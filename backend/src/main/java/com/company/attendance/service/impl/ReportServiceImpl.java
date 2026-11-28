package com.company.attendance.service.impl;

import com.company.attendance.dto.request.report.ReportFilter;
import com.company.attendance.dto.response.report.ReportColumn;
import com.company.attendance.dto.response.report.ReportResult;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendanceRegularization;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Exam;
import com.company.attendance.entity.LeaveBalance;
import com.company.attendance.entity.ExamAttempt;
import com.company.attendance.entity.ExamParticipant;
import com.company.attendance.entity.ExamQuestion;
import com.company.attendance.entity.MeetingParticipant;
import com.company.attendance.entity.LeaveRequest;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.enums.AccessScope;
import com.company.attendance.enums.AttendanceStatus;
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
import com.company.attendance.repository.LeaveBalanceRepository;
import com.company.attendance.repository.LeaveRequestDayRepository;
import com.company.attendance.repository.LeaveRequestRepository;
import com.company.attendance.repository.MeetingParticipantRepository;
import com.company.attendance.security.AccessScopeResolver;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.CalendarService;
import com.company.attendance.service.LeaveBalanceService;
import com.company.attendance.service.PaidLeaveAccrualService;
import com.company.attendance.service.PayrollRulesService;
import com.company.attendance.service.PayrollRulesService.Rules;
import com.company.attendance.service.ReportService;
import com.company.attendance.service.exam.ExamSets;
import com.company.attendance.util.DateTimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    private static final Logger log = LoggerFactory.getLogger(ReportServiceImpl.class);

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
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final PayrollRulesService payrollRulesService;
    private final CalendarService calendarService;
    private final LeaveBalanceService leaveBalanceService;
    private final PaidLeaveAccrualService paidLeaveAccrualService;
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
                             LeaveBalanceRepository leaveBalanceRepository,
                             PayrollRulesService payrollRulesService,
                             CalendarService calendarService,
                             LeaveBalanceService leaveBalanceService,
                             PaidLeaveAccrualService paidLeaveAccrualService,
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
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.payrollRulesService = payrollRulesService;
        this.calendarService = calendarService;
        this.leaveBalanceService = leaveBalanceService;
        this.paidLeaveAccrualService = paidLeaveAccrualService;
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
            case SALARY_ATTENDANCE -> salaryAttendance(scope, population, from, to);
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

    // -----------------------------------------------------------------
    // The salary sheet
    //
    // This is the one report that is a transcription rather than a
    // summary: payroll already keeps this sheet by hand, column for
    // column, and the point of producing it is that the two can be laid
    // side by side and compared line by line. So the layout follows
    // theirs — S.No, name, one column per day, then the counts — and the
    // day cells use their words, not the enum names.
    //
    // The counts are not independent of each other. They satisfy, for
    // every row:
    //
    //     days counted            = working days + offs + Saturday halves
    //     working days            = days worked + leaves taken + LOP
    //     Post leave adjustment   = days worked + leaves taken
    //     Salary payable Days     = days worked + leaves taken + offs
    //                                 - LOP (late marks)
    //     Carry forward           = opening + month's accrual - taken
    //
    // Leaves taken can never exceed the opening balance plus the
    // month's accrual, because a day is only charged to leave that is
    // actually there: a leave the balance cannot pay for becomes an
    // absence on that day and loss of pay in the column. So the
    // carry-forward is never negative, and the overdraft that used to
    // live in a sentence in the Comments column is now a figure the
    // payable days reflect.
    //
    // A working day is counted at its weight, which is a half for a
    // rostered short Saturday and one for everything else, so the first
    // identity is what accounts for the halves that are neither owed to
    // anybody nor paid to anybody. SalaryAttendanceRulesIT asserts all
    // three on every row it produces.
    //
    // ON THE TWO SETS OF FIGURES
    //
    // The layout and the arithmetic were verified against three rows of
    // payroll's own October 2026 sheet before any of this was written,
    // and under those rules an Absent day was charged to the leave
    // balance -- which is what made their payable figure come to a full
    // month for somebody who missed a day.
    //
    // V32 changed three of the rules, dated from 1 November 2026, and
    // the sheet no longer agrees with payroll's for a month under them:
    // an unapplied absence is loss of pay instead of a charge to the
    // balance, a worked Saturday pays half a day instead of a full one,
    // and every second late mark costs a day. A month before that date
    // still reproduces exactly, which is the point of dating the rules
    // rather than switching them -- see Rules#appliesOn.
    // -----------------------------------------------------------------

    /** The leave type a salary sheet is denominated in. */
    private static final String PAID_LEAVE_CODE = "PL";

    private static final BigDecimal HALF = new BigDecimal("0.5");


    /**
     * What one day of the sheet says, and what it counts as.
     *
     * <p>The labels are payroll's own vocabulary. Nothing downstream
     * parses them back — they are read by a person comparing two sheets.
     */
    private enum DayCell {

        /** Not employed yet, or already left. */
        BLANK(""),

        /** A day still to come. Blank like the paper sheet, and counted nowhere. */
        UPCOMING(""),

        /** Before this system was recording. Not absent; see V26. */
        NOT_TRACKED("Not tracked"),

        PRESENT("Present"),
        LATE("Late"),
        SHORT_DAY("Short day"),
        HALF_DAY("Half day"),
        LEAVE("Leave"),
        ABSENT("Absent"),
        HOLIDAY("Holiday"),
        SUNDAY("Sunday"),
        SATURDAY_OFF("Sat off"),
        WEEKLY_OFF("Weekly off");

        private final String label;

        DayCell(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        /** Counted under "Holidays & Weekly offs", and never a working day. */
        boolean isOff() {
            return this == HOLIDAY || this == SUNDAY || this == SATURDAY_OFF || this == WEEKLY_OFF;
        }

        /**
         * Whether the sheet counts this day at all.
         *
         * <p>A day that is not counted contributes to nothing — not to the
         * working days, and not to the holidays and offs either. Counting an
         * untracked Sunday as an off while leaving the untracked Mondays out
         * breaks the identity the whole sheet rests on, and leaves a payable
         * figure that reconciles with neither the month nor the part of it
         * the system actually saw.
         */
        boolean isCounted() {
            return this != BLANK && this != UPCOMING && this != NOT_TRACKED;
        }

        /**
         * The fraction of the day's own weight that was worked.
         *
         * <p>A fraction, not a number of days: the caller multiplies by what
         * the day is owed, so a present Saturday credits half a day and a half
         * day on a Saturday credits a quarter. A late arrival still worked.
         */
        BigDecimal workedFraction() {
            // SHORT_DAY earns a full day. It is a cell of its own only so the
            // sheet shows which days ran short of the mark.
            if (this == PRESENT || this == LATE || this == SHORT_DAY) {
                return BigDecimal.ONE;
            }
            return this == HALF_DAY ? HALF : BigDecimal.ZERO;
        }
    }

    private Built salaryAttendance(AccessScope scope, List<Employee> population,
                                   LocalDate from, LocalDate to) {

        YearMonth month = YearMonth.from(from);
        if (!month.equals(YearMonth.from(to))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "A salary sheet covers one calendar month. Pick a range inside a "
                            + "single month — the whole month is produced either way.");
        }

        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        LocalDate today = LocalDate.now();

        // Resolved once. Every day of every employee needs it, and reading it
        // from company_settings per use turned a 42-person sheet into
        // thousands of queries -- slow enough that the browser gave up before
        // the report returned.
        LocalDate trackingStart = calendarService.trackingStartDate();

        // Read once, for the same reason: the rules are two settings rows and
        // a policy row, and every day of every replayed month asks about them.
        Rules rules = payrollRulesService.current();

        short leaveYear = leaveBalanceService.resolveLeaveYear(monthStart);
        LocalDate yearStart = leaveBalanceService.leaveYearStart(leaveYear);
        LocalDate accrualStart = leaveBalanceService.accrualStartDate();

        // The sheet's opening balance is the closing balance of every month
        // before it in the leave year, so those months have to be replayed —
        // absence is inferred, never stored, and cannot be looked up.
        LocalDate historyFrom = yearStart.isBefore(monthStart) ? yearStart : monthStart;

        List<ReportColumn> columns = salaryColumns(month);

        Map<LocalDate, Map<Long, DayType>> dayTypes =
                calendarService.resolveDayTypes(population, historyFrom, monthEnd);

        Map<Long, Map<LocalDate, Attendance>> attendanceByEmployee =
                attendanceInRange(population, historyFrom, monthEnd).stream()
                        .collect(Collectors.groupingBy(a -> a.getEmployee().getId(),
                                Collectors.toMap(Attendance::getAttendanceDate,
                                        Function.identity(), (first, second) -> first)));

        // Present with a zero value still means "on leave": a day inside a
        // leave span that falls on a holiday is shown as the holiday and
        // charged nothing, which is what makes the sandwich rule visible
        // here rather than silently generous.
        Map<Long, Map<LocalDate, BigDecimal>> leaveByEmployee = new HashMap<>();
        Set<Long> ids = idsOf(population);
        if (!ids.isEmpty()) {
            for (LeaveRequestDay day : leaveRequestDayRepository
                    .findApprovedForEmployeesBetween(ids, historyFrom, monthEnd)) {
                leaveByEmployee
                        .computeIfAbsent(day.getEmployeeId(), key -> new HashMap<>())
                        .merge(day.getLeaveDate(),
                                day.getDeductibleDays(),
                                BigDecimal::add);
            }
        }

        Map<Long, LeaveBalance> ledger = ids.isEmpty() ? Map.of()
                : leaveBalanceRepository
                        .findForEmployeesAndTypeAndYear(ids, PAID_LEAVE_CODE, leaveYear).stream()
                        .collect(Collectors.toMap(balance -> balance.getEmployee().getId(),
                                Function.identity(), (first, second) -> first));

        List<Map<String, Object>> rows = new ArrayList<>();
        int serial = 0;

        for (Employee employee : population) {
            Map<LocalDate, Attendance> days =
                    attendanceByEmployee.getOrDefault(employee.getId(), Map.of());
            Map<LocalDate, BigDecimal> leaves =
                    leaveByEmployee.getOrDefault(employee.getId(), Map.of());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sno", ++serial);
            row.put("employeeName", employee.getFullName());

            // Resolved before the days rather than after them, because a day
            // can only be charged to a balance that still has something in
            // it. "Debit 0.5 from paid leave if available" is the rule, and
            // the day loop cannot honour the second half of it without
            // knowing what is there to draw on.
            BigDecimal opening = openingBalance(employee, ledger.get(employee.getId()),
                    yearStart, monthStart, accrualStart, dayTypes, days, leaves, today,
                    trackingStart, rules);
            BigDecimal accrued = paidLeaveAccrualService
                    .accruedBy(employee, monthStart, monthEnd, accrualStart);

            // The month's accrual is spendable within the month, which is the
            // behaviour the sheet has always had.
            BigDecimal available = opening.add(accrued);

            BigDecimal worked = BigDecimal.ZERO;
            BigDecimal taken = BigDecimal.ZERO;
            BigDecimal unpaid = BigDecimal.ZERO;
            // Both are loss of pay and both land in the same column, but the
            // advice differs and only one of them is the employee's to fix.
            // Telling somebody to apply for leave they did in fact apply for,
            // and which was in fact approved, is worse than saying nothing.
            BigDecimal unpaidNoLeave = BigDecimal.ZERO;
            BigDecimal unpaidNoBalance = BigDecimal.ZERO;
            BigDecimal workingDays = BigDecimal.ZERO;
            BigDecimal saturdayHalves = BigDecimal.ZERO;
            int counted = 0;
            int offs = 0;
            int untracked = 0;
            int upcoming = 0;
            int absences = 0;
            int lateMarks = 0;
            int graceUsed = 0;

            for (int day = 1; day <= month.lengthOfMonth(); day++) {
                LocalDate date = month.atDay(day);
                Attendance attendance = days.get(date);
                DayCell cell = classify(employee, date,
                        dayTypes.getOrDefault(date, Map.of())
                                .getOrDefault(employee.getId(), DayType.WORKING),
                        attendance, leaves.containsKey(date), today, trackingStart);

                if (!cell.isCounted()) {
                    row.put(dayKey(day), cellLabel(cell, attendance));
                    if (cell == DayCell.NOT_TRACKED) {
                        untracked++;
                    } else if (cell == DayCell.UPCOMING) {
                        upcoming++;
                    }
                    continue;
                }
                counted++;
                if (cell.isOff()) {
                    row.put(dayKey(day), cellLabel(cell, attendance));
                    offs++;
                    continue;
                }

                // What this day is owed. A rostered short Saturday is half a
                // working day, so it can be worth at most half a day and cost
                // at most half a day. The other half is owed by nobody and
                // paid to nobody; it is totalled separately so the sheet
                // still reconciles to the length of the month rather than
                // leaving payroll to account for a missing 1.5 days.
                BigDecimal weight = rules.weightOf(date);
                workingDays = workingDays.add(weight);
                saturdayHalves = saturdayHalves.add(BigDecimal.ONE.subtract(weight));

                if (attendance != null) {
                    // Recorded late minutes are what the month's 30-minute
                    // pool could no longer absorb, so any at all is a late
                    // mark. The minutes the pool did absorb are counted too,
                    // and shown: they are spent, and the next late arrival
                    // has fewer of them.
                    if (attendance.getLateMinutes() > 0 && rules.appliesOn(date)) {
                        lateMarks++;
                    }
                    graceUsed += attendance.getGraceUsedMinutes();
                }

                // A counted working day is worth its weight, split between
                // what was worked and what was charged to the balance.
                // Charging first and crediting the remainder keeps that true
                // however the day is made up: a half day of leave with a
                // punch for the other half charges the half day and credits
                // the half worked, instead of crediting a whole day and
                // charging nothing.
                BigDecimal wanted;
                if (cell == DayCell.ABSENT) {
                    // Before the effective date, payroll charged an absence to
                    // the leave balance by itself — the only reason their
                    // payable figure came to a full month for somebody who did
                    // not turn up. From it, an absence nobody applied for is
                    // loss of pay and the balance is untouched. Applying for
                    // the leave and having it approved is what converts the day
                    // back, which is the whole point of the rule.
                    wanted = rules.appliesOn(date) ? BigDecimal.ZERO : weight;
                } else {
                    wanted = leaves.getOrDefault(date, BigDecimal.ZERO).min(weight);
                }

                // A leave is only paid out of leave somebody actually has.
                // Approval checks the balance at the time, but the balance can
                // be spent by an earlier day between approval and month end,
                // and the sheet replays its own figure rather than reading a
                // ledger — so the day is where the two have to be reconciled.
                // Days are charged in date order: first come, first served.
                //
                // Before the effective date this cap is off, and an overdrawn
                // balance stays a note in the Comments column. Turning it on
                // for those months would change payable figures that have
                // already been paid.
                BigDecimal charged = rules.appliesOn(date) ? wanted.min(available) : wanted;
                available = available.subtract(charged).max(BigDecimal.ZERO);

                BigDecimal workedToday = cell.workedFraction().multiply(weight)
                        .min(weight.subtract(charged))
                        .max(BigDecimal.ZERO);

                BigDecimal uncovered = weight.subtract(charged).subtract(workedToday)
                        .max(BigDecimal.ZERO);

                // A leave the balance could not pay for is an absence, and the
                // sheet says so. Leaving it reading "Leave" while the pay came
                // out in the LOP column would put the two halves of the same
                // fact in different places, and the person querying their
                // payslip reads the day, not the column.
                if (cell == DayCell.LEAVE && charged.signum() == 0) {
                    cell = DayCell.ABSENT;
                }

                if (cell == DayCell.ABSENT) {
                    absences++;
                }

                row.put(dayKey(day), partlyPaidLabel(cell, attendance, charged, uncovered));

                taken = taken.add(charged);
                worked = worked.add(workedToday);
                unpaid = unpaid.add(uncovered);
                if (uncovered.signum() > 0) {
                    if (wanted.signum() > 0) {
                        // Leave was applied for and approved; the balance just
                        // could not pay for all of it.
                        unpaidNoBalance = unpaidNoBalance.add(uncovered);
                    } else {
                        unpaidNoLeave = unpaidNoLeave.add(uncovered);
                    }
                }
            }

            BigDecimal latePenalty = rules.penaltyFor(lateMarks);

            BigDecimal carried = opening.add(accrued).subtract(taken);

            // Under the new rules this is always zero: nothing is charged
            // beyond the balance, so the overdraft shows up as loss of pay in
            // a column instead of as a sentence. It stays for the months
            // before them, where a balance could be overdrawn and the comment
            // was the only place that said so.
            BigDecimal shortfall = carried.signum() < 0 ? carried.negate() : BigDecimal.ZERO;

            // Loss of pay never makes a payable figure negative. Somebody who
            // managed both a month of late marks and a month of absence is a
            // conversation, not a credit note.
            BigDecimal payable = worked.add(taken).add(BigDecimal.valueOf(offs))
                    .subtract(latePenalty)
                    .max(BigDecimal.ZERO);

            row.put("openingBalance", strip(opening));
            row.put("leavesTaken", strip(taken));
            row.put("workingDays", strip(workingDays));
            row.put("daysWorked", strip(worked));
            row.put("postLeaveAdjustment", strip(worked.add(taken)));
            row.put("holidaysAndOffs", offs);
            row.put("lateMarks", lateMarks);
            row.put("graceUsedMinutes", graceUsed);
            row.put("latePenaltyDays", strip(latePenalty));
            row.put("unpaidDays", strip(unpaid));
            row.put("saturdayHalfDays", strip(saturdayHalves));
            row.put("payableDays", strip(payable));
            row.put("monthlyAccrual", strip(accrued));
            row.put("carryForward", strip(carried.max(BigDecimal.ZERO)));
            row.put("comments", comments(month, untracked, upcoming, absences,
                    unpaidNoLeave, unpaidNoBalance, lateMarks, latePenalty, shortfall));

            rows.add(row);
        }

        return new Built(scope, monthStart, monthEnd, columns, rows,
                Map.of("rows", rows.size()));
    }

    private List<ReportColumn> salaryColumns(YearMonth month) {
        String monthName = month.getMonth()
                .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String monthAndYear = monthName + " " + month.getYear();
        YearMonth next = month.plusMonths(1);
        String nextMonthAndYear = next.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + next.getYear();

        List<ReportColumn> columns = new ArrayList<>();
        columns.add(ReportColumn.number("sno", "S.No"));
        columns.add(ReportColumn.text("employeeName", "Employee Name"));

        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            columns.add(ReportColumn.text(dayKey(day), ordinal(day) + " " + monthName));
        }

        columns.add(ReportColumn.number("openingBalance",
                "Balance leaves as on 1st " + monthAndYear));
        columns.add(ReportColumn.number("leavesTaken", "Total Leaves Taken"));
        columns.add(ReportColumn.number("workingDays", "Total working days"));
        columns.add(ReportColumn.number("daysWorked", "Total days worked (Present days)"));
        columns.add(ReportColumn.number("postLeaveAdjustment",
                "Total working days post leave adjustment"));
        columns.add(ReportColumn.number("holidaysAndOffs", "Holidays & Weekly offs"));

        // The deductions, between the figures they come from and the figure
        // they reduce. A payable day count that is short of the month is the
        // first thing anybody asks about, and these five columns are the
        // answer without a second report.
        columns.add(ReportColumn.number("lateMarks", "Late marks"));
        columns.add(ReportColumn.number("graceUsedMinutes", "Grace used (min of 30)"));
        columns.add(ReportColumn.number("latePenaltyDays", "LOP (late marks)"));
        columns.add(ReportColumn.number("unpaidDays", "LOP (no leave applied)"));
        columns.add(ReportColumn.number("saturdayHalfDays", "Saturday half days"));

        columns.add(ReportColumn.number("payableDays", "Salary payable Days"));
        columns.add(ReportColumn.number("monthlyAccrual", "Paid leaves for the month"));
        columns.add(ReportColumn.number("carryForward",
                "Leaves to be carry forwarded " + nextMonthAndYear));
        columns.add(ReportColumn.text("comments", "Comments"));

        return columns;
    }

    private static String dayKey(int day) {
        return "d" + day;
    }

    /**
     * What one day's cell reads as, including the minutes.
     *
     * <p>The cell used to say "Late" and nothing else, which told somebody
     * comparing two sheets that a day was a late mark but not whether it was
     * by two minutes or by two hours — and said nothing at all about the
     * month's 30-minute pool, which is what decides whether a late arrival
     * becomes a late mark in the first place. Three cases now read
     * differently:
     *
     * <ul>
     *   <li><strong>Late by 7m (5m adj)</strong> — arrived 12 minutes late
     *       with 5 minutes of the pool left. The 5 were spent, 7 are on the
     *       record, and the day is a late mark.
     *   <li><strong>Present (5m adj)</strong> — arrived 5 minutes late and
     *       the pool covered all of it. Not a late mark, but the minutes are
     *       gone and the next late arrival has fewer.
     *   <li><strong>Late by 12m</strong> — the pool was already empty.
     * </ul>
     */
    private String cellLabel(DayCell cell, Attendance attendance) {
        if (attendance == null) {
            return cell.label();
        }

        int late = attendance.getLateMinutes();
        int adjusted = attendance.getGraceUsedMinutes();

        if (late > 0) {
            String text = "Late by " + minutes(late);
            return adjusted > 0 ? text + " (" + minutes(adjusted) + " adj)" : text;
        }
        if (adjusted > 0) {
            return cell.label() + " (" + minutes(adjusted) + " adj)";
        }
        return cell.label();
    }

    /**
     * A day's cell, saying so when the balance only partly paid for it.
     *
     * <p>Three outcomes for a leave day. Covered in full it reads "Leave", as
     * it always has. Covered by nothing it has already been turned into an
     * absence by the caller and reads "Absent". Covered in part — half a day
     * of balance against a whole day of leave — it reads
     * <strong>Leave 0.5 (0.5 unpaid)</strong>, because neither "Leave" nor
     * "Absent" is true of it and rounding it to either loses the half that
     * somebody will query.
     */
    private String partlyPaidLabel(DayCell cell, Attendance attendance,
                                   BigDecimal charged, BigDecimal uncovered) {
        String label = cellLabel(cell, attendance);

        if (cell != DayCell.LEAVE || charged.signum() == 0 || uncovered.signum() == 0) {
            return label;
        }
        return label + " " + strip(charged) + " (" + strip(uncovered) + " unpaid)";
    }

    /** "7m", "1h 5m". A day cell on a 48-column sheet has no room for words. */
    private static String minutes(int total) {
        if (total < 60) {
            return total + "m";
        }
        int hours = total / 60;
        int rest = total % 60;
        return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
    }

    private static String ordinal(int day) {
        String suffix = switch (day % 100) {
            case 11, 12, 13 -> "th";
            default -> switch (day % 10) {
                case 1 -> "st";
                case 2 -> "nd";
                case 3 -> "rd";
                default -> "th";
            };
        };
        return day + suffix;
    }

    /**
     * What one day of the sheet reads as.
     *
     * <p>The precedence deliberately matches the attendance calendar's, so
     * the salary sheet and the screen an employee can see never disagree
     * about a date. A recorded punch outranks the calendar — somebody who
     * came in on a Sunday worked that Sunday — and a holiday outranks a
     * weekly off and a leave day both.
     */
    private DayCell classify(Employee employee, LocalDate date, DayType dayType,
                             Attendance attendance, boolean onLeave, LocalDate today,
                             LocalDate trackingStart) {

        if (!wasEmployedOn(employee, date)) {
            return DayCell.BLANK;
        }

        if (attendance != null && attendance.getPunchInTime() != null) {
            if (attendance.getAttendanceStatus() == AttendanceStatus.HALF_DAY) {
                return DayCell.HALF_DAY;
            }
            if (attendance.getAttendanceStatus() == AttendanceStatus.SHORT_DAY) {
                return DayCell.SHORT_DAY;
            }
            return attendance.getLateMinutes() > 0 ? DayCell.LATE : DayCell.PRESENT;
        }

        // Asked before the calendar, not after. Whether a day is on the
        // sheet at all is settled first; only then does what kind of day it
        // was matter.
        if (date.isAfter(today)) {
            return DayCell.UPCOMING;
        }
        // Compared against a value resolved once for the whole report, not
        // asked of CalendarService per call: this runs for every employee on
        // every day of the replayed leave year, and a settings lookup there
        // is thousands of queries for one sheet.
        if (trackingStart != null && date.isBefore(trackingStart)) {
            return DayCell.NOT_TRACKED;
        }

        if (dayType == DayType.HOLIDAY) {
            return DayCell.HOLIDAY;
        }
        if (dayType == DayType.WEEKLY_OFF) {
            return switch (date.getDayOfWeek()) {
                case SUNDAY -> DayCell.SUNDAY;
                case SATURDAY -> DayCell.SATURDAY_OFF;
                default -> DayCell.WEEKLY_OFF;
            };
        }
        if (onLeave) {
            return DayCell.LEAVE;
        }
        return DayCell.ABSENT;
    }

    /**
     * The balance on the first of the month.
     *
     * <p>Not a stored figure. The ledger holds what was carried into the
     * leave year and any audited adjustment; everything since is the months
     * between, replayed — accrual earned, leave taken, absences charged. The
     * alternative is reading {@code available_days} off the ledger, which is
     * right to the day rather than to the first of the month and does not
     * charge absences at all, so it would disagree with the carry-forward
     * column of the previous sheet. Two columns that are supposed to be the
     * same number must be computed the same way.
     *
     * <p>For the first month the system owns the ledger this reduces to the
     * carried-in figure alone: nothing accrues before
     * {@code leave.accrual_start_date} and no earlier day was tracked.
     */
    private BigDecimal openingBalance(Employee employee, LeaveBalance balance,
                                      LocalDate yearStart, LocalDate monthStart,
                                      LocalDate accrualStart,
                                      Map<LocalDate, Map<Long, DayType>> dayTypes,
                                      Map<LocalDate, Attendance> days,
                                      Map<LocalDate, BigDecimal> leaves,
                                      LocalDate today, LocalDate trackingStart,
                                      Rules rules) {

        BigDecimal opening = balance == null ? BigDecimal.ZERO
                : balance.getOpeningBalance()
                        .add(balance.getCarriedForwardDays())
                        .add(balance.getAdjustedDays());

        if (!yearStart.isBefore(monthStart)) {
            return opening;
        }

        LocalDate priorEnd = monthStart.minusDays(1);
        opening = opening.add(paidLeaveAccrualService
                .accruedBy(employee, yearStart, priorEnd, accrualStart));

        for (LocalDate date = yearStart; !date.isAfter(priorEnd); date = date.plusDays(1)) {
            DayCell cell = classify(employee, date,
                    dayTypes.getOrDefault(date, Map.of())
                            .getOrDefault(employee.getId(), DayType.WORKING),
                    days.get(date), leaves.containsKey(date), today, trackingStart);

            if (cell == DayCell.LEAVE || cell == DayCell.HALF_DAY) {
                opening = opening.subtract(leaves.getOrDefault(date, BigDecimal.ZERO)
                        .min(rules.weightOf(date)));
            } else if (cell == DayCell.ABSENT && !rules.appliesOn(date)) {
                // Only under the old rules. Charging absences to the balance
                // across the replayed months as well would refund, to every
                // balance, every absence since the leave year began — for
                // months that have already been paid under the rule that
                // charged them.
                opening = opening.subtract(BigDecimal.ONE);
            }
        }

        return opening.max(BigDecimal.ZERO);
    }

    /**
     * The one column a person writes in, filled in for them where the
     * figures need explaining.
     *
     * <p>A month the system joined part-way through is the case that matters:
     * without a line saying so, a payable figure of fifteen in a thirty-day
     * month looks like a bug rather than a go-live date.
     */
    private String comments(YearMonth month, int untracked, int upcoming,
                            int absences, BigDecimal unpaidNoLeave,
                            BigDecimal unpaidNoBalance, int lateMarks,
                            BigDecimal latePenalty, BigDecimal shortfall) {
        List<String> notes = new ArrayList<>();

        if (untracked > 0) {
            // Named only when the go-live date is inside this month. Running a
            // sheet for a month entirely before go-live would otherwise print
            // the right day against the wrong month's name.
            LocalDate start = calendarService.trackingStartDate();
            boolean startIsInThisMonth = start != null && YearMonth.from(start).equals(month);

            notes.add(untracked + " day" + (untracked == 1 ? "" : "s")
                    + " before this system began recording"
                    + (startIsInThisMonth
                            ? " on " + ordinal(start.getDayOfMonth()) + " "
                                    + month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                            : "")
                    + ". The figures cover the rest of the month only.");
        }
        if (upcoming > 0) {
            notes.add(upcoming + " day" + (upcoming == 1 ? "" : "s")
                    + " of the month still to come. Run this again once the month has closed.");
        }
        // Two separate facts, and a row can carry one without the other. How
        // many days somebody was away is an attendance fact and is stated
        // whatever the rules do with it. What those days cost is a payroll
        // fact: zero while an absence is still charged to the leave balance,
        // and a figure in days — 0.5 for a missed Saturday — once it is not.
        if (absences > 0) {
            notes.add(absences == 1 ? "Absent" : absences + " days absent");
        }
        if (unpaidNoLeave.signum() > 0) {
            notes.add(strip(unpaidNoLeave) + " day" + plural(unpaidNoLeave)
                    + " with no leave applied — loss of pay. Applying for the"
                    + " leave and having it approved converts this back.");
        }
        if (unpaidNoBalance.signum() > 0) {
            // Nothing for the employee to do about this one, so it does not
            // tell them to do anything. The balance is the fact.
            notes.add(strip(unpaidNoBalance) + " day" + plural(unpaidNoBalance)
                    + " of approved leave with no paid leave left to cover it"
                    + " — loss of pay.");
        }
        if (lateMarks > 0) {
            String note = lateMarks + " late mark" + (lateMarks == 1 ? "" : "s");
            notes.add(latePenalty.signum() > 0
                    ? note + " — " + strip(latePenalty) + " day" + plural(latePenalty)
                            + " loss of pay."
                    : note + ", below the threshold that costs a day.");
        }
        if (shortfall.signum() > 0) {
            notes.add(strip(shortfall) + " day" + plural(shortfall)
                    + " beyond the leave balance — loss of pay.");
        }

        return String.join(" ", notes);
    }

    /** "day" or "days", for a figure that may be a half. */
    private static String plural(BigDecimal days) {
        return BigDecimal.ONE.compareTo(days) == 0 ? "" : "s";
    }

    /** 2.0 reads as 2, 2.50 as 2.5. A sheet of trailing zeroes is harder to check. */
    private static Object strip(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.longValueExact() : stripped;
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
