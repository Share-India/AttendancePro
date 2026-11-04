package com.company.attendance.service.impl;

import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.attendance.AttendanceCalendarResponse;
import com.company.attendance.dto.response.attendance.AttendanceResponse;
import com.company.attendance.dto.response.attendance.AttendanceStatusResponse;
import com.company.attendance.dto.response.attendance.AttendanceSummaryResponse;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendanceBreak;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Holiday;
import com.company.attendance.entity.Shift;
import com.company.attendance.enums.AccessScope;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceBreakRepository;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.security.AccessScopeResolver;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.AttendanceService;
import com.company.attendance.service.CalendarService;
import com.company.attendance.util.DateTimeUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AttendanceServiceImpl implements AttendanceService {

    private static final String PERM_VIEW_ALL = "ATTENDANCE_VIEW_ALL";
    private static final String PERM_VIEW_TEAM = "ATTENDANCE_VIEW_TEAM";

    private final AttendanceRepository attendanceRepository;
    private final AttendanceBreakRepository breakRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeShiftRepository employeeShiftRepository;
    private final CalendarService calendarService;
    private final AccessScopeResolver accessScopeResolver;
    private final ObjectMapper objectMapper;

    public AttendanceServiceImpl(AttendanceRepository attendanceRepository,
                                 AttendanceBreakRepository breakRepository,
                                 EmployeeRepository employeeRepository,
                                 EmployeeShiftRepository employeeShiftRepository,
                                 CalendarService calendarService,
                                 AccessScopeResolver accessScopeResolver,
                                 ObjectMapper objectMapper) {
        this.attendanceRepository = attendanceRepository;
        this.breakRepository = breakRepository;
        this.employeeRepository = employeeRepository;
        this.employeeShiftRepository = employeeShiftRepository;
        this.calendarService = calendarService;
        this.accessScopeResolver = accessScopeResolver;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------
    // Live status
    // -----------------------------------------------------------------

    /**
     * What the employee can do right now.
     *
     * <p>Every minute figure here is computed server-side. The browser animates
     * seconds between polls, but it never derives the number itself — a wrong
     * client clock must not be able to inflate hours.
     */
    @Override
    @Transactional(readOnly = true)
    public AttendanceStatusResponse getCurrentStatus(UserPrincipal principal) {
        Instant now = Instant.now();
        Employee employee = requireSelf(principal);
        ZoneId zone = DateTimeUtils.zoneOf(employee);

        Optional<Shift> shift = employeeShiftRepository
                .findCurrentFor(employee.getId(), DateTimeUtils.localDate(now, zone))
                .map(assignment -> assignment.getShift());

        LocalDate businessDate = DateTimeUtils.resolveBusinessDate(now, zone, shift.orElse(null));
        DayType dayType = calendarService.resolveDayType(employee, businessDate);

        Optional<Attendance> today = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employee.getId(), businessDate);

        Optional<AttendanceBreak> openBreak = today
                .flatMap(attendance -> breakRepository.findOpenBreak(attendance.getId()));

        boolean punchedIn = today.map(Attendance::isPunchedIn).orElse(false);
        boolean punchedOut = today.map(Attendance::isPunchedOut).orElse(false);
        boolean onBreak = openBreak.isPresent();

        int workedMinutes = today.map(attendance -> attendance.elapsedWorkingMinutes(now)).orElse(0);
        int breakMinutes = today.map(Attendance::getBreakMinutes).orElse(0);

        // An open break's minutes are not yet in break_minutes, so add them here
        // or the live timer would keep counting break time as work.
        if (onBreak) {
            int openBreakMinutes = DateTimeUtils.minutesBetween(openBreak.get().getBreakStartTime(), now);
            breakMinutes += openBreakMinutes;
            workedMinutes = Math.max(0, workedMinutes - openBreakMinutes);
        }

        String blockedReason = null;
        if (shift.isEmpty()) {
            blockedReason = "No shift is assigned to you for today. Ask HR to assign one.";
        } else if (punchedOut) {
            blockedReason = "You have already completed today.";
        } else if (dayType == DayType.HOLIDAY) {
            blockedReason = holidayName(employee, businessDate)
                    .map(name -> "Today is a holiday (" + name + ").")
                    .orElse("Today is a holiday.");
        } else if (dayType == DayType.WEEKLY_OFF) {
            blockedReason = "Today is your weekly off.";
        }

        boolean canPunchIn = !punchedIn && !punchedOut && shift.isPresent();
        boolean canPunchOut = punchedIn && !punchedOut;

        return new AttendanceStatusResponse(
                today.map(Attendance::getId).orElse(null),
                businessDate,
                today.map(a -> a.getAttendanceStatus().name()).orElse("NOT_PUNCHED_IN"),
                dayType.name(),
                today.map(a -> a.getWorkMode().name()).orElse("OFFICE"),
                today.map(Attendance::getPunchInTime).orElse(null),
                today.map(Attendance::getPunchOutTime).orElse(null),
                now,
                workedMinutes,
                breakMinutes,
                shift.map(Shift::getStandardWorkMinutes).map(Short::intValue).orElse(0),
                today.map(Attendance::getLateMinutes).orElse(0),
                today.map(Attendance::getEarlyExitMinutes).orElse(0),
                today.map(Attendance::getOvertimeMinutes).orElse(0),
                onBreak,
                openBreak.map(AttendanceBreak::getBreakStartTime).orElse(null),
                openBreak.map(b -> b.getBreakType().getName()).orElse(null),
                canPunchIn,
                canPunchOut,
                punchedIn && !punchedOut && !onBreak,
                onBreak,
                blockedReason,
                shift.map(this::toShiftSummary).orElse(null),
                today.map(Attendance::getPunchInLocation).orElse(null),
                today.map(Attendance::getPunchOutLocation).orElse(null),
                today.map(Attendance::isSuspicious).orElse(false),
                today.map(this::parseFlags).orElse(List.of()));
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceResponse getToday(UserPrincipal principal) {
        Instant now = Instant.now();
        Employee employee = requireSelf(principal);
        ZoneId zone = DateTimeUtils.zoneOf(employee);

        Shift shift = employeeShiftRepository
                .findCurrentFor(employee.getId(), DateTimeUtils.localDate(now, zone))
                .map(assignment -> assignment.getShift())
                .orElse(null);

        LocalDate businessDate = DateTimeUtils.resolveBusinessDate(now, zone, shift);

        return attendanceRepository.findByEmployeeIdAndAttendanceDate(employee.getId(), businessDate)
                .map(this::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTENDANCE_NOT_FOUND,
                        "You have not punched in today."));
    }

    // -----------------------------------------------------------------
    // History and calendar
    // -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AttendanceResponse> getHistory(UserPrincipal principal,
                                                       Long employeeId,
                                                       LocalDate from,
                                                       LocalDate to,
                                                       Pageable pageable) {
        Long targetId = resolveTarget(principal, employeeId);

        if (from == null) {
            from = LocalDate.now().withDayOfMonth(1);
        }
        if (to == null) {
            to = LocalDate.now();
        }
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE, "The end date is before the start date.");
        }

        Page<Attendance> page = attendanceRepository
                .findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(targetId, from, to, pageable);

        return PageResponse.of(page, this::toResponse);
    }

    /**
     * A month of days, including the ones nobody worked.
     *
     * <p>Holidays and weekly offs are filled in from the calendar rather than
     * left blank, so a gap in the grid always means something — an absence, not
     * simply a day the system had nothing to say about.
     */
    @Override
    @Transactional(readOnly = true)
    public AttendanceCalendarResponse getCalendar(UserPrincipal principal, Long employeeId, int year, int month) {
        Long targetId = resolveTarget(principal, employeeId);
        Employee employee = employeeRepository.findById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found"));

        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate first = yearMonth.atDay(1);
        LocalDate last = yearMonth.atEndOfMonth();

        Map<LocalDate, Attendance> byDate = new HashMap<>();
        attendanceRepository
                .findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(targetId, first, last)
                .forEach(attendance -> byDate.put(attendance.getAttendanceDate(), attendance));

        Map<LocalDate, String> holidays = new HashMap<>();
        calendarService.findHolidaysBetween(first, last)
                .forEach(holiday -> holidays.put(holiday.getHolidayDate(), holiday.getName()));

        List<AttendanceCalendarResponse.CalendarDay> days = first.datesUntil(last.plusDays(1))
                .map(date -> buildCalendarDay(employee, date, byDate.get(date), holidays.get(date)))
                .toList();

        return new AttendanceCalendarResponse(year, month, days, buildSummary(employee, year, month, byDate, holidays));
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceSummaryResponse getSummary(UserPrincipal principal, Long employeeId, int year, int month) {
        Long targetId = resolveTarget(principal, employeeId);
        Employee employee = employeeRepository.findById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found"));

        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate first = yearMonth.atDay(1);
        LocalDate last = yearMonth.atEndOfMonth();

        Map<LocalDate, Attendance> byDate = new HashMap<>();
        attendanceRepository
                .findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(targetId, first, last)
                .forEach(attendance -> byDate.put(attendance.getAttendanceDate(), attendance));

        Map<LocalDate, String> holidays = new HashMap<>();
        calendarService.findHolidaysBetween(first, last)
                .forEach(holiday -> holidays.put(holiday.getHolidayDate(), holiday.getName()));

        return buildSummary(employee, year, month, byDate, holidays);
    }

    // -----------------------------------------------------------------

    private AttendanceCalendarResponse.CalendarDay buildCalendarDay(Employee employee,
                                                                    LocalDate date,
                                                                    Attendance attendance,
                                                                    String holidayName) {
        DayType dayType = holidayName != null
                ? DayType.HOLIDAY
                : calendarService.isWeeklyOff(employee, date) ? DayType.WEEKLY_OFF : DayType.WORKING;

        String status;
        if (attendance != null) {
            status = attendance.getAttendanceStatus().name();
        } else if (dayType == DayType.HOLIDAY) {
            status = AttendanceStatus.HOLIDAY.name();
        } else if (dayType == DayType.WEEKLY_OFF) {
            status = AttendanceStatus.WEEKLY_OFF.name();
        } else if (date.isAfter(LocalDate.now())) {
            // A future working day is simply not yet known; calling it absent
            // would light the calendar up with failures that have not happened.
            status = "UPCOMING";
        } else {
            status = AttendanceStatus.ABSENT.name();
        }

        return new AttendanceCalendarResponse.CalendarDay(
                date,
                date.getDayOfWeek().name(),
                dayType.name(),
                status,
                attendance != null ? attendance.getWorkingMinutes() : null,
                attendance != null ? DateTimeUtils.formatMinutes(attendance.getWorkingMinutes()) : null,
                attendance != null ? attendance.getLateMinutes() : null,
                holidayName,
                attendance != null);
    }

    private AttendanceSummaryResponse buildSummary(Employee employee, int year, int month,
                                                   Map<LocalDate, Attendance> byDate,
                                                   Map<LocalDate, String> holidays) {
        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate first = yearMonth.atDay(1);
        LocalDate last = yearMonth.atEndOfMonth();
        LocalDate today = LocalDate.now();

        int present = 0, absent = 0, half = 0, leave = 0, holiday = 0, weeklyOff = 0, wfh = 0;
        int lateMarks = 0, earlyExits = 0, workedMinutes = 0, overtimeMinutes = 0;
        int workingDaysElapsed = 0;

        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            boolean isHoliday = holidays.containsKey(date);
            boolean isWeeklyOff = !isHoliday && calendarService.isWeeklyOff(employee, date);
            Attendance attendance = byDate.get(date);

            if (isHoliday) {
                holiday++;
            } else if (isWeeklyOff) {
                weeklyOff++;
            }

            if (attendance != null) {
                workedMinutes += attendance.getWorkingMinutes();
                overtimeMinutes += attendance.getOvertimeMinutes();
                if (attendance.getLateMinutes() > 0) {
                    lateMarks++;
                }
                if (attendance.getEarlyExitMinutes() > 0) {
                    earlyExits++;
                }

                switch (attendance.getAttendanceStatus()) {
                    case PRESENT, REGULARIZED, ON_DUTY -> present++;
                    case WFH -> {
                        wfh++;
                        present++;
                    }
                    case HALF_DAY -> half++;
                    case ON_LEAVE, HALF_DAY_LEAVE -> leave++;
                    case ABSENT, INSUFFICIENT_HOURS -> absent++;
                    default -> {
                        // Open or non-working states contribute no count.
                    }
                }
            } else if (!isHoliday && !isWeeklyOff && !date.isAfter(today)) {
                absent++;
            }

            // Percentage is measured against days that have actually passed, so
            // early in a month it is not dragged down by days yet to come.
            if (!isHoliday && !isWeeklyOff && !date.isAfter(today)) {
                workingDaysElapsed++;
            }
        }

        double percentage = workingDaysElapsed == 0
                ? 0
                : Math.round(((present + (half * 0.5)) / workingDaysElapsed) * 1000) / 10.0;

        int daysWithWork = (int) byDate.values().stream()
                .filter(a -> a.getWorkingMinutes() > 0).count();
        int averageMinutes = daysWithWork == 0 ? 0 : workedMinutes / daysWithWork;

        return new AttendanceSummaryResponse(
                year, month, present, absent, half, leave, holiday, weeklyOff, wfh,
                lateMarks, earlyExits, workedMinutes, overtimeMinutes,
                DateTimeUtils.formatMinutes(workedMinutes),
                DateTimeUtils.formatMinutes(averageMinutes),
                percentage);
    }

    private Employee requireSelf(UserPrincipal principal) {
        if (principal.getEmployeeId() == null) {
            throw new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                    "This account is not linked to an employee record.");
        }
        return employeeRepository.findById(principal.getEmployeeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found"));
    }

    /**
     * Null means "me". Anything else is checked against the caller's scope
     * before a single row is read.
     */
    private Long resolveTarget(UserPrincipal principal, Long employeeId) {
        if (employeeId == null || employeeId.equals(principal.getEmployeeId())) {
            return requireSelf(principal).getId();
        }
        AccessScope scope = accessScopeResolver.resolve(principal, PERM_VIEW_ALL, PERM_VIEW_TEAM);
        accessScopeResolver.assertCanAccess(principal, scope, employeeId);
        return employeeId;
    }

    private Optional<String> holidayName(Employee employee, LocalDate date) {
        return calendarService.findHoliday(employee, date).stream().findFirst().map(Holiday::getName);
    }

    private AttendanceStatusResponse.ShiftSummary toShiftSummary(Shift shift) {
        return new AttendanceStatusResponse.ShiftSummary(
                shift.getId(), shift.getCode(), shift.getName(),
                shift.getStartTime(), shift.getEndTime(), shift.isNightShift(),
                shift.getGracePeriodMinutes(), shift.getStandardWorkMinutes());
    }

    private List<String> parseFlags(Attendance attendance) {
        if (attendance.getSuspicionReasons() == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(attendance.getSuspicionReasons(), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private AttendanceResponse toResponse(Attendance attendance) {
        List<AttendanceResponse.BreakSummary> breaks = breakRepository
                .findByAttendanceIdOrderByBreakStartTimeAsc(attendance.getId()).stream()
                .map(b -> new AttendanceResponse.BreakSummary(
                        b.getId(),
                        b.getBreakType().getName(),
                        b.getBreakStartTime(),
                        b.getBreakEndTime(),
                        b.getDurationMinutes(),
                        b.isExceededLimit(),
                        b.isAutoClosed()))
                .toList();

        Employee employee = attendance.getEmployee();

        return new AttendanceResponse(
                attendance.getId(),
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                attendance.getAttendanceDate(),
                attendance.getAttendanceDate().getDayOfWeek().name(),
                attendance.getPunchInTime(),
                attendance.getPunchOutTime(),
                attendance.getWorkingMinutes(),
                attendance.getBreakMinutes(),
                attendance.getOvertimeMinutes(),
                attendance.getLateMinutes(),
                attendance.getEarlyExitMinutes(),
                DateTimeUtils.formatMinutes(attendance.getWorkingMinutes()),
                attendance.getAttendanceStatus().name(),
                attendance.getWorkMode().name(),
                attendance.getSource().name(),
                attendance.getPunchInLocation(),
                attendance.getPunchOutLocation(),
                attendance.getPunchInDistanceMeters(),
                attendance.getPunchOutDistanceMeters(),
                attendance.getShift() != null ? attendance.getShift().getName() : null,
                attendance.isRegularized(),
                attendance.isSuspicious(),
                parseFlags(attendance),
                attendance.getRemarks(),
                breaks);
    }
}
