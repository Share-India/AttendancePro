package com.company.attendance.integration;

import com.company.attendance.dto.request.request.RequestDecisionRequest;
import com.company.attendance.dto.request.request.WorkFromHomeApplyRequest;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeManager;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.User;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.PolicyType;
import com.company.attendance.enums.RelationshipType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.LocationPolicy;
import com.company.attendance.entity.PolicyAssignment;
import com.company.attendance.repository.AttendancePolicyRepository;
import com.company.attendance.repository.EmployeeManagerRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.LocationPolicyRepository;
import com.company.attendance.repository.NotificationRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.PolicyAssignmentRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.NotificationService;
import com.company.attendance.service.WorkFromHomeService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Notifications are a side effect of the things that matter, and these tests
 * exist mostly to keep them behaving like one: they must reach the right
 * person, carry the reason a decision was made, and never be able to break the
 * flow that produced them.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationFlowIT {

    @Autowired WorkFromHomeService workFromHomeService;
    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeManagerRepository employeeManagerRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired WeeklyOffRepository weeklyOffRepository;
    @Autowired AttendancePolicyRepository attendancePolicyRepository;
    @Autowired LocationPolicyRepository locationPolicyRepository;
    @Autowired PolicyAssignmentRepository policyAssignmentRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee applicant;
    private Employee manager;
    private User applicantUser;
    private User managerUser;
    private UserPrincipal applicantPrincipal;
    private UserPrincipal approverPrincipal;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        Office office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        Shift shift = new Shift();
        shift.setCode("GEN");
        shift.setName("General Shift");
        shift.setStartTime(LocalTime.of(9, 30));
        shift.setEndTime(LocalTime.of(18, 30));
        shift = shiftRepository.save(shift);

        applicant = employee(office, shift, "EMP7001", "Asha", "Rao");
        manager = employee(office, shift, "EMP7002", "Priya", "Sharma");

        EmployeeManager reporting = new EmployeeManager();
        reporting.setEmployee(applicant);
        reporting.setManager(manager);
        reporting.setRelationshipType(RelationshipType.PRIMARY);
        reporting.setEffectiveFrom(LocalDate.now().minusYears(1));
        employeeManagerRepository.save(reporting);

        applicantUser = user(applicant, "EMP7001");
        managerUser = user(manager, "EMP7002");

        weeklyOffRepository.save(weeklyOff(DayOfWeek.SATURDAY));
        weeklyOffRepository.save(weeklyOff(DayOfWeek.SUNDAY));

        AttendancePolicy attendancePolicy = new AttendancePolicy();
        attendancePolicy.setCode("TEST");
        attendancePolicy.setName("Test Attendance Policy");
        attendancePolicy.setMaxWfhDaysPerMonth((short) 10);
        attendancePolicy = attendancePolicyRepository.save(attendancePolicy);
        assign(PolicyType.ATTENDANCE, attendancePolicy.getId());

        LocationPolicy locationPolicy = new LocationPolicy();
        locationPolicy.setCode("TEST");
        locationPolicy.setName("Test Location Policy");
        locationPolicy = locationPolicyRepository.save(locationPolicy);
        assign(PolicyType.LOCATION, locationPolicy.getId());

        applicantPrincipal = UserPrincipal.fromClaims(applicantUser.getId(), applicant.getId(),
                "EMP7001", Set.of("EMPLOYEE"), Set.of("WFH_APPLY"));
        approverPrincipal = UserPrincipal.fromClaims(managerUser.getId(), manager.getId(),
                "EMP7002", Set.of("MANAGER"), Set.of("WFH_APPROVE", "ATTENDANCE_VIEW_TEAM"));
    }

    private Employee employee(Office office, Shift shift, String code, String first, String last) {
        Employee person = new Employee();
        person.setEmployeeCode(code);
        person.setFirstName(first);
        person.setLastName(last);
        person.setOfficialEmail(code.toLowerCase() + "@company.com");
        person.setJoiningDate(LocalDate.now().minusYears(2));
        person.setOffice(office);
        person = employeeRepository.save(person);

        EmployeeShift assignment = new EmployeeShift();
        assignment.setEmployee(person);
        assignment.setShift(shift);
        assignment.setEffectiveFrom(person.getJoiningDate());
        employeeShiftRepository.save(assignment);
        return person;
    }

    private User user(Employee employee, String username) {
        User account = new User();
        account.setUsername(username);
        account.setEmail(employee.getOfficialEmail());
        account.setPasswordHash("{noop}irrelevant");
        account.setEmployee(employee);
        return userRepository.save(account);
    }

    private WeeklyOff weeklyOff(DayOfWeek day) {
        WeeklyOff rule = new WeeklyOff();
        rule.setScopeType("GLOBAL");
        rule.setDayOfWeek((byte) day.getValue());
        return rule;
    }

    private void assign(PolicyType type, Long policyId) {
        PolicyAssignment assignment = new PolicyAssignment();
        assignment.setPolicyType(type);
        assignment.setPolicyId(policyId);
        assignment.setScopeType("GLOBAL");
        policyAssignmentRepository.save(assignment);
    }

    private LocalDate nextWorkingDay() {
        LocalDate date = LocalDate.now().plusDays(2);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("raising a request tells the person who has to decide it")
    void applyNotifiesTheApprover() {
        LocalDate day = nextWorkingDay();
        workFromHomeService.apply(applicantPrincipal,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));

        var forManager = notificationRepository
                .findByRecipientUserIdOrderByCreatedAtDesc(managerUser.getId(), PageRequest.of(0, 10));

        assertThat(forManager.getContent()).hasSize(1);
        assertThat(forManager.getContent().get(0).getTitle()).contains("Asha Rao");
        assertThat(forManager.getContent().get(0).getLink()).isEqualTo("/approvals/requests");
        // And the applicant is told nothing: they already know what they asked for.
        assertThat(notificationRepository.countByRecipientUserIdAndReadFalse(applicantUser.getId()))
                .isZero();
    }

    @Test
    @DisplayName("a rejection reaches the applicant with the reason attached")
    void rejectionCarriesItsReason() {
        LocalDate day = nextWorkingDay();
        var request = workFromHomeService.apply(applicantPrincipal,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));

        workFromHomeService.reject(approverPrincipal, request.id(),
                new RequestDecisionRequest("We have the quarterly review that day."));

        var forApplicant = notificationRepository
                .findByRecipientUserIdOrderByCreatedAtDesc(applicantUser.getId(), PageRequest.of(0, 10));

        assertThat(forApplicant.getContent()).hasSize(1);
        var notification = forApplicant.getContent().get(0);
        assertThat(notification.getTitle()).contains("rejected");
        // A rejection without its reason is the most frustrating message there is.
        assertThat(notification.getBody()).contains("quarterly review");
        assertThat(notification.getActorName()).isEqualTo("Priya Sharma");
    }

    @Test
    @DisplayName("nobody is told about their own action")
    void actorIsNotNotifiedOfTheirOwnDecision() {
        LocalDate day = nextWorkingDay();
        var request = workFromHomeService.apply(applicantPrincipal,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));

        long before = notificationRepository.countByRecipientUserIdAndReadFalse(managerUser.getId());
        workFromHomeService.approve(approverPrincipal, request.id(), new RequestDecisionRequest("Fine"));

        // Still just the original request; approving it added nothing for them.
        assertThat(notificationRepository.countByRecipientUserIdAndReadFalse(managerUser.getId()))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("the actor is named by their name, not their login")
    void actorIsNamedNotCoded() {
        LocalDate day = nextWorkingDay();
        var request = workFromHomeService.apply(applicantPrincipal,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));
        workFromHomeService.approve(approverPrincipal, request.id(), new RequestDecisionRequest("Fine"));

        var forApplicant = notificationRepository
                .findByRecipientUserIdOrderByCreatedAtDesc(applicantUser.getId(), PageRequest.of(0, 10));

        // "EMP7002 approved your request" is not what anybody wants to read.
        assertThat(forApplicant.getContent().get(0).getActorName()).isEqualTo("Priya Sharma");
    }

    @Test
    @DisplayName("one person cannot mark another person's notification read")
    void readStateIsScopedToItsOwner() {
        LocalDate day = nextWorkingDay();
        workFromHomeService.apply(applicantPrincipal,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));

        Long managersNotification = notificationRepository
                .findByRecipientUserIdOrderByCreatedAtDesc(managerUser.getId(), PageRequest.of(0, 1))
                .getContent().get(0).getId();

        assertThatThrownBy(() -> notificationService.markRead(applicantPrincipal, managersNotification))
                .isInstanceOf(BusinessException.class);

        // And it really is still unread for the person it belongs to.
        assertThat(notificationRepository.countByRecipientUserIdAndReadFalse(managerUser.getId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("marking all read clears the count in one go")
    void markAllReadClearsTheCount() {
        LocalDate day = nextWorkingDay();
        workFromHomeService.apply(applicantPrincipal,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));

        assertThat(notificationService.unreadCount(approverPrincipal)).isEqualTo(1);
        notificationService.markAllRead(approverPrincipal);
        assertThat(notificationService.unreadCount(approverPrincipal)).isZero();
    }

    @Test
    @DisplayName("an employee with no login is skipped rather than failing the request")
    void requestSucceedsWhenTheApproverHasNoAccount() {
        // The manager loses their account; the request must still go through.
        userRepository.delete(managerUser);

        LocalDate day = nextWorkingDay();
        var request = workFromHomeService.apply(applicantPrincipal,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));

        // The thing that actually matters happened; only the message was skipped.
        assertThat(request.id()).isNotNull();
        assertThat(notificationRepository.count()).isZero();
    }
}
