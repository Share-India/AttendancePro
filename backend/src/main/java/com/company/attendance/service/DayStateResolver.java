package com.company.attendance.service;

import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.entity.Shift;
import com.company.attendance.enums.DayState;
import com.company.attendance.enums.DayType;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.LeaveRequestDayRepository;
import com.company.attendance.repository.WfhRequestDayRepository;
import com.company.attendance.util.DateTimeUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where a group of people stand on one date.
 *
 * <p>Lifted out of the dashboard because a second caller needed the same
 * answer. The reminder that chases a missing punch has to know exactly what
 * the dashboard knows -- that this person is on approved leave, that it is
 * their weekly off, that the company is closed -- and a second implementation
 * of that would drift from the first. When it drifted the symptom would be an
 * email accusing somebody on sanctioned leave of not turning up, which is the
 * kind of mistake people remember.
 *
 * <p>So there is one classifier and both callers use it. The dashboard shows
 * its answer; the reminder acts on it.
 */
@Service
public class DayStateResolver {

    private final AttendanceRepository attendanceRepository;
    private final LeaveRequestDayRepository leaveRequestDayRepository;
    private final WfhRequestDayRepository wfhRequestDayRepository;
    private final ShiftResolutionService shiftResolutionService;
    private final CalendarService calendarService;

    public DayStateResolver(AttendanceRepository attendanceRepository,
                            LeaveRequestDayRepository leaveRequestDayRepository,
                            WfhRequestDayRepository wfhRequestDayRepository,
                            ShiftResolutionService shiftResolutionService,
                            CalendarService calendarService) {
        this.attendanceRepository = attendanceRepository;
        this.leaveRequestDayRepository = leaveRequestDayRepository;
        this.wfhRequestDayRepository = wfhRequestDayRepository;
        this.shiftResolutionService = shiftResolutionService;
        this.calendarService = calendarService;
    }

    /** One employee's day, with everything needed to classify it. */
    public record MemberDay(Employee employee,
                            LocalDate date,
                            DayState state,
                            Attendance attendance,
                            Shift shift,
                            LeaveRequestDay leaveDay) {
    }

    /**
     * Classifies a whole population for one date in a fixed number of queries.
     */
    @Transactional(readOnly = true)
    public List<MemberDay> resolveDays(List<Employee> population, LocalDate date) {
        if (population.isEmpty()) {
            return List.of();
        }

        Set<Long> ids = population.stream().map(Employee::getId).collect(Collectors.toSet());

        Map<Long, DayType> dayTypes = calendarService.resolveDayTypes(population, date);

        Map<Long, Attendance> attendance =
                attendanceRepository.findForEmployeesOn(ids, date).stream()
                        .collect(Collectors.toMap(a -> a.getEmployee().getId(), Function.identity(),
                                (first, second) -> first));

        Map<Long, LeaveRequestDay> leave =
                leaveRequestDayRepository.findApprovedForEmployeesOn(ids, date).stream()
                        .collect(Collectors.toMap(LeaveRequestDay::getEmployeeId, Function.identity(),
                                (first, second) -> first));

        Set<Long> wfhApproved = new HashSet<>(
                wfhRequestDayRepository.findApprovedEmployeeIdsOn(ids, date));

        Map<Long, Shift> shifts = shiftsFor(population, date);

        Instant now = Instant.now();
        List<MemberDay> result = new ArrayList<>(population.size());

        for (Employee employee : population) {
            if (!wasEmployedOn(employee, date)) {
                continue;
            }
            Attendance row = attendance.get(employee.getId());
            Shift shift = shifts.get(employee.getId());
            LeaveRequestDay leaveDay = leave.get(employee.getId());
            DayType dayType = dayTypes.getOrDefault(employee.getId(), DayType.WORKING);

            DayState state = classify(employee, date, now, dayType, row, leaveDay,
                    wfhApproved.contains(employee.getId()), shift);

            result.add(new MemberDay(employee, date, state, row, shift, leaveDay));
        }
        return result;
    }

    /**
     * Whether this person was on the payroll on this date.
     *
     * <p>Without it, someone who joins next month is reported absent today, and
     * a four-week trend marks every new joiner absent for the weeks before they
     * started -- which is how a chart ends up a wall of red on a system that has
     * simply not been running very long.
     */
    public boolean wasEmployedOn(Employee employee, LocalDate date) {
        if (employee.getJoiningDate() != null && date.isBefore(employee.getJoiningDate())) {
            return false;
        }
        return employee.getExitDate() == null || !date.isAfter(employee.getExitDate());
    }

    /**
     * Where one person stands.
     *
     * <p>Order matters. A punch outranks everything: someone who worked a
     * holiday worked it, and reporting them as on holiday would erase the hours
     * they are owed. Only after that do the reasons for being away apply.
     */
    private DayState classify(Employee employee,
                              LocalDate date,
                              Instant now,
                              DayType dayType,
                              Attendance attendance,
                              LeaveRequestDay leaveDay,
                              boolean wfhApproved,
                              Shift shift) {

        if (attendance != null && attendance.getPunchInTime() != null) {
            boolean workedFromHome = wfhApproved
                    || (attendance.getWorkMode() != null && "WFH".equals(attendance.getWorkMode().name()));
            return workedFromHome ? DayState.WORKING_FROM_HOME : DayState.PRESENT;
        }

        if (leaveDay != null) {
            return DayState.ON_LEAVE;
        }
        if (dayType == DayType.HOLIDAY) {
            return DayState.HOLIDAY;
        }
        if (dayType == DayType.WEEKLY_OFF) {
            return DayState.WEEKLY_OFF;
        }

        // A working day with nobody home. Whether that is a problem depends on
        // whether the day is over -- calling someone absent at 09:05 turns a
        // clock reading into an accusation.
        // Before go-live there was no punch to miss, so the day is reported
        // as nothing-expected rather than absent. NOT_YET_IN rather than a new
        // enum value: it already means "no attendance, not a failure", and the
        // counts that matter treat it the same way.
        if (!calendarService.isTracked(date)) {
            return DayState.NOT_YET_IN;
        }
        return hasShiftEnded(employee, date, now, shift) ? DayState.ABSENT : DayState.NOT_YET_IN;
    }

    /**
     * Whether the chance to turn up has passed.
     *
     * <p>Resolved in the employee's own office timezone, so a Singapore office
     * is not marked absent because it is still morning at head office. With no
     * shift on record the day is treated as over once the date itself has
     * passed -- the safer of the two errors, since it never accuses anyone whose
     * day is still running.
     */
    private boolean hasShiftEnded(Employee employee, LocalDate date, Instant now, Shift shift) {
        ZoneId zone = DateTimeUtils.zoneOf(employee);
        if (date.isBefore(DateTimeUtils.localDate(now, zone))) {
            return true;
        }
        if (shift == null) {
            return false;
        }
        return now.isAfter(DateTimeUtils.shiftEndInstant(date, zone, shift));
    }

    private Map<Long, Shift> shiftsFor(Collection<Employee> population, LocalDate date) {
        // Per employee; the roster is paginated and a team is small, but an
        // org-wide call would be N queries. Kept explicit here so the cost is
        // visible rather than hidden behind a lazy association.
        //
        // Entities rather than ids because a weekday shift override can be
        // scoped to a department or an office, which an id alone cannot answer.
        Map<Long, Shift> shifts = new HashMap<>();
        for (Employee employee : population) {
            shiftResolutionService.resolve(employee, date)
                    .ifPresent(shift -> shifts.put(employee.getId(), shift));
        }
        return shifts;
    }
}
