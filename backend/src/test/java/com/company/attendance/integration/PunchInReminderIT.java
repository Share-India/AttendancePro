package com.company.attendance.integration;

import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.Holiday;
import com.company.attendance.entity.LeaveRequest;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.entity.Notification;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.PolicyAssignment;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.User;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.DayPortion;
import com.company.attendance.enums.NotificationType;
import com.company.attendance.enums.PolicyType;
import com.company.attendance.enums.RequestStatus;
import com.company.attendance.enums.WorkMode;
import com.company.attendance.repository.AttendancePolicyRepository;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.HolidayRepository;
import com.company.attendance.repository.LeaveRequestDayRepository;
import com.company.attendance.repository.LeaveRequestRepository;
import com.company.attendance.repository.LeaveTypeRepository;
import com.company.attendance.repository.NotificationRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.PolicyAssignmentRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import com.company.attendance.service.PunchInReminderService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chasing a missing punch-in.
 *
 * <p>The feature is small and its failure mode is not. A reminder is an email
 * to a real person saying, in effect, that the company has no record of them
 * turning up -- so the expensive mistake is not a reminder that fails to send,
 * it is one sent to somebody on sanctioned leave, on a public holiday, or on
 * their weekly off. Most of what follows is about who must never be chased.
 *
 * <p>The second theme is restraint. The condition stays true until the person
 * acts and the sweep runs every few minutes, so "once" is a property that has
 * to be tested rather than assumed.
 */
@SpringBootTest
@ActiveProfiles("test")
class PunchInReminderIT {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired PunchInReminderService reminderService;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired UserRepository userRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired WeeklyOffRepository weeklyOffRepository;
    @Autowired HolidayRepository holidayRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeaveRequestRepository leaveRequestRepository;
    @Autowired LeaveRequestDayRepository leaveRequestDayRepository;
    @Autowired AttendancePolicyRepository attendancePolicyRepository;
    @Autowired PolicyAssignmentRepository policyAssignmentRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Office office;
    private Shift general;
    private Shift early;
    private LeaveType casualLeave;
    private AttendancePolicy policy;

    /** Today, unless today is a weekend -- then the coming Monday. */
    private LocalDate workday;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        workday = LocalDate.now(IST);

        office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        general = shift("GEN", LocalTime.of(9, 30), LocalTime.of(18, 30));
        early = shift("EARLY", LocalTime.of(8, 30), LocalTime.of(17, 30));

        weeklyOffRepository.save(weeklyOff(DayOfWeek.SUNDAY));

        casualLeave = new LeaveType();
        casualLeave.setCode("CL");
        casualLeave.setName("Casual Leave");
        casualLeave = leaveTypeRepository.save(casualLeave);

        policy = new AttendancePolicy();
        policy.setCode("TEST");
        policy.setName("Test Attendance Policy");
        policy.setPunchInReminderEnabled(true);
        policy.setPunchInReminderDelayMinutes((short) 90);
        policy = attendancePolicyRepository.save(policy);

        PolicyAssignment assignment = new PolicyAssignment();
        assignment.setPolicyType(PolicyType.ATTENDANCE);
        assignment.setPolicyId(policy.getId());
        assignment.setScopeType("GLOBAL");
        policyAssignmentRepository.save(assignment);
    }

    // -----------------------------------------------------------------
    // The reminder itself
    // -----------------------------------------------------------------

    @Test
    @DisplayName("somebody who has not punched in is reminded once their own grace has passed")
    void theOrdinaryCase() {
        Employee person = employed("S9001", general);

        // 09:30 plus ninety minutes is 11:00.
        PunchInReminderService.Sweep sweep = reminderService.sweep(at(11, 5));

        assertThat(sweep.reminded()).isEqualTo(1);
        assertThat(notificationsFor(person))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.getType()).isEqualTo(NotificationType.ATTENDANCE_PUNCH_IN_REMINDER);
                    assertThat(n.getEntityId()).isEqualTo(workday.toString());
                });
    }

    @Test
    @DisplayName("nobody is chased inside their own grace period")
    void notBeforeTheDelayHasPassed() {
        employed("S9002", general);

        // 10:45 is seventy-five minutes into a shift whose reminder is due
        // at ninety. Still inside the window somebody can simply be late.
        assertThat(reminderService.sweep(at(10, 45)).reminded()).isZero();
    }

    @Test
    @DisplayName("the delay runs from each person's own shift, not from one company-wide time")
    void theDelayIsPerShift() {
        // The whole reason the delay is not a clock time. At 10:05 the early
        // shift passed its ninety minutes at 10:00; the general shift does not
        // reach its own until 11:00. One fixed time cannot serve both.
        Employee earlyBird = employed("S9003", early);
        Employee general930 = employed("S9004", general);

        reminderService.sweep(at(10, 5));

        assertThat(notificationsFor(earlyBird)).hasSize(1);
        assertThat(notificationsFor(general930)).isEmpty();
    }

    @Test
    @DisplayName("a second sweep does not send a second reminder")
    void remindedOnlyOnce() {
        // The sweep runs every few minutes and the condition it looks for
        // stays true until the person punches in. Without this, somebody
        // genuinely away is mailed all morning.
        Employee person = employed("S9005", general);

        assertThat(reminderService.sweep(at(11, 5)).reminded()).isEqualTo(1);
        assertThat(reminderService.sweep(at(11, 10)).reminded()).isZero();
        assertThat(reminderService.sweep(at(12, 30)).reminded()).isZero();

        assertThat(notificationsFor(person)).hasSize(1);
    }

    // -----------------------------------------------------------------
    // Who must never be chased
    // -----------------------------------------------------------------

    @Test
    @DisplayName("somebody on approved leave is never chased")
    void approvedLeaveIsNotChased() {
        Employee person = employed("S9006", general);
        grantLeave(person, workday);

        assertThat(reminderService.sweep(at(11, 5)).reminded()).isZero();
        assertThat(notificationsFor(person)).isEmpty();
    }

    @Test
    @DisplayName("nobody is chased on a public holiday")
    void holidaysAreNotChased() {
        Employee person = employed("S9007", general);
        declareHoliday(workday);

        assertThat(reminderService.sweep(at(11, 5)).reminded()).isZero();
        assertThat(notificationsFor(person)).isEmpty();
    }

    @Test
    @DisplayName("nobody is chased on their weekly off")
    void weeklyOffIsNotChased() {
        Employee person = employed("S9008", general);
        // A rule for whatever day today happens to be.
        weeklyOffRepository.save(weeklyOff(workday.getDayOfWeek()));

        assertThat(reminderService.sweep(at(11, 5)).reminded()).isZero();
        assertThat(notificationsFor(person)).isEmpty();
    }

    @Test
    @DisplayName("somebody who has already punched in is not chased")
    void thePresentAreNotChased() {
        Employee person = employed("S9009", general);
        punchIn(person, at(9, 28));

        assertThat(reminderService.sweep(at(11, 5)).reminded()).isZero();
        assertThat(notificationsFor(person)).isEmpty();
    }

    @Test
    @DisplayName("somebody who has not joined yet is not chased")
    void futureJoinersAreNotChased() {
        Employee person = employed("S9010", general);
        person.setJoiningDate(workday.plusWeeks(2));
        employeeRepository.save(person);

        assertThat(reminderService.sweep(at(11, 5)).reminded()).isZero();
        assertThat(notificationsFor(person)).isEmpty();
    }

    @Test
    @DisplayName("somebody who has left is not chased")
    void leaversAreNotChased() {
        Employee person = employed("S9011", general);
        person.setExitDate(workday.minusDays(1));
        employeeRepository.save(person);

        assertThat(reminderService.sweep(at(11, 5)).reminded()).isZero();
    }

    @Test
    @DisplayName("somebody with no shift on record is not chased")
    void noShiftMeansNoReminder() {
        // There is no honest time to send it: the delay is measured from a
        // shift start that does not exist. A missing shift is a data problem
        // to fix, not a reason to guess.
        Employee person = person("S9012");
        account(person);

        assertThat(reminderService.sweep(at(11, 5)).reminded()).isZero();
        assertThat(notificationsFor(person)).isEmpty();
    }

    // -----------------------------------------------------------------
    // The switch
    // -----------------------------------------------------------------

    @Test
    @DisplayName("with the policy switch off, nobody is mailed at all")
    void offMeansOff() {
        Employee person = employed("S9013", general);
        policy.setPunchInReminderEnabled(false);
        attendancePolicyRepository.save(policy);

        assertThat(reminderService.sweep(at(11, 5)).reminded()).isZero();
        assertThat(notificationsFor(person)).isEmpty();
    }

    @Test
    @DisplayName("the delay is read from the policy, not baked in")
    void theDelayIsConfigurable() {
        // Moved off the default, or this would prove nothing.
        Employee person = employed("S9014", general);
        policy.setPunchInReminderDelayMinutes((short) 30);
        attendancePolicyRepository.save(policy);

        assertThat(reminderService.sweep(at(9, 45)).reminded()).isZero();
        assertThat(reminderService.sweep(at(10, 5)).reminded()).isEqualTo(1);
        assertThat(notificationsFor(person)).hasSize(1);
    }

    @Test
    @DisplayName("somebody with no login is not mailed")
    void noAccountMeansNoReminder() {
        // The reminder asks them to punch in, which needs an account. Mailing
        // somebody a link they cannot use is noise.
        Employee person = person("S9015");
        assignShift(person, general);

        assertThat(reminderService.sweep(at(11, 5)).reminded()).isZero();
    }

    // -----------------------------------------------------------------
    // Seeding
    // -----------------------------------------------------------------

    private Shift shift(String code, LocalTime start, LocalTime end) {
        Shift s = new Shift();
        s.setCode(code);
        s.setName(code + " shift");
        s.setStartTime(start);
        s.setEndTime(end);
        s.setStandardWorkMinutes((short) 480);
        s.setFullDayMinutes((short) 480);
        s.setHalfDayMinutes((short) 240);
        s.setMinAttendanceMinutes((short) 120);
        return shiftRepository.save(s);
    }

    private WeeklyOff weeklyOff(DayOfWeek day) {
        WeeklyOff rule = new WeeklyOff();
        rule.setScopeType("GLOBAL");
        rule.setDayOfWeek((byte) day.getValue());
        return weeklyOffRepository.save(rule);
    }

    /** An employee with a shift and a login -- the ordinary case. */
    private Employee employed(String code, Shift shift) {
        Employee person = person(code);
        assignShift(person, shift);
        account(person);
        return person;
    }

    private Employee person(String code) {
        Employee person = new Employee();
        person.setEmployeeCode(code);
        person.setFirstName("Test");
        person.setLastName(code);
        person.setOfficialEmail(code.toLowerCase() + "@company.com");
        person.setJoiningDate(LocalDate.now().minusYears(1));
        person.setOffice(office);
        return employeeRepository.save(person);
    }

    private void assignShift(Employee person, Shift shift) {
        EmployeeShift assignment = new EmployeeShift();
        assignment.setEmployee(person);
        assignment.setShift(shift);
        assignment.setEffectiveFrom(person.getJoiningDate());
        employeeShiftRepository.save(assignment);
    }

    private User account(Employee person) {
        User user = new User();
        user.setUsername(person.getEmployeeCode());
        user.setEmail(person.getOfficialEmail());
        user.setPasswordHash("{noop}irrelevant");
        user.setEmployee(person);
        return userRepository.save(user);
    }

    private void punchIn(Employee person, Instant when) {
        Attendance attendance = new Attendance();
        attendance.setEmployee(person);
        attendance.setAttendanceDate(workday);
        attendance.setShift(general);
        attendance.setPunchInTime(when);
        attendance.setWorkMode(WorkMode.OFFICE);
        attendance.setAttendanceStatus(AttendanceStatus.PRESENT);
        attendanceRepository.save(attendance);
    }

    private void grantLeave(Employee person, LocalDate date) {
        LeaveRequest request = new LeaveRequest();
        request.setRequestNumber("LV-" + person.getEmployeeCode());
        request.setEmployee(person);
        request.setLeaveType(casualLeave);
        request.setFromDate(date);
        request.setToDate(date);
        request.setTotalDays(BigDecimal.ONE);
        request.setReason("Seeded");
        request.setStatus(RequestStatus.APPROVED);
        request = leaveRequestRepository.save(request);

        LeaveRequestDay day = new LeaveRequestDay();
        day.setLeaveRequest(request);
        day.setEmployeeId(person.getId());
        day.setLeaveDate(date);
        day.setDayPortion(DayPortion.FULL_DAY);
        day.setDeductible(true);
        leaveRequestDayRepository.save(day);
    }

    private void declareHoliday(LocalDate date) {
        Holiday holiday = new Holiday();
        holiday.setName("Seeded Holiday");
        holiday.setHolidayDate(date);
        holiday.setHolidayYear((short) date.getYear());
        holidayRepository.save(holiday);
    }

    /** A moment on today's date, in the office's timezone. */
    private Instant at(int hour, int minute) {
        return LocalDateTime.of(workday, LocalTime.of(hour, minute)).atZone(IST).toInstant();
    }

    private java.util.List<Notification> notificationsFor(Employee person) {
        return userRepository.findByEmployeeId(person.getId())
                .map(user -> notificationRepository.findAll().stream()
                        .filter(n -> n.getRecipientUserId().equals(user.getId()))
                        .toList())
                .orElse(java.util.List.of());
    }
}
