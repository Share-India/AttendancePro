package com.company.attendance.service.impl;

import com.company.attendance.dto.request.report.ReportFilter;
import com.company.attendance.dto.response.report.ReportColumn;
import com.company.attendance.dto.response.report.ReportResult;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendanceRegularization;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Exam;
import com.company.attendance.entity.ExamAttempt;
import com.company.attendance.entity.ExamParticipant;
import com.company.attendance.entity.ExamQuestion;
import com.company.attendance.entity.MeetingParticipant;
import com.company.attendance.entity.LeaveRequest;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.enums.AccessScope;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.ExamParticipantKind;
import com.company.attendance.enums.ReportType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceRegularizationRepository;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.ExamAnswerRepository;
import com.company.attendance.repository.ExamAttemptRepository;
import com.company.attendance.repository.ExamParticipantRepository;
import com.company.attendance.repository.ExamQuestionRepository;
import com.company.attendance.repository.LeaveRequestDayRepository;
import com.company.attendance.repository.LeaveRequestRepository;
import com.company.attendance.repository.MeetingParticipantRepository;
import com.company.attendance.security.AccessScopeResolver;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.CalendarService;
import com.company.attendance.service.ReportService;
import com.company.attendance.service.exam.ExamSets;
import com.company.attendance.util.DateTimeUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The report engine.
 *
 * <p>Twelve reports, one shape. Each builder returns typed columns and rows
 * keyed by them, which is what lets a single exporter produce CSV, Excel and
 * PDF for all of them and a single table render any of them. The alternative —
 * a DTO, an exporter and a table per report — makes the thirteenth report cost
 * as much as the first.
 *
 * <p>Scope is resolved once, before any report runs. Filters narrow what is
 * already permitted and never widen it, so a manager asking for a department
 * they do not manage gets an empty report rather than someone else's data.
 */
@Service
public class ReportServiceImpl implements ReportService {

    private static final String PERM_VIEW_ALL = "REPORT_VIEW_ALL";
    private static final String PERM_VIEW_TEAM = "REPORT_VIEW_TEAM";

    /**
     * A hard ceiling on an export. Past this the answer is a scheduled extract,
     * not a synchronous download — and a request that would run for minutes is
     * better refused with a clear message than left to time out.
     */
    private static final int MAX_EXPORT_ROWS = 50_000;

    /** Longest range a report may cover, to keep one query from scanning years. */
    private static final int MAX_RANGE_DAYS = 366;

    private final EmployeeRepository employeeRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveRequestDayRepository leaveRequestDayRepository;
    private final AttendanceRegularizationRepository regularizationRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final ExamParticipantRepository examParticipantRepository;
    private final ExamAttemptRepository examAttemptRepository;
    private final ExamAnswerRepository examAnswerRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final CalendarService calendarService;
    private final AccessScopeResolver accessScopeResolver;

    public ReportServiceImpl(EmployeeRepository employeeRepository,
                             AttendanceRepository attendanceRepository,
                             LeaveRequestRepository leaveRequestRepository,
                             LeaveRequestDayRepository leaveRequestDayRepository,
                             AttendanceRegularizationRepository regularizationRepository,
                             MeetingParticipantRepository meetingParticipantRepository,
                             ExamParticipantRepository examParticipantRepository,
                             ExamAttemptRepository examAttemptRepository,
                             ExamAnswerRepository examAnswerRepository,
                             ExamQuestionRepository examQuestionRepository,
                             CalendarService calendarService,
                             AccessScopeResolver accessScopeResolver) {
        this.employeeRepository = employeeRepository;
        this.attendanceRepository = attendanceRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.leaveRequestDayRepository = leaveRequestDayRepository;
        this.regularizationRepository = regularizationRepository;
        this.meetingParticipantRepository = meetingParticipantRepository;
        this.examParticipantRepository = examParticipantRepository;
        this.examAttemptRepository = examAttemptRepository;
        this.examAnswerRepository = examAnswerRepository;
        this.examQuestionRepository = examQuestionRepository;
        this.calendarService = calendarService;
        this.accessScopeResolver = accessScopeResolver;
    }

    // -----------------------------------------------------------------
    // Entry points
    // -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ReportResult generate(UserPrincipal principal, ReportType type,
                                 ReportFilter filter, Pageable pageable) {
        Built built = build(principal, type, filter);

        int from = (int) Math.min(pageable.getOffset(), built.rows().size());
        int to = Math.min(from + pageable.getPageSize(), built.rows().size());

        return assemble(type, built, built.rows().subList(from, to),
                pageable.getPageNumber(), pageable.getPageSize());
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResult generateAll(UserPrincipal principal, ReportType type, ReportFilter filter) {
        Built built = build(principal, type, filter);

        if (built.rows().size() > MAX_EXPORT_ROWS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That range produces " + built.rows().size() + " rows, over the "
                            + MAX_EXPORT_ROWS + " limit for a direct download. "
                            + "Narrow the dates or filter by department.");
        }
        // Deliberately not paged: an export that quietly contained only the
        // first twenty rows would be worse than one that failed.
        return assemble(type, built, built.rows(), 0, built.rows().size());
    }

    // -----------------------------------------------------------------
    // Building
    // -----------------------------------------------------------------

    private record Built(AccessScope scope,
                         LocalDate from,
                         LocalDate to,
                         List<ReportColumn> columns,
                         List<Map<String, Object>> rows,
                         Map<String, Object> totals) {
    }

    private Built build(UserPrincipal principal, ReportType type, ReportFilter filter) {
        AccessScope scope = accessScopeResolver.resolve(principal, PERM_VIEW_ALL, PERM_VIEW_TEAM);
        if (scope == AccessScope.SELF) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "Reports are for managers and HR. Your own history is under My Attendance.");
        }

        LocalDate to = filter.toDate() != null ? filter.toDate() : LocalDate.now();
        LocalDate from = filter.fromDate() != null ? filter.fromDate() : to.withDayOfMonth(1);

        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "The start of the range comes after its end.");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "A report covers at most " + MAX_RANGE_DAYS + " days.");
        }
        if (type.requiresEmployee()
                && (filter.employeeIds() == null || filter.employeeIds().isEmpty())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Choose an employee for this report.");
        }

        List<Employee> population = population(principal, scope, filter);

        return switch (type) {
            case DAILY_ATTENDANCE, EMPLOYEE_ATTENDANCE -> dailyAttendance(scope, population, from, to);
            case MONTHLY_ATTENDANCE -> monthlySummary(scope, population, from, to);
            case LATE_ARRIVALS -> exceptions(scope, population, from, to, true);
            case EARLY_EXITS -> exceptions(scope, population, from, to, false);
            case ABSENTEES -> absentees(scope, population, from, to);
            case LEAVES -> leaveRegister(scope, population, from, to, filter);
            case OVERTIME -> overtime(scope, population, from, to);
            case WORKING_HOURS -> workingHours(scope, population, from, to);
            case DEPARTMENT_ATTENDANCE -> departmentAttendance(scope, population, from, to);
            case LOCATION_ATTENDANCE -> locationAttendance(scope, population, from, to);
            case REGULARIZATIONS -> regularizations(scope, population, from, to, filter);
            case MEETING_ATTENDANCE -> meetingAttendance(scope, population, from, to);
            case MEETING_PARTICIPATION -> meetingParticipation(scope, population, from, to);
            case EXAM_RESULTS -> examResults(scope, population, from, to);
            case EXAM_PARTICIPATION -> examParticipation(scope, population, from, to);
            case EXAM_QUESTION_ANALYSIS -> examQuestionAnalysis(scope, population, from, to);
        };
    }

    private List<Employee> population(UserPrincipal principal, AccessScope scope, ReportFilter filter) {
        Optional<Set<Long>> visible = accessScopeResolver.visibleEmployeeIds(principal, scope);

        List<Employee> employees = visible
                .map(ids -> ids.isEmpty() ? List.<Employee>of() : employeeRepository.findAllById(ids))
                .orElseGet(employeeRepository::findAll);

        return employees.stream()
                .filter(employee -> filter.employeeIds() == null || filter.employeeIds().isEmpty()
                        || filter.employeeIds().contains(employee.getId()))
                .filter(employee -> filter.departmentId() == null
                        || (employee.getDepartment() != null
                        && filter.departmentId().equals(employee.getDepartment().getId())))
                .filter(employee -> filter.officeId() == null
                        || (employee.getOffice() != null
                        && filter.officeId().equals(employee.getOffice().getId())))
                .sorted(Comparator.comparing(Employee::getEmployeeCode))
                .toList();
    }

    // -----------------------------------------------------------------
    // The reports
    // -----------------------------------------------------------------

    private Built dailyAttendance(AccessScope scope, List<Employee> population,
                                  LocalDate from, LocalDate to) {

        List<ReportColumn> columns = List.of(
                ReportColumn.date("date", "Date"),
                ReportColumn.text("employeeCode", "Code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("department", "Department"),
                ReportColumn.time("punchIn", "In"),
                ReportColumn.time("punchOut", "Out"),
                ReportColumn.duration("worked", "Worked"),
                ReportColumn.duration("late", "Late by"),
                ReportColumn.duration("earlyExit", "Left early by"),
                ReportColumn.text("workMode", "Mode"),
                ReportColumn.status("status", "Status"));

        List<Map<String, Object>> rows = new ArrayList<>();
        long workedTotal = 0;

        for (Attendance attendance : attendanceInRange(population, from, to)) {
            Employee employee = attendance.getEmployee();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", attendance.getAttendanceDate());
            row.put("employeeCode", employee.getEmployeeCode());
            row.put("employeeName", employee.getFullName());
            row.put("department", departmentName(employee));
            row.put("punchIn", attendance.getPunchInTime());
            row.put("punchOut", attendance.getPunchOutTime());
            row.put("worked", attendance.getWorkingMinutes());
            row.put("late", attendance.getLateMinutes());
            row.put("earlyExit", attendance.getEarlyExitMinutes());
            row.put("workMode", attendance.getWorkMode() == null ? null : attendance.getWorkMode().name());
            row.put("status", attendance.getAttendanceStatus() == null
                    ? null : attendance.getAttendanceStatus().name());
            rows.add(row);
            workedTotal += attendance.getWorkingMinutes();
        }

        rows.sort(Comparator.comparing((Map<String, Object> row) -> (LocalDate) row.get("date"))
                .reversed()
                .thenComparing(row -> String.valueOf(row.get("employeeCode"))));

        return new Built(scope, from, to, columns, rows,
                Map.of("rows", rows.size(), "worked", workedTotal));
    }

    private Built monthlySummary(AccessScope scope, List<Employee> population,
                                 LocalDate from, LocalDate to) {

        List<ReportColumn> columns = List.of(
                ReportColumn.text("employeeCode", "Code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("department", "Department"),
                ReportColumn.number("present", "Present"),
                ReportColumn.number("wfh", "From home"),
                ReportColumn.number("leave", "On leave"),
                ReportColumn.number("absent", "Absent"),
                ReportColumn.number("lateMarks", "Late marks"),
                ReportColumn.duration("worked", "Total worked"),
                ReportColumn.duration("overtime", "Overtime"));

        Map<Long, List<Attendance>> byEmployee = attendanceInRange(population, from, to).stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        Map<Long, Long> leaveDays = leaveDaysInRange(population, from, to);
        Map<Long, Integer> expected = expectedWorkingDays(population, from, to);

        List<Map<String, Object>> rows = new ArrayList<>();

        for (Employee employee : population) {
            List<Attendance> days = byEmployee.getOrDefault(employee.getId(), List.of());

            long present = days.stream()
                    .filter(a -> a.getPunchInTime() != null)
                    .filter(a -> a.getWorkMode() == null || !"WFH".equals(a.getWorkMode().name()))
                    .count();
            long wfh = days.stream()
                    .filter(a -> a.getPunchInTime() != null)
                    .filter(a -> a.getWorkMode() != null && "WFH".equals(a.getWorkMode().name()))
                    .count();
            long leave = leaveDays.getOrDefault(employee.getId(), 0L);
            long lateMarks = days.stream().filter(a -> a.getLateMinutes() > 0).count();
            long worked = days.stream().mapToLong(Attendance::getWorkingMinutes).sum();
            long overtimeMinutes = days.stream().mapToLong(Attendance::getOvertimeMinutes).sum();

            // Absence is what is left after everything with an explanation is
            // taken out — never simply "days without a punch".
            long absent = Math.max(0,
                    expected.getOrDefault(employee.getId(), 0) - present - wfh - leave);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employeeCode", employee.getEmployeeCode());
            row.put("employeeName", employee.getFullName());
            row.put("department", departmentName(employee));
            row.put("present", present);
            row.put("wfh", wfh);
            row.put("leave", leave);
            row.put("absent", absent);
            row.put("lateMarks", lateMarks);
            row.put("worked", worked);
            row.put("overtime", overtimeMinutes);
            rows.add(row);
        }

        return new Built(scope, from, to, columns, rows, Map.of("rows", rows.size()));
    }

    private Built exceptions(AccessScope scope, List<Employee> population,
                             LocalDate from, LocalDate to, boolean late) {

        List<ReportColumn> columns = List.of(
                ReportColumn.date("date", "Date"),
                ReportColumn.text("employeeCode", "Code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("department", "Department"),
                ReportColumn.time("punchIn", "In"),
                ReportColumn.time("punchOut", "Out"),
                ReportColumn.duration(late ? "late" : "earlyExit", late ? "Late by" : "Left early by"),
                ReportColumn.duration("worked", "Worked"));

        List<Map<String, Object>> rows = new ArrayList<>();
        long totalMinutes = 0;

        for (Attendance attendance : attendanceInRange(population, from, to)) {
            int minutes = late ? attendance.getLateMinutes() : attendance.getEarlyExitMinutes();
            if (minutes <= 0) {
                continue;
            }
            Employee employee = attendance.getEmployee();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", attendance.getAttendanceDate());
            row.put("employeeCode", employee.getEmployeeCode());
            row.put("employeeName", employee.getFullName());
            row.put("department", departmentName(employee));
            row.put("punchIn", attendance.getPunchInTime());
            row.put("punchOut", attendance.getPunchOutTime());
            row.put(late ? "late" : "earlyExit", minutes);
            row.put("worked", attendance.getWorkingMinutes());
            rows.add(row);
            totalMinutes += minutes;
        }

        rows.sort(Comparator.comparing((Map<String, Object> row) -> (LocalDate) row.get("date")).reversed());

        return new Built(scope, from, to, columns, rows,
                Map.of("rows", rows.size(), late ? "late" : "earlyExit", totalMinutes));
    }

    private Built absentees(AccessScope scope, List<Employee> population,
                            LocalDate from, LocalDate to) {

        List<ReportColumn> columns = List.of(
                ReportColumn.date("date", "Date"),
                ReportColumn.text("employeeCode", "Code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("department", "Department"),
                ReportColumn.text("dayOfWeek", "Day"));

        Set<Long> ids = idsOf(population);
        Map<LocalDate, Set<Long>> punched = ids.isEmpty() ? Map.of()
                : attendanceRepository.findForEmployeesBetween(ids, from, to).stream()
                        .filter(a -> a.getPunchInTime() != null)
                        .collect(Collectors.groupingBy(Attendance::getAttendanceDate,
                                Collectors.mapping(a -> a.getEmployee().getId(), Collectors.toSet())));

        Map<LocalDate, Set<Long>> onLeave = leaveDayIndex(population, from, to);

        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            Map<Long, DayType> dayTypes = calendarService.resolveDayTypes(population, day);
            Set<Long> here = punched.getOrDefault(day, Set.of());
            Set<Long> away = onLeave.getOrDefault(day, Set.of());

            for (Employee employee : population) {
                if (!wasEmployedOn(employee, day)) {
                    continue;
                }
                if (dayTypes.getOrDefault(employee.getId(), DayType.WORKING) != DayType.WORKING) {
                    continue;
                }
                if (here.contains(employee.getId()) || away.contains(employee.getId())) {
                    continue;
                }
                // Today counts only once the shift has finished; before that the
                // person is simply not in yet, and listing them as an absentee
                // would be an accusation on a clock reading.
                if (day.equals(today) && !shiftFinished(employee, day)) {
                    continue;
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", day);
                row.put("employeeCode", employee.getEmployeeCode());
                row.put("employeeName", employee.getFullName());
                row.put("department", departmentName(employee));
                row.put("dayOfWeek", day.getDayOfWeek().name());
                rows.add(row);
            }
        }

        rows.sort(Comparator.comparing((Map<String, Object> row) -> (LocalDate) row.get("date")).reversed());
        return new Built(scope, from, to, columns, rows, Map.of("rows", rows.size()));
    }

    private Built leaveRegister(AccessScope scope, List<Employee> population,
                                LocalDate from, LocalDate to, ReportFilter filter) {

        List<ReportColumn> columns = List.of(
                ReportColumn.text("requestNumber", "Reference"),
                ReportColumn.text("employeeCode", "Code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("leaveType", "Type"),
                ReportColumn.date("fromDate", "From"),
                ReportColumn.date("toDate", "To"),
                ReportColumn.number("days", "Days"),
                ReportColumn.status("status", "Status"),
                ReportColumn.text("reason", "Reason"));

        Set<Long> ids = idsOf(population);
        List<LeaveRequest> requests = ids.isEmpty() ? List.of()
                : leaveRequestRepository.findForReport(ids, from, to);

        List<Map<String, Object>> rows = new ArrayList<>();
        double totalDays = 0;

        for (LeaveRequest request : requests) {
            if (filter.status() != null && !filter.status().isBlank()
                    && !filter.status().equalsIgnoreCase(request.getStatus().name())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requestNumber", request.getRequestNumber());
            row.put("employeeCode", request.getEmployee().getEmployeeCode());
            row.put("employeeName", request.getEmployee().getFullName());
            row.put("leaveType", request.getLeaveType().getName());
            row.put("fromDate", request.getFromDate());
            row.put("toDate", request.getToDate());
            row.put("days", request.getTotalDays());
            row.put("status", request.getStatus().name());
            row.put("reason", request.getReason());
            rows.add(row);
            totalDays += request.getTotalDays() == null ? 0 : request.getTotalDays().doubleValue();
        }

        return new Built(scope, from, to, columns, rows,
                Map.of("rows", rows.size(), "days", totalDays));
    }

    private Built overtime(AccessScope scope, List<Employee> population,
                           LocalDate from, LocalDate to) {

        List<ReportColumn> columns = List.of(
                ReportColumn.date("date", "Date"),
                ReportColumn.text("employeeCode", "Code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("department", "Department"),
                ReportColumn.duration("worked", "Worked"),
                ReportColumn.duration("overtime", "Overtime"));

        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;

        for (Attendance attendance : attendanceInRange(population, from, to)) {
            if (attendance.getOvertimeMinutes() <= 0) {
                continue;
            }
            Employee employee = attendance.getEmployee();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", attendance.getAttendanceDate());
            row.put("employeeCode", employee.getEmployeeCode());
            row.put("employeeName", employee.getFullName());
            row.put("department", departmentName(employee));
            row.put("worked", attendance.getWorkingMinutes());
            row.put("overtime", attendance.getOvertimeMinutes());
            rows.add(row);
            total += attendance.getOvertimeMinutes();
        }

        rows.sort(Comparator.comparing((Map<String, Object> row) -> (LocalDate) row.get("date")).reversed());
        return new Built(scope, from, to, columns, rows,
                Map.of("rows", rows.size(), "overtime", total));
    }

    private Built workingHours(AccessScope scope, List<Employee> population,
                               LocalDate from, LocalDate to) {

        List<ReportColumn> columns = List.of(
                ReportColumn.text("employeeCode", "Code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("department", "Department"),
                ReportColumn.number("daysWorked", "Days worked"),
                ReportColumn.duration("worked", "Total worked"),
                ReportColumn.duration("average", "Average per day"),
                ReportColumn.duration("breaks", "Breaks"),
                ReportColumn.duration("overtime", "Overtime"));

        Map<Long, List<Attendance>> byEmployee = attendanceInRange(population, from, to).stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));

        List<Map<String, Object>> rows = new ArrayList<>();
        long grandTotal = 0;

        for (Employee employee : population) {
            List<Attendance> days = byEmployee.getOrDefault(employee.getId(), List.of());
            long worked = days.stream().mapToLong(Attendance::getWorkingMinutes).sum();
            long daysWorked = days.stream().filter(a -> a.getPunchInTime() != null).count();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employeeCode", employee.getEmployeeCode());
            row.put("employeeName", employee.getFullName());
            row.put("department", departmentName(employee));
            row.put("daysWorked", daysWorked);
            row.put("worked", worked);
            // Averaged over days actually worked, not over the range: dividing
            // by calendar days would make a week of leave look like short days.
            row.put("average", daysWorked == 0 ? 0 : worked / daysWorked);
            row.put("breaks", days.stream().mapToLong(Attendance::getBreakMinutes).sum());
            row.put("overtime", days.stream().mapToLong(Attendance::getOvertimeMinutes).sum());
            rows.add(row);
            grandTotal += worked;
        }

        return new Built(scope, from, to, columns, rows,
                Map.of("rows", rows.size(), "worked", grandTotal));
    }

    private Built departmentAttendance(AccessScope scope, List<Employee> population,
                                       LocalDate from, LocalDate to) {

        List<ReportColumn> columns = List.of(
                ReportColumn.text("department", "Department"),
                ReportColumn.number("headcount", "People"),
                ReportColumn.number("present", "Present days"),
                ReportColumn.number("wfh", "From home"),
                ReportColumn.number("leave", "Leave days"),
                ReportColumn.duration("worked", "Total worked"),
                ReportColumn.number("lateMarks", "Late marks"));

        Map<Long, List<Attendance>> byEmployee = attendanceInRange(population, from, to).stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));
        Map<Long, Long> leaveDays = leaveDaysInRange(population, from, to);

        Map<String, List<Employee>> grouped = population.stream()
                .collect(Collectors.groupingBy(this::departmentName, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> rows = new ArrayList<>();

        for (Map.Entry<String, List<Employee>> entry : grouped.entrySet()) {
            List<Attendance> days = entry.getValue().stream()
                    .flatMap(employee -> byEmployee.getOrDefault(employee.getId(), List.<Attendance>of()).stream())
                    .toList();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("department", entry.getKey());
            row.put("headcount", entry.getValue().size());
            row.put("present", days.stream()
                    .filter(a -> a.getPunchInTime() != null)
                    .filter(a -> a.getWorkMode() == null || !"WFH".equals(a.getWorkMode().name()))
                    .count());
            row.put("wfh", days.stream()
                    .filter(a -> a.getWorkMode() != null && "WFH".equals(a.getWorkMode().name()))
                    .count());
            row.put("leave", entry.getValue().stream()
                    .mapToLong(employee -> leaveDays.getOrDefault(employee.getId(), 0L)).sum());
            row.put("worked", days.stream().mapToLong(Attendance::getWorkingMinutes).sum());
            row.put("lateMarks", days.stream().filter(a -> a.getLateMinutes() > 0).count());
            rows.add(row);
        }

        rows.sort(Comparator.comparing(row -> String.valueOf(row.get("department")),
                String.CASE_INSENSITIVE_ORDER));
        return new Built(scope, from, to, columns, rows, Map.of("rows", rows.size()));
    }

    private Built locationAttendance(AccessScope scope, List<Employee> population,
                                     LocalDate from, LocalDate to) {

        List<ReportColumn> columns = List.of(
                ReportColumn.date("date", "Date"),
                ReportColumn.text("employeeCode", "Code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("workMode", "Mode"),
                ReportColumn.text("punchInLocation", "Punched in at"),
                ReportColumn.number("distance", "Distance (m)"),
                ReportColumn.text("source", "Source"),
                ReportColumn.text("regularized", "Corrected"));

        List<Map<String, Object>> rows = new ArrayList<>();

        for (Attendance attendance : attendanceInRange(population, from, to)) {
            if (attendance.getPunchInTime() == null) {
                continue;
            }
            Employee employee = attendance.getEmployee();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", attendance.getAttendanceDate());
            row.put("employeeCode", employee.getEmployeeCode());
            row.put("employeeName", employee.getFullName());
            row.put("workMode", attendance.getWorkMode() == null ? null : attendance.getWorkMode().name());
            row.put("punchInLocation", attendance.getPunchInLocation());
            row.put("distance", attendance.getPunchInDistanceMeters());
            row.put("source", attendance.getSource() == null ? null : attendance.getSource().name());
            // Worth its own column: a corrected day did not come from a device
            // reading at all, and reading it as location evidence would mislead.
            row.put("regularized", attendance.isRegularized() ? "Yes" : "No");
            rows.add(row);
        }

        rows.sort(Comparator.comparing((Map<String, Object> row) -> (LocalDate) row.get("date")).reversed());
        return new Built(scope, from, to, columns, rows, Map.of("rows", rows.size()));
    }

    private Built regularizations(AccessScope scope, List<Employee> population,
                                  LocalDate from, LocalDate to, ReportFilter filter) {

        List<ReportColumn> columns = List.of(
                ReportColumn.text("requestNumber", "Reference"),
                ReportColumn.date("attendanceDate", "For date"),
                ReportColumn.text("employeeCode", "Code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("reasonType", "Reason"),
                ReportColumn.status("status", "Status"),
                ReportColumn.text("approver", "With"),
                ReportColumn.text("reason", "Explanation"));

        Set<Long> ids = idsOf(population);
        List<AttendanceRegularization> requests = ids.isEmpty() ? List.of()
                : regularizationRepository.findForReport(ids, from, to);

        List<Map<String, Object>> rows = new ArrayList<>();

        for (AttendanceRegularization request : requests) {
            if (filter.status() != null && !filter.status().isBlank()
                    && !filter.status().equalsIgnoreCase(request.getStatus().name())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requestNumber", request.getRequestNumber());
            row.put("attendanceDate", request.getAttendanceDate());
            row.put("employeeCode", request.getEmployee().getEmployeeCode());
            row.put("employeeName", request.getEmployee().getFullName());
            row.put("reasonType", request.getReasonType() == null ? null : request.getReasonType().name());
            row.put("status", request.getStatus().name());
            row.put("approver", request.getCurrentApproverEmployeeId() == null ? "—" : "Assigned");
            row.put("reason", request.getReason());
            rows.add(row);
        }

        return new Built(scope, from, to, columns, rows, Map.of("rows", rows.size()));
    }

    // -----------------------------------------------------------------
    // Shared loading
    // -----------------------------------------------------------------

    private List<Attendance> attendanceInRange(List<Employee> population, LocalDate from, LocalDate to) {
        Set<Long> ids = idsOf(population);
        return ids.isEmpty() ? List.of()
                : attendanceRepository.findForEmployeesBetween(ids, from, to);
    }

    private Map<Long, Long> leaveDaysInRange(List<Employee> population, LocalDate from, LocalDate to) {
        Set<Long> ids = idsOf(population);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return leaveRequestDayRepository.findApprovedForEmployeesBetween(ids, from, to).stream()
                .collect(Collectors.groupingBy(LeaveRequestDay::getEmployeeId, Collectors.counting()));
    }

    private Map<LocalDate, Set<Long>> leaveDayIndex(List<Employee> population, LocalDate from, LocalDate to) {
        Set<Long> ids = idsOf(population);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return leaveRequestDayRepository.findApprovedForEmployeesBetween(ids, from, to).stream()
                .collect(Collectors.groupingBy(LeaveRequestDay::getLeaveDate,
                        Collectors.mapping(LeaveRequestDay::getEmployeeId, Collectors.toSet())));
    }

    /** Working days each person was actually employed for, across the range. */
    private Map<Long, Integer> expectedWorkingDays(List<Employee> population, LocalDate from, LocalDate to) {
        Map<Long, Integer> expected = new HashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            Map<Long, DayType> types = calendarService.resolveDayTypes(population, day);
            for (Employee employee : population) {
                if (!wasEmployedOn(employee, day)) {
                    continue;
                }
                if (types.getOrDefault(employee.getId(), DayType.WORKING) == DayType.WORKING) {
                    expected.merge(employee.getId(), 1, Integer::sum);
                }
            }
        }
        return expected;
    }

    private boolean wasEmployedOn(Employee employee, LocalDate date) {
        if (employee.getJoiningDate() != null && date.isBefore(employee.getJoiningDate())) {
            return false;
        }
        return employee.getExitDate() == null || !date.isAfter(employee.getExitDate());
    }

    /** Whether the day is over for this person, in their own office's timezone. */
    private boolean shiftFinished(Employee employee, LocalDate date) {
        ZoneId zone = DateTimeUtils.zoneOf(employee);
        return date.isBefore(DateTimeUtils.localDate(Instant.now(), zone));
    }

    private Set<Long> idsOf(List<Employee> population) {
        return population.stream().map(Employee::getId).collect(Collectors.toSet());
    }

    private String departmentName(Employee employee) {
        return employee.getDepartment() == null ? "Unassigned" : employee.getDepartment().getName();
    }


    // -----------------------------------------------------------------
    // Meetings
    //
    // Both reports measure one thing: whether a person opened the join link.
    // The column labels say so, because "Attended" would be a claim this
    // system cannot support. Somebody can dial in from a calendar invite and
    // never touch our link, and somebody can open the link and never speak.
    // -----------------------------------------------------------------

    private Built meetingAttendance(AccessScope scope, List<Employee> population,
                                    LocalDate from, LocalDate to) {

        List<ReportColumn> columns = List.of(
                ReportColumn.date("meetingDate", "Date"),
                ReportColumn.text("title", "Meeting"),
                ReportColumn.text("employeeCode", "Code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("department", "Department"),
                ReportColumn.status("status", "Outcome"),
                ReportColumn.time("joinedAt", "Link opened"),
                ReportColumn.duration("minutes", "Approx. minutes"),
                ReportColumn.text("excuseReason", "Reason"));

        List<Map<String, Object>> rows = new ArrayList<>();

        for (MeetingParticipant participant : meetingRows(population, from, to)) {
            Employee employee = participant.getEmployee();
            ZoneId zone = DateTimeUtils.zoneOf(employee);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("meetingDate",
                    DateTimeUtils.localDate(participant.getMeeting().getScheduledStartAt(), zone));
            row.put("title", participant.getMeeting().getTitle());
            row.put("employeeCode", employee.getEmployeeCode());
            row.put("employeeName", employee.getFullName());
            row.put("department",
                    employee.getDepartment() == null ? null : employee.getDepartment().getName());
            row.put("status", participant.getStatus().name());
            row.put("joinedAt", participant.getFirstJoinedAt());
            row.put("minutes", participant.getAttendedMinutesEstimate());
            row.put("excuseReason", participant.getExcuseReason());
            rows.add(row);
        }

        return new Built(scope, from, to, columns, rows, Map.of("rows", rows.size()));
    }

    private Built meetingParticipation(AccessScope scope, List<Employee> population,
                                       LocalDate from, LocalDate to) {

        List<ReportColumn> columns = List.of(
                ReportColumn.text("employeeCode", "Code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("department", "Department"),
                ReportColumn.number("invited", "Invited"),
                ReportColumn.number("joined", "Opened link"),
                ReportColumn.number("late", "Late"),
                ReportColumn.number("excused", "Excused"),
                ReportColumn.number("missed", "No link opened"),
                ReportColumn.number("rate", "Join rate %"));

        Map<Long, int[]> tally = new LinkedHashMap<>();
        Map<Long, Employee> people = new LinkedHashMap<>();

        for (MeetingParticipant participant : meetingRows(population, from, to)) {
            Employee employee = participant.getEmployee();
            people.putIfAbsent(employee.getId(), employee);
            // invited, joined, late, excused, missed
            int[] counts = tally.computeIfAbsent(employee.getId(), id -> new int[5]);
            counts[0]++;
            switch (participant.getStatus()) {
                case JOINED -> counts[1]++;
                case JOINED_LATE -> {
                    counts[1]++;
                    counts[2]++;
                }
                case EXCUSED -> counts[3]++;
                case NOT_JOINED, INVITED -> counts[4]++;
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, int[]> entry : tally.entrySet()) {
            Employee employee = people.get(entry.getKey());
            int[] counts = entry.getValue();

            // Excused meetings are out of the denominator. Counting somebody
            // as a poor attender for being on sanctioned leave is exactly the
            // error that makes people stop trusting a report.
            int expected = counts[0] - counts[3];
            int rate = expected == 0 ? 100 : Math.round(counts[1] * 100f / expected);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employeeCode", employee.getEmployeeCode());
            row.put("employeeName", employee.getFullName());
            row.put("department",
                    employee.getDepartment() == null ? null : employee.getDepartment().getName());
            row.put("invited", counts[0]);
            row.put("joined", counts[1]);
            row.put("late", counts[2]);
            row.put("excused", counts[3]);
            row.put("missed", counts[4]);
            row.put("rate", rate);
            rows.add(row);
        }

        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("employeeCode"))));
        return new Built(scope, from, to, columns, rows, Map.of("rows", rows.size()));
    }

    private List<MeetingParticipant> meetingRows(List<Employee> population,
                                                 LocalDate from, LocalDate to) {
        Set<Long> ids = idsOf(population);
        if (ids.isEmpty()) {
            return List.of();
        }
        ZoneId zone = DateTimeUtils.zoneOf(population.get(0));

        return meetingParticipantRepository.findForReport(
                false, ids,
                from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant());
    }

    // -----------------------------------------------------------------
    // Exams
    // -----------------------------------------------------------------

    /**
     * One row per person per exam.
     *
     * <p>Testers are kept in and labelled rather than filtered out. This report
     * answers "what did this person score", and silently omitting the people who
     * wrote the paper would make the row count disagree with the roster on
     * screen. The rates they would distort live in EXAM_PARTICIPATION and in the
     * analysis screen, and both exclude them.
     */
    private Built examResults(AccessScope scope, List<Employee> population,
                              LocalDate from, LocalDate to) {

        List<ReportColumn> columns = List.of(
                ReportColumn.date("examDate", "Opens"),
                ReportColumn.text("examCode", "Code"),
                ReportColumn.text("examTitle", "Exam"),
                ReportColumn.text("employeeCode", "Emp code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("department", "Department"),
                ReportColumn.text("setCode", "Set"),
                ReportColumn.text("kind", "Counted as"),
                ReportColumn.status("status", "Outcome"),
                ReportColumn.number("score", "Score"),
                ReportColumn.number("maxScore", "Out of"),
                ReportColumn.text("result", "Pass/fail"),
                ReportColumn.number("minutesTaken", "Minutes taken"),
                ReportColumn.text("submission", "Handed in by"));

        List<ExamParticipant> roster = examRows(population, from, to);
        Map<Long, ExamAttempt> attempts = examAttemptsFor(roster);
        List<Map<String, Object>> rows = new ArrayList<>();

        for (ExamParticipant participant : roster) {
            Employee employee = participant.getEmployee();
            ZoneId zone = DateTimeUtils.zoneOf(employee);
            ExamAttempt attempt = attempts.get(participant.getId());
            boolean done = attempt != null && attempt.isSubmitted();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("examDate",
                    DateTimeUtils.localDate(participant.getExam().getWindowStart(), zone));
            row.put("examCode", participant.getExam().getExamCode());
            row.put("examTitle", participant.getExam().getTitle());
            row.put("employeeCode", employee.getEmployeeCode());
            row.put("employeeName", employee.getFullName());
            row.put("department",
                    employee.getDepartment() == null ? null : employee.getDepartment().getName());
            row.put("setCode", participant.getAssignedSet().getSetCode());
            row.put("kind", participant.getParticipantKind() == ExamParticipantKind.TESTER
                    ? "Tester (out of the rates)" : "Candidate");
            row.put("status", participant.getStatus().name());
            row.put("score", done ? attempt.getScore() : null);
            row.put("maxScore", done ? attempt.getMaxScore() : null);
            // Blank rather than "Fail" for somebody who never sat it: not
            // turning up is a participation problem, not a knowledge one.
            row.put("result", done
                    ? (Boolean.TRUE.equals(attempt.getPassed()) ? "Pass" : "Fail") : null);
            row.put("minutesTaken", done
                    ? (int) Duration.between(attempt.getStartedAt(),
                            attempt.getSubmittedAt()).toMinutes()
                    : null);
            row.put("submission", done ? attempt.getSubmissionSource().name() : null);
            rows.add(row);
        }

        return new Built(scope, from, to, columns, rows, Map.of("rows", rows.size()));
    }

    /**
     * One row per person over the range: how many exams they were given and
     * what became of them.
     *
     * <p>The one anybody actually reads, because it answers "who has stopped
     * sitting these" without going exam by exam.
     */
    private Built examParticipation(AccessScope scope, List<Employee> population,
                                    LocalDate from, LocalDate to) {

        List<ReportColumn> columns = List.of(
                ReportColumn.text("employeeCode", "Code"),
                ReportColumn.text("employeeName", "Employee"),
                ReportColumn.text("department", "Department"),
                ReportColumn.number("assigned", "Assigned"),
                ReportColumn.number("sat", "Sat"),
                ReportColumn.number("passed", "Passed"),
                ReportColumn.number("failed", "Failed"),
                ReportColumn.number("missed", "Did not sit"),
                ReportColumn.number("sitRate", "Sat %"),
                ReportColumn.number("passRate", "Passed %"),
                ReportColumn.number("averageScore", "Average score"));

        List<ExamParticipant> roster = examRows(population, from, to);
        Map<Long, ExamAttempt> attempts = examAttemptsFor(roster);

        // assigned, sat, passed, failed, missed, scoreTotal
        Map<Long, int[]> tally = new LinkedHashMap<>();
        Map<Long, Employee> people = new LinkedHashMap<>();

        for (ExamParticipant participant : roster) {
            // Testers are left out of this one: they sat the paper having read
            // the answers, so counting them inflates every rate on the page.
            if (participant.getParticipantKind() == ExamParticipantKind.TESTER) {
                continue;
            }
            Employee employee = participant.getEmployee();
            people.putIfAbsent(employee.getId(), employee);
            int[] counts = tally.computeIfAbsent(employee.getId(), id -> new int[6]);

            counts[0]++;
            ExamAttempt attempt = attempts.get(participant.getId());
            if (attempt != null && attempt.isSubmitted()) {
                counts[1]++;
                if (Boolean.TRUE.equals(attempt.getPassed())) {
                    counts[2]++;
                } else {
                    counts[3]++;
                }
                counts[5] += attempt.getScore() == null ? 0 : attempt.getScore();
            } else {
                counts[4]++;
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        people.forEach((employeeId, employee) -> {
            int[] counts = tally.get(employeeId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employeeCode", employee.getEmployeeCode());
            row.put("employeeName", employee.getFullName());
            row.put("department",
                    employee.getDepartment() == null ? null : employee.getDepartment().getName());
            row.put("assigned", counts[0]);
            row.put("sat", counts[1]);
            row.put("passed", counts[2]);
            row.put("failed", counts[3]);
            row.put("missed", counts[4]);
            row.put("sitRate", counts[0] == 0 ? 0 : Math.round(counts[1] * 100.0 / counts[0]));
            // Out of what they sat, not what they were given: somebody who
            // missed two and passed the third knows the material, and has a
            // different problem from somebody who sat three and failed them all.
            row.put("passRate", counts[1] == 0 ? null : Math.round(counts[2] * 100.0 / counts[1]));
            row.put("averageScore",
                    counts[1] == 0 ? null : Math.round((double) counts[5] / counts[1]));
            rows.add(row);
        });

        rows.sort(Comparator.comparing(row -> String.valueOf(row.get("employeeCode"))));
        return new Built(scope, from, to, columns, rows, Map.of("rows", rows.size()));
    }

    /**
     * One row per question: how many were asked it, how many got it right, and
     * the wrong answer most of them gave.
     *
     * <p>The report that matters for a study exam. "31 of 40 people got the
     * reset-benefit question wrong" names the next training session, which no
     * individual score ever does — so it is sorted worst first.
     *
     * <p>The figures are not narrowed to the caller's subtree, deliberately:
     * they describe the paper rather than people, and a manager's slice of four
     * answers would read as evidence while being noise. The population still
     * decides <em>which exams</em> appear.
     */
    private Built examQuestionAnalysis(AccessScope scope, List<Employee> population,
                                       LocalDate from, LocalDate to) {

        List<ReportColumn> columns = List.of(
                ReportColumn.text("examCode", "Exam"),
                ReportColumn.text("setCode", "Set"),
                ReportColumn.number("questionNumber", "Q"),
                ReportColumn.text("topic", "Product"),
                ReportColumn.text("question", "Question"),
                ReportColumn.number("asked", "Asked"),
                ReportColumn.number("answered", "Answered"),
                ReportColumn.number("skipped", "Skipped"),
                ReportColumn.number("correct", "Correct"),
                ReportColumn.number("correctRate", "Correct %"),
                ReportColumn.text("commonestWrong", "Commonest wrong answer"));

        List<Map<String, Object>> rows = new ArrayList<>();

        for (Exam exam : examsIn(population, from, to)) {
            Map<Long, long[]> stats = new LinkedHashMap<>();
            for (Object[] stat : examAnswerRepository.questionStats(exam.getId(), false)) {
                stats.put(((Number) stat[0]).longValue(),
                        new long[] {((Number) stat[1]).longValue(),
                                ((Number) stat[2]).longValue()});
            }
            Map<Long, Integer> askedPerSet = new LinkedHashMap<>();
            for (Object[] stat : examAttemptRepository.submittedCountPerSet(exam.getId(), false)) {
                askedPerSet.put(((Number) stat[0]).longValue(), ((Number) stat[1]).intValue());
            }
            Map<Long, String> commonestWrong = new LinkedHashMap<>();
            for (Object[] stat : examAnswerRepository.wrongAnswerSpread(exam.getId(), false)) {
                commonestWrong.putIfAbsent(((Number) stat[0]).longValue(),
                        stat[1] + " (" + ((Number) stat[2]).intValue() + ")");
            }

            for (ExamQuestion question : examQuestionRepository.findBank(ExamSets.bankExamId(exam))) {
                long[] counts = stats.getOrDefault(question.getId(), new long[] {0, 0});
                int answered = (int) counts[0];
                int correct = (int) counts[1];
                // How many papers carried it, not how many answer rows exist:
                // a question twenty people skipped is a question twenty people
                // could not answer, and counting only rows would score it 100%.
                int asked = Math.max(
                        askedPerSet.getOrDefault(question.getQuestionSet().getId(), 0), answered);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("examCode", exam.getExamCode());
                row.put("setCode", question.getQuestionSet().getSetCode());
                row.put("questionNumber", question.getQuestionNumber());
                row.put("topic", question.getTopic());
                row.put("question", question.getQuestionText());
                row.put("asked", asked);
                row.put("answered", answered);
                row.put("skipped", asked - answered);
                row.put("correct", correct);
                // Null, not zero, when nobody was asked: that question is
                // unmeasured rather than failed, and a zero would sort it to
                // the top of a list read to decide what to teach next.
                row.put("correctRate", asked == 0 ? null : Math.round(correct * 100.0 / asked));
                row.put("commonestWrong", commonestWrong.get(question.getId()));
                rows.add(row);
            }
        }

        rows.sort(Comparator.comparing(row -> row.get("correctRate") == null
                ? Long.MAX_VALUE : ((Number) row.get("correctRate")).longValue()));
        return new Built(scope, from, to, columns, rows, Map.of("rows", rows.size()));
    }

    /** Roster rows for the exams whose window opens inside the range. */
    private List<ExamParticipant> examRows(List<Employee> population, LocalDate from, LocalDate to) {
        Set<Long> ids = idsOf(population);
        if (ids.isEmpty()) {
            return List.of();
        }
        ZoneId zone = DateTimeUtils.zoneOf(population.get(0));

        return examParticipantRepository.findForReport(
                false, ids,
                from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /**
     * Every attempt on the roster, in one query.
     *
     * <p>A month-long report over two hundred people is otherwise two hundred
     * queries, which is the difference between a report and a timeout.
     */
    private Map<Long, ExamAttempt> examAttemptsFor(List<ExamParticipant> roster) {
        if (roster.isEmpty()) {
            return Map.of();
        }
        Map<Long, ExamAttempt> byParticipant = new HashMap<>();
        examAttemptRepository.findByParticipantIdIn(
                        roster.stream().map(ExamParticipant::getId).toList())
                .forEach(attempt -> byParticipant.put(attempt.getParticipant().getId(), attempt));
        return byParticipant;
    }

    /** The distinct exams the visible population was given in the range. */
    private List<Exam> examsIn(List<Employee> population, LocalDate from, LocalDate to) {
        Map<Long, Exam> exams = new LinkedHashMap<>();
        for (ExamParticipant participant : examRows(population, from, to)) {
            exams.putIfAbsent(participant.getExam().getId(), participant.getExam());
        }
        return List.copyOf(exams.values());
    }

    private ReportResult assemble(ReportType type, Built built,
                                  List<Map<String, Object>> pageRows, int page, int size) {
        int totalPages = size == 0 ? 1 : (int) Math.ceil(built.rows().size() / (double) size);

        return new ReportResult(
                type.name(),
                type.getTitle(),
                type.getDescription(),
                built.from(),
                built.to(),
                built.scope().name(),
                built.columns(),
                pageRows,
                page,
                size,
                built.rows().size(),
                Math.max(totalPages, 1),
                built.totals(),
                Instant.now());
    }
}
