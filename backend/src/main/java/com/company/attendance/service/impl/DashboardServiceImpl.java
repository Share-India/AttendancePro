package com.company.attendance.service.impl;

import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.dashboard.AttendanceTrendResponse;
import com.company.attendance.dto.response.dashboard.DashboardSummaryResponse;
import com.company.attendance.dto.response.dashboard.TeamMemberDayResponse;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.entity.Shift;
import com.company.attendance.enums.AccessScope;
import com.company.attendance.enums.DayState;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceRegularizationRepository;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.LeaveRequestDayRepository;
import com.company.attendance.repository.LeaveRequestRepository;
import com.company.attendance.repository.WfhRequestDayRepository;
import com.company.attendance.repository.WorkFromHomeRequestRepository;
import com.company.attendance.security.AccessScopeResolver;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.CalendarService;
import com.company.attendance.service.DashboardService;
import com.company.attendance.service.RequestApproverResolver;
import com.company.attendance.util.DateTimeUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Team and organisation roll-ups.
 *
 * <p>The whole class turns on one rule: a person who is away is not
 * automatically a problem. Approved leave, a weekly off and a public holiday
 * are each counted as themselves, and only a working day with no punch and a
 * finished shift reports as an absence. A manager who cannot tell those apart
 * ends up chasing people who did nothing wrong, and stops trusting the screen.
 *
 * <p>Everything here is batched. The naive shape — walk the team, ask the
 * calendar and the attendance table per person — is two queries per employee on
 * the most-opened screen in the product. Rows are fetched once per day and
 * joined in memory instead.
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    private static final String PERM_VIEW_ALL = "ATTENDANCE_VIEW_ALL";
    private static final String PERM_VIEW_TEAM = "ATTENDANCE_VIEW_TEAM";

    /** A trend longer than this is a report, not a dashboard widget. */
    private static final int MAX_TREND_DAYS = 92;

    private final EmployeeRepository employeeRepository;
    private final EmployeeShiftRepository employeeShiftRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRequestDayRepository leaveRequestDayRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final WfhRequestDayRepository wfhRequestDayRepository;
    private final AttendanceRegularizationRepository regularizationRepository;
    private final WorkFromHomeRequestRepository wfhRepository;
    private final CalendarService calendarService;
    private final AccessScopeResolver accessScopeResolver;
    private final RequestApproverResolver approverResolver;

    public DashboardServiceImpl(EmployeeRepository employeeRepository,
                                EmployeeShiftRepository employeeShiftRepository,
                                AttendanceRepository attendanceRepository,
                                LeaveRequestDayRepository leaveRequestDayRepository,
                                LeaveRequestRepository leaveRequestRepository,
                                WfhRequestDayRepository wfhRequestDayRepository,
                                AttendanceRegularizationRepository regularizationRepository,
                                WorkFromHomeRequestRepository wfhRepository,
                                CalendarService calendarService,
                                AccessScopeResolver accessScopeResolver,
                                RequestApproverResolver approverResolver) {
        this.employeeRepository = employeeRepository;
        this.employeeShiftRepository = employeeShiftRepository;
        this.attendanceRepository = attendanceRepository;
        this.leaveRequestDayRepository = leaveRequestDayRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.wfhRequestDayRepository = wfhRequestDayRepository;
        this.regularizationRepository = regularizationRepository;
        this.wfhRepository = wfhRepository;
        this.calendarService = calendarService;
        this.accessScopeResolver = accessScopeResolver;
        this.approverResolver = approverResolver;
    }

    // -----------------------------------------------------------------
    // Summary
    // -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public DashboardSummaryResponse getSummary(UserPrincipal principal,
                                               LocalDate date,
                                               Long departmentId,
                                               Long officeId) {

        AccessScope scope = requireTeamOrOrg(principal);
        LocalDate day = date != null ? date : LocalDate.now();

        List<Employee> population = population(principal, scope, departmentId, officeId);
        List<MemberDay> days = resolveDays(population, day);

        Map<DayState, Integer> counts = countByState(days);

        int expected = expectedCount(days);
        int present = counts.getOrDefault(DayState.PRESENT, 0);
        int wfh = counts.getOrDefault(DayState.WORKING_FROM_HOME, 0);

        int late = (int) days.stream()
                .filter(member -> member.attendance() != null && member.attendance().getLateMinutes() > 0)
                .count();
        int punchedOut = (int) days.stream()
                .filter(member -> member.attendance() != null && member.attendance().getPunchOutTime() != null)
                .count();
        int currentlyIn = (int) days.stream()
                .filter(member -> member.attendance() != null
                        && member.attendance().getPunchInTime() != null
                        && member.attendance().getPunchOutTime() == null)
                .count();

        // Rate against people who were supposed to work, never against
        // headcount — otherwise every weekend reads as a collapse.
        double rate = expected == 0 ? 0d
                : Math.round((present + wfh) * 1000d / expected) / 10d;

        return new DashboardSummaryResponse(
                day,
                scope.name(),
                // Everyone employed on this date — the same population the
                // states below describe, so the two can never disagree.
                days.size(),
                expected,
                present,
                wfh,
                counts.getOrDefault(DayState.ON_LEAVE, 0),
                counts.getOrDefault(DayState.WEEKLY_OFF, 0),
                counts.getOrDefault(DayState.HOLIDAY, 0),
                counts.getOrDefault(DayState.NOT_YET_IN, 0),
                counts.getOrDefault(DayState.ABSENT, 0),
                late,
                punchedOut,
                currentlyIn,
                rate,
                pendingApprovals(principal),
                departmentBreakdown(days));
    }

    // -----------------------------------------------------------------
    // Roster
    // -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TeamMemberDayResponse> getRoster(UserPrincipal principal,
                                                         LocalDate date,
                                                         String dayState,
                                                         Long departmentId,
                                                         Long officeId,
                                                         String search,
                                                         Pageable pageable) {

        AccessScope scope = requireTeamOrOrg(principal);
        LocalDate day = date != null ? date : LocalDate.now();

        List<Employee> population = population(principal, scope, departmentId, officeId);
        List<MemberDay> days = resolveDays(population, day);

        DayState wanted = parseDayState(dayState);
        String needle = search == null ? null : search.trim().toLowerCase(Locale.ROOT);

        List<TeamMemberDayResponse> rows = days.stream()
                .filter(member -> wanted == null || member.state() == wanted)
                .filter(member -> needle == null || needle.isEmpty() || matches(member.employee(), needle))
                // Whoever needs attention first: absent, then not yet in, then
                // the rest alphabetically. A roster sorted by id helps nobody.
                .sorted(Comparator
                        .comparingInt((MemberDay member) -> urgency(member.state()))
                        .thenComparing(member -> member.employee().getFullName(),
                                String.CASE_INSENSITIVE_ORDER))
                .map(this::toResponse)
                .toList();

        return PageResponse.of(paginate(rows, pageable));
    }

    // -----------------------------------------------------------------
    // Trend
    // -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public AttendanceTrendResponse getTrend(UserPrincipal principal, LocalDate fromDate, LocalDate toDate) {
        AccessScope scope = requireTeamOrOrg(principal);

        LocalDate to = toDate != null ? toDate : LocalDate.now();
        LocalDate from = fromDate != null ? fromDate : to.minusDays(29);

        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "The start of the range comes after its end.");
        }
        if (from.plusDays(MAX_TREND_DAYS).isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "A trend covers at most " + MAX_TREND_DAYS + " days. Use a report for longer periods.");
        }

        List<Employee> population = population(principal, scope, null, null);
        Set<Long> ids = population.stream().map(Employee::getId).collect(Collectors.toSet());

        if (ids.isEmpty()) {
            return new AttendanceTrendResponse(from, to, List.of());
        }

        // Three queries for the whole range, not three per day.
        Map<LocalDate, List<Attendance>> attendanceByDate =
                attendanceRepository.findForEmployeesBetween(ids, from, to).stream()
                        .collect(Collectors.groupingBy(Attendance::getAttendanceDate));

        Map<LocalDate, Set<Long>> leaveByDate =
                leaveRequestDayRepository.findApprovedForEmployeesBetween(ids, from, to).stream()
                        .collect(Collectors.groupingBy(LeaveRequestDay::getLeaveDate,
                                Collectors.mapping(LeaveRequestDay::getEmployeeId, Collectors.toSet())));

        List<AttendanceTrendResponse.Point> points = new ArrayList<>();

        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            Map<Long, DayType> dayTypes = calendarService.resolveDayTypes(population, day);
            List<Attendance> rows = attendanceByDate.getOrDefault(day, List.of());
            Set<Long> onLeave = leaveByDate.getOrDefault(day, Set.of());

            Map<Long, Attendance> byEmployee = rows.stream()
                    .collect(Collectors.toMap(a -> a.getEmployee().getId(), Function.identity(),
                            (first, second) -> first));

            int expected = 0;
            int present = 0;
            int wfh = 0;
            int absent = 0;
            int late = 0;

            for (Employee employee : population) {
                if (!wasEmployedOn(employee, day)) {
                    continue;
                }
                DayType dayType = dayTypes.getOrDefault(employee.getId(), DayType.WORKING);
                boolean leave = onLeave.contains(employee.getId());
                Attendance attendance = byEmployee.get(employee.getId());

                if (dayType != DayType.WORKING || leave) {
                    // Still counts if they actually worked it — someone who
                    // punches on a holiday should not vanish from the chart.
                    if (attendance != null && attendance.getPunchInTime() != null) {
                        present++;
                    }
                    continue;
                }

                expected++;
                if (attendance == null || attendance.getPunchInTime() == null) {
                    absent++;
                    continue;
                }
                if (attendance.getWorkMode() != null && "WFH".equals(attendance.getWorkMode().name())) {
                    wfh++;
                } else {
                    present++;
                }
                if (attendance.getLateMinutes() > 0) {
                    late++;
                }
            }

            boolean working = expected > 0;
            points.add(new AttendanceTrendResponse.Point(
                    day, working, expected, present, wfh, onLeave.size(), absent, late));
        }

        return new AttendanceTrendResponse(from, to, points);
    }

    // -----------------------------------------------------------------
    // The shared resolution
    // -----------------------------------------------------------------

    /** One employee's day, with everything needed to classify it. */
    private record MemberDay(Employee employee,
                             LocalDate date,
                             DayState state,
                             Attendance attendance,
                             Shift shift,
                             LeaveRequestDay leaveDay) {
    }

    /**
     * Whether this person was on the payroll on this date.
     *
     * <p>Without it, someone who joins next month is reported absent today, and
     * a four-week trend marks every new joiner absent for the weeks before they
     * started — which is how a chart ends up a wall of red on a system that has
     * simply not been running very long.
     */
    private boolean wasEmployedOn(Employee employee, LocalDate date) {
        if (employee.getJoiningDate() != null && date.isBefore(employee.getJoiningDate())) {
            return false;
        }
        return employee.getExitDate() == null || !date.isAfter(employee.getExitDate());
    }

    /**
     * Classifies a whole population for one date in a fixed number of queries.
     */
    private List<MemberDay> resolveDays(List<Employee> population, LocalDate date) {
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

        Map<Long, Shift> shifts = shiftsFor(ids, date);

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
        // whether the day is over — calling someone absent at 09:05 turns a
        // clock reading into an accusation.
        return hasShiftEnded(employee, date, now, shift) ? DayState.ABSENT : DayState.NOT_YET_IN;
    }

    /**
     * Whether the chance to turn up has passed.
     *
     * <p>Resolved in the employee's own office timezone, so a Singapore office
     * is not marked absent because it is still morning at head office. With no
     * shift on record the day is treated as over once the date itself has
     * passed — the safer of the two errors, since it never accuses anyone whose
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

    private Map<Long, Shift> shiftsFor(Set<Long> employeeIds, LocalDate date) {
        // findEffectiveOn is per employee; the roster is paginated and a team is
        // small, but an org-wide call would be N queries. Kept explicit here so
        // the cost is visible rather than hidden behind a lazy association.
        Map<Long, Shift> shifts = new HashMap<>();
        for (Long employeeId : employeeIds) {
            employeeShiftRepository.findCurrentFor(employeeId, date)
                    .map(EmployeeShift::getShift)
                    .ifPresent(shift -> shifts.put(employeeId, shift));
        }
        return shifts;
    }

    // -----------------------------------------------------------------
    // Population and scope
    // -----------------------------------------------------------------

    private AccessScope requireTeamOrOrg(UserPrincipal principal) {
        AccessScope scope = accessScopeResolver.resolve(principal, PERM_VIEW_ALL, PERM_VIEW_TEAM);
        if (scope == AccessScope.SELF) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "This view is for managers and HR. Your own day is on your dashboard.");
        }
        return scope;
    }

    /**
     * Everyone the caller may see, optionally narrowed by department or office.
     *
     * <p>The filters narrow what is already permitted; they never widen it. A
     * manager passing a department id still sees only their own team within it.
     */
    private List<Employee> population(UserPrincipal principal,
                                      AccessScope scope,
                                      Long departmentId,
                                      Long officeId) {

        Optional<Set<Long>> visible = accessScopeResolver.visibleEmployeeIds(principal, scope);

        List<Employee> employees = visible
                .map(ids -> ids.isEmpty() ? List.<Employee>of() : employeeRepository.findAllById(ids))
                .orElseGet(employeeRepository::findAll);

        return employees.stream()
                .filter(Employee::isEmployable)
                // A manager is not part of their own team roll-up; they are
                // looking at the people they are responsible for.
                .filter(employee -> scope == AccessScope.ORG
                        || !employee.getId().equals(principal.getEmployeeId()))
                .filter(employee -> departmentId == null
                        || (employee.getDepartment() != null
                        && departmentId.equals(employee.getDepartment().getId())))
                .filter(employee -> officeId == null
                        || (employee.getOffice() != null
                        && officeId.equals(employee.getOffice().getId())))
                .toList();
    }

    // -----------------------------------------------------------------
    // Counting
    // -----------------------------------------------------------------

    private Map<DayState, Integer> countByState(List<MemberDay> days) {
        Map<DayState, Integer> counts = new HashMap<>();
        for (MemberDay member : days) {
            counts.merge(member.state(), 1, Integer::sum);
        }
        return counts;
    }

    /** People whose day was supposed to be a working one. */
    private int expectedCount(List<MemberDay> days) {
        return (int) days.stream()
                .filter(member -> switch (member.state()) {
                    case WEEKLY_OFF, HOLIDAY, ON_LEAVE -> false;
                    default -> true;
                })
                .count();
    }

    private List<DashboardSummaryResponse.GroupCount> departmentBreakdown(List<MemberDay> days) {
        Map<Long, List<MemberDay>> grouped = days.stream()
                .collect(Collectors.groupingBy(
                        member -> member.employee().getDepartment() == null
                                ? -1L : member.employee().getDepartment().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        return grouped.entrySet().stream()
                .map(entry -> {
                    List<MemberDay> members = entry.getValue();
                    Map<DayState, Integer> counts = countByState(members);
                    String name = members.get(0).employee().getDepartment() == null
                            ? "Unassigned"
                            : members.get(0).employee().getDepartment().getName();
                    return new DashboardSummaryResponse.GroupCount(
                            entry.getKey() == -1L ? null : entry.getKey(),
                            name,
                            expectedCount(members),
                            counts.getOrDefault(DayState.PRESENT, 0),
                            counts.getOrDefault(DayState.WORKING_FROM_HOME, 0),
                            counts.getOrDefault(DayState.ON_LEAVE, 0),
                            counts.getOrDefault(DayState.ABSENT, 0));
                })
                .sorted(Comparator.comparing(DashboardSummaryResponse.GroupCount::name,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * What is waiting on this caller across all three request types.
     *
     * <p>Counted through the same queries the inboxes use, so the badge on the
     * dashboard and the list behind it can never disagree.
     */
    private DashboardSummaryResponse.PendingApprovals pendingApprovals(UserPrincipal principal) {
        if (principal.getEmployeeId() == null) {
            return new DashboardSummaryResponse.PendingApprovals(0, 0, 0, 0);
        }

        Pageable one = PageRequest.of(0, 1);
        Long me = principal.getEmployeeId();

        int leave = principal.hasPermission("LEAVE_APPROVE_L1") || principal.hasPermission("LEAVE_APPROVE_L2")
                ? (int) leaveRequestRepository.findPendingForApprover(me, one).getTotalElements()
                : 0;

        boolean canApproveRegularization = principal.hasPermission("REGULARIZATION_APPROVE_L1")
                || principal.hasPermission("REGULARIZATION_APPROVE_L2");
        int regularization = canApproveRegularization
                ? (int) regularizationRepository.findPendingForApprover(
                        me,
                        approverResolver.subordinateIdsOf(me, LocalDate.now()),
                        principal.hasPermission("REGULARIZATION_APPROVE_L2"),
                        one).getTotalElements()
                : 0;

        int workFromHome = principal.hasPermission("WFH_APPROVE")
                ? (int) wfhRepository.findPendingForApprover(
                        me,
                        approverResolver.subordinateIdsOf(me, LocalDate.now()),
                        principal.hasPermission("WFH_APPROVE") && principal.hasPermission(PERM_VIEW_ALL),
                        one).getTotalElements()
                : 0;

        return new DashboardSummaryResponse.PendingApprovals(
                leave, regularization, workFromHome, leave + regularization + workFromHome);
    }

    // -----------------------------------------------------------------
    // Mapping and small helpers
    // -----------------------------------------------------------------

    private TeamMemberDayResponse toResponse(MemberDay member) {
        Employee employee = member.employee();
        Attendance attendance = member.attendance();
        Shift shift = member.shift();

        return new TeamMemberDayResponse(
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                employee.getDepartment() == null ? null : employee.getDepartment().getName(),
                employee.getDesignation() == null ? null : employee.getDesignation().getName(),
                employee.getOffice() == null ? null : employee.getOffice().getName(),
                member.date(),
                member.state().name(),
                shift == null ? null : shift.getName(),
                shift == null ? null : shift.getStartTime(),
                shift == null ? null : shift.getEndTime(),
                attendance == null ? null : attendance.getPunchInTime(),
                attendance == null ? null : attendance.getPunchOutTime(),
                attendance == null ? null : attendance.getWorkingMinutes(),
                attendance == null ? null : (int) attendance.getLateMinutes(),
                attendance == null || attendance.getWorkMode() == null
                        ? null : attendance.getWorkMode().name(),
                attendance == null || attendance.getAttendanceStatus() == null
                        ? null : attendance.getAttendanceStatus().name(),
                member.leaveDay() == null ? null
                        : member.leaveDay().getLeaveRequest().getLeaveType().getName(),
                member.leaveDay() == null ? null : member.leaveDay().getDayPortion().name());
    }

    /** Absent first, then not-yet-in: the roster is a to-do list, not a register. */
    private int urgency(DayState state) {
        return switch (state) {
            case ABSENT -> 0;
            case NOT_YET_IN -> 1;
            case PRESENT, WORKING_FROM_HOME -> 2;
            case ON_LEAVE -> 3;
            case WEEKLY_OFF, HOLIDAY -> 4;
        };
    }

    private boolean matches(Employee employee, String needle) {
        return employee.getFullName().toLowerCase(Locale.ROOT).contains(needle)
                || employee.getEmployeeCode().toLowerCase(Locale.ROOT).contains(needle);
    }

    private DayState parseDayState(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DayState.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "Unknown day state '" + value + "'.");
        }
    }

    /**
     * Pages a list that had to be assembled in memory.
     *
     * <p>The classification depends on the calendar and on approved leave, so
     * it cannot be expressed as one sortable SQL query without denormalising
     * the day state into a table. That is a fair trade at team and single-office
     * size; it is the first thing to revisit if an org grows past a few thousand.
     */
    private Page<TeamMemberDayResponse> paginate(List<TeamMemberDayResponse> rows, Pageable pageable) {
        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }
}
