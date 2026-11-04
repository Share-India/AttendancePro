package com.company.attendance.integration;

import com.company.attendance.dto.request.meeting.MeetingCancelRequest;
import com.company.attendance.dto.request.meeting.MeetingCreateRequest;
import com.company.attendance.dto.response.meeting.MeetingParticipantResponse;
import com.company.attendance.dto.response.meeting.MeetingResponse;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeManager;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.Holiday;
import com.company.attendance.entity.LeaveRequest;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.entity.Meeting;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.User;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.MeetingStatus;
import com.company.attendance.enums.RelationshipType;
import com.company.attendance.enums.RequestStatus;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.EmployeeManagerRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.HolidayRepository;
import com.company.attendance.repository.LeaveRequestDayRepository;
import com.company.attendance.repository.LeaveRequestRepository;
import com.company.attendance.repository.LeaveTypeRepository;
import com.company.attendance.repository.MeetingJoinEventRepository;
import com.company.attendance.repository.MeetingParticipantRepository;
import com.company.attendance.repository.MeetingRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.MeetingService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The meeting module records one thing honestly: that somebody opened a link.
 * These tests exist to keep that claim narrow and the derived figures sound —
 * the roster must be frozen when it is published, a reconnect must not make an
 * early arrival look late, a person on sanctioned leave must never sit beside
 * a genuine no-show, and a meeting nobody closed must still produce numbers.
 */
@SpringBootTest
@ActiveProfiles("test")
class MeetingFlowIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired MeetingService meetingService;
    @Autowired MeetingRepository meetingRepository;
    @Autowired MeetingParticipantRepository participantRepository;
    @Autowired MeetingJoinEventRepository joinEventRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeManagerRepository employeeManagerRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired UserRepository userRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired WeeklyOffRepository weeklyOffRepository;
    @Autowired HolidayRepository holidayRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeaveRequestRepository leaveRequestRepository;
    @Autowired LeaveRequestDayRepository leaveRequestDayRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Office office;
    private Employee host;
    private Employee alice;
    private Employee bob;
    private Employee outsider;

    private UserPrincipal hostPrincipal;
    private UserPrincipal alicePrincipal;
    private UserPrincipal bobPrincipal;
    private UserPrincipal outsiderPrincipal;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        office = new Office();
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

        host = employee(shift, "EMP9001", "Priya", "Sharma");
        alice = employee(shift, "EMP9002", "Alice", "Rao");
        bob = employee(shift, "EMP9003", "Bob", "Nair");
        outsider = employee(shift, "EMP9004", "Zane", "Kumar");

        reportsTo(alice, host);
        reportsTo(bob, host);

        hostPrincipal = principal(host, "EMP9001",
                Set.of("MEETING_MANAGE_TEAM", "MEETING_REPORT_VIEW", "MEETING_VIEW_SELF", "MEETING_JOIN"));
        alicePrincipal = principal(alice, "EMP9002", Set.of("MEETING_VIEW_SELF", "MEETING_JOIN"));
        bobPrincipal = principal(bob, "EMP9003", Set.of("MEETING_VIEW_SELF", "MEETING_JOIN"));
        outsiderPrincipal = principal(outsider, "EMP9004", Set.of("MEETING_VIEW_SELF", "MEETING_JOIN"));

        // Deliberately no weekly offs here.
        //
        // Seeding Saturday and Sunday globally made every meeting in this class
        // fall on a weekly off whenever the suite ran at a weekend, so everyone
        // who did not join came back EXCUSED instead of NOT_JOINED — and two
        // tests failed on Saturdays only. No test needed the seed; the one that
        // exercises the weekly-off excuse now seeds it for its own day.
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("publishing freezes the roster, and somebody hired afterwards is not on it")
    void publishSnapshotsTheRoster() {
        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(5), inMinutes(35)));
        meetingService.publish(hostPrincipal, meeting.id());

        assertThat(participantRepository.findEmployeeIds(meeting.id()))
                .containsExactlyInAnyOrder(host.getId(), alice.getId(), bob.getId());

        // A new joiner after publish must not appear retrospectively.
        Employee latecomer = employee(shiftOf(), "EMP9005", "New", "Starter");
        reportsTo(latecomer, host);

        assertThat(participantRepository.findEmployeeIds(meeting.id()))
                .doesNotContain(latecomer.getId())
                .hasSize(3);
    }

    @Test
    @DisplayName("a meeting that has already run cannot be cancelled")
    void cancelOnlyBeforeItRuns() {
        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(5), inMinutes(35)));
        meetingService.publish(hostPrincipal, meeting.id());
        meetingService.start(hostPrincipal, meeting.id());

        assertThatThrownBy(() -> meetingService.cancel(hostPrincipal, meeting.id(),
                new MeetingCancelRequest("Clashes with the board call")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already run");
    }

    // ------------------------------------------------------------------ join

    @Test
    @DisplayName("somebody who was not invited is refused, not redirected")
    void nonInviteeCannotJoin() {
        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(-5), inMinutes(25)));
        meetingService.publish(hostPrincipal, meeting.id());

        assertThatThrownBy(() ->
                meetingService.registerJoin(outsiderPrincipal, meeting.id(), "127.0.0.1", "test"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not on the list");

        assertThat(joinEventRepository.findByMeeting(meeting.id())).isEmpty();
    }

    @Test
    @DisplayName("clicking again does not move the arrival time, but every click is recorded")
    void repeatedClicksKeepTheFirstArrival() {
        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(-5), inMinutes(25)));
        meetingService.publish(hostPrincipal, meeting.id());

        meetingService.registerJoin(alicePrincipal, meeting.id(), "127.0.0.1", "first");
        Instant firstSeen = participantRepository
                .findByMeetingIdAndEmployeeId(meeting.id(), alice.getId())
                .orElseThrow().getFirstJoinedAt();

        meetingService.registerJoin(alicePrincipal, meeting.id(), "127.0.0.1", "reconnect");
        meetingService.registerJoin(alicePrincipal, meeting.id(), "127.0.0.1", "reconnect again");

        var row = participantRepository
                .findByMeetingIdAndEmployeeId(meeting.id(), alice.getId()).orElseThrow();

        assertThat(row.getFirstJoinedAt()).isEqualTo(firstSeen);
        assertThat(row.getClickCount()).isEqualTo(3);
        assertThat(joinEventRepository.countByMeetingIdAndEmployeeId(meeting.id(), alice.getId()))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the join link does not work once the window has closed")
    void joiningOutsideTheWindowIsRefused() {
        MeetingResponse meeting = meetingService.create(hostPrincipal,
                request(inMinutes(-600), inMinutes(-570)));
        meetingService.publish(hostPrincipal, meeting.id());

        assertThatThrownBy(() ->
                meetingService.registerJoin(alicePrincipal, meeting.id(), "127.0.0.1", "late"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a link that is not https is refused when the meeting is created")
    void insecureLinkIsRejected() {
        assertThatThrownBy(() -> meetingService.create(hostPrincipal,
                new MeetingCreateRequest("Standup", null, "http://meet.google.com/abc-defg-hij",
                        inMinutes(5), inMinutes(35), null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("a link with a username before the @ is refused")
    void credentialedLinkIsRejected() {
        assertThatThrownBy(() -> meetingService.create(hostPrincipal,
                new MeetingCreateRequest("Standup", null,
                        "https://evil.example.com@meet.google.com/abc", inMinutes(5), inMinutes(35),
                        null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("@");
    }

    // ---------------------------------------------------------- finalisation

    @Test
    @DisplayName("ending the meeting separates who joined from who did not")
    void endingDerivesAttendance() {
        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(-1), inMinutes(25)));
        meetingService.publish(hostPrincipal, meeting.id());
        meetingService.registerJoin(alicePrincipal, meeting.id(), "127.0.0.1", "test");
        meetingService.end(hostPrincipal, meeting.id());

        List<MeetingParticipantResponse> roster = meetingService.roster(hostPrincipal, meeting.id());

        assertThat(statusOf(roster, alice)).isEqualTo("JOINED");
        assertThat(statusOf(roster, bob)).isEqualTo("NOT_JOINED");
        assertThat(meetingRepository.findById(meeting.id()).orElseThrow().getStatus())
                .isEqualTo(MeetingStatus.FINALISED);
    }

    @Test
    @DisplayName("the lateness threshold decides JOINED from JOINED_LATE, and is configurable")
    void latenessBoundary() {
        // Inside the five-minute default: on time.
        MeetingResponse onTime = meetingService.create(hostPrincipal,
                request(inMinutes(-2), inMinutes(28)));
        meetingService.publish(hostPrincipal, onTime.id());
        meetingService.registerJoin(alicePrincipal, onTime.id(), "127.0.0.1", "test");
        assertThat(participantRepository
                .findByMeetingIdAndEmployeeId(onTime.id(), alice.getId())
                .orElseThrow().getStatus().name()).isEqualTo("JOINED");

        // Past it: late.
        MeetingResponse late = meetingService.create(hostPrincipal,
                request(inMinutes(-10), inMinutes(20)));
        meetingService.publish(hostPrincipal, late.id());
        meetingService.registerJoin(alicePrincipal, late.id(), "127.0.0.1", "test");
        assertThat(participantRepository
                .findByMeetingIdAndEmployeeId(late.id(), alice.getId())
                .orElseThrow().getStatus().name()).isEqualTo("JOINED_LATE");

        // A per-meeting override widens it, so the same arrival is on time.
        MeetingResponse lenient = meetingService.create(hostPrincipal,
                new MeetingCreateRequest("Standup", null, "https://meet.google.com/abc-defg-hij",
                        inMinutes(-10), inMinutes(20), (short) 30, null));
        meetingService.publish(hostPrincipal, lenient.id());
        meetingService.registerJoin(alicePrincipal, lenient.id(), "127.0.0.1", "test");
        assertThat(participantRepository
                .findByMeetingIdAndEmployeeId(lenient.id(), alice.getId())
                .orElseThrow().getStatus().name()).isEqualTo("JOINED");
    }

    @Test
    @DisplayName("somebody on approved leave is excused, not marked a no-show")
    void approvedLeaveIsExcused() {
        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(-5), inMinutes(25)));
        meetingService.publish(hostPrincipal, meeting.id());

        approveLeaveFor(bob, today());

        meetingService.end(hostPrincipal, meeting.id());
        List<MeetingParticipantResponse> roster = meetingService.roster(hostPrincipal, meeting.id());

        assertThat(statusOf(roster, bob)).isEqualTo("EXCUSED");
        assertThat(roster.stream()
                .filter(r -> r.employeeId().equals(bob.getId()))
                .findFirst().orElseThrow().excuseReason())
                .isEqualTo("ON_LEAVE");
    }

    @Test
    @DisplayName("a weekly off excuses everyone who did not join")
    void weeklyOffIsExcused() {
        // Seeded for today's own day of the week, so this holds whichever day
        // the suite runs rather than only at a weekend.
        weeklyOffRepository.save(weeklyOff(today().getDayOfWeek()));

        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(-5), inMinutes(25)));
        meetingService.publish(hostPrincipal, meeting.id());
        meetingService.end(hostPrincipal, meeting.id());

        List<MeetingParticipantResponse> roster = meetingService.roster(hostPrincipal, meeting.id());
        assertThat(statusOf(roster, bob)).isEqualTo("EXCUSED");
        assertThat(roster.stream()
                .filter(r -> r.employeeId().equals(bob.getId()))
                .findFirst().orElseThrow().excuseReason())
                .isEqualTo("WEEKLY_OFF");
    }

    @Test
    @DisplayName("a holiday excuses everyone who did not join")
    void holidayIsExcused() {
        holidayRepository.save(holiday(today()));

        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(-5), inMinutes(25)));
        meetingService.publish(hostPrincipal, meeting.id());
        meetingService.end(hostPrincipal, meeting.id());

        List<MeetingParticipantResponse> roster = meetingService.roster(hostPrincipal, meeting.id());
        assertThat(statusOf(roster, bob)).isEqualTo("EXCUSED");
        assertThat(roster.stream()
                .filter(r -> r.employeeId().equals(bob.getId()))
                .findFirst().orElseThrow().excuseReason())
                .isEqualTo("HOLIDAY");
    }

    @Test
    @DisplayName("somebody who had not joined the company yet is excused")
    void notYetEmployedIsExcused() {
        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(-5), inMinutes(25)));
        meetingService.publish(hostPrincipal, meeting.id());

        // Backdate the meeting to before Bob existed, which is the shape of a
        // report run over a range that predates somebody's joining date.
        bob.setJoiningDate(LocalDate.now().plusDays(30));
        employeeRepository.save(bob);

        meetingService.end(hostPrincipal, meeting.id());
        List<MeetingParticipantResponse> roster = meetingService.roster(hostPrincipal, meeting.id());

        assertThat(roster.stream()
                .filter(r -> r.employeeId().equals(bob.getId()))
                .findFirst().orElseThrow().excuseReason())
                .isEqualTo("NOT_EMPLOYED");
    }

    @Test
    @DisplayName("a meeting nobody closed finalises itself the next time it is read")
    void lazyFinalisationClosesAForgottenMeeting() {
        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(-5), inMinutes(25)));
        meetingService.publish(hostPrincipal, meeting.id());
        meetingService.registerJoin(alicePrincipal, meeting.id(), "127.0.0.1", "test");

        // The host goes home without pressing End, and the window lapses.
        Meeting row = meetingRepository.findById(meeting.id()).orElseThrow();
        row.setScheduledStartAt(Instant.now().minus(Duration.ofHours(5)));
        row.setScheduledEndAt(Instant.now().minus(Duration.ofHours(4)));
        meetingRepository.save(row);

        MeetingResponse reread = meetingService.get(hostPrincipal, meeting.id());

        assertThat(reread.status()).isEqualTo("FINALISED");
        assertThat(statusOf(meetingService.roster(hostPrincipal, meeting.id()), bob))
                .isEqualTo("NOT_JOINED");
    }

    @Test
    @DisplayName("finalising twice does not change what was already decided")
    void finalisationIsIdempotent() {
        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(-5), inMinutes(25)));
        meetingService.publish(hostPrincipal, meeting.id());
        meetingService.registerJoin(alicePrincipal, meeting.id(), "127.0.0.1", "test");
        meetingService.end(hostPrincipal, meeting.id());

        Instant firstFinalisedAt = meetingRepository.findById(meeting.id())
                .orElseThrow().getFinalisedAt();
        List<MeetingParticipantResponse> first = meetingService.roster(hostPrincipal, meeting.id());

        meetingService.get(hostPrincipal, meeting.id());
        meetingService.roster(hostPrincipal, meeting.id());

        assertThat(meetingRepository.findById(meeting.id()).orElseThrow().getFinalisedAt())
                .isEqualTo(firstFinalisedAt);
        assertThat(meetingService.roster(hostPrincipal, meeting.id()))
                .usingRecursiveComparison().isEqualTo(first);
    }

    // ----------------------------------------------------------------- scope

    @Test
    @DisplayName("an employee cannot read a meeting they were not invited to")
    void outsiderCannotReadTheMeeting() {
        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(5), inMinutes(35)));
        meetingService.publish(hostPrincipal, meeting.id());

        assertThatThrownBy(() -> meetingService.get(outsiderPrincipal, meeting.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not invited");
    }

    @Test
    @DisplayName("an invitee cannot see the roster, only the host and reporting roles can")
    void inviteeCannotSeeTheRoster() {
        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(5), inMinutes(35)));
        meetingService.publish(hostPrincipal, meeting.id());

        assertThatThrownBy(() -> meetingService.roster(alicePrincipal, meeting.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot see");
    }

    @Test
    @DisplayName("an invitee cannot manage somebody else's meeting")
    void inviteeCannotManage() {
        MeetingResponse meeting = meetingService.create(hostPrincipal, request(inMinutes(5), inMinutes(35)));
        meetingService.publish(hostPrincipal, meeting.id());

        assertThatThrownBy(() -> meetingService.end(bobPrincipal, meeting.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot manage");
    }

    // ----------------------------------------------------------------- setup

    private MeetingCreateRequest request(Instant start, Instant end) {
        return new MeetingCreateRequest("Morning standup", "Yesterday, today, blockers",
                "https://meet.google.com/abc-defg-hij", start, end, null, null);
    }

    private static Instant inMinutes(long minutes) {
        return Instant.now().plus(Duration.ofMinutes(minutes));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(Instant.now(), ZONE);
    }

    private String statusOf(List<MeetingParticipantResponse> roster, Employee employee) {
        return roster.stream()
                .filter(r -> r.employeeId().equals(employee.getId()))
                .findFirst().orElseThrow()
                .status();
    }

    private Shift shiftOf() {
        return shiftRepository.findAll().get(0);
    }

    private Employee employee(Shift shift, String code, String first, String last) {
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

    private void reportsTo(Employee employee, Employee manager) {
        EmployeeManager link = new EmployeeManager();
        link.setEmployee(employee);
        link.setManager(manager);
        link.setRelationshipType(RelationshipType.PRIMARY);
        link.setEffectiveFrom(LocalDate.now().minusYears(1));
        employeeManagerRepository.save(link);
    }

    private UserPrincipal principal(Employee employee, String username, Set<String> permissions) {
        User account = new User();
        account.setUsername(username);
        account.setEmail(employee.getOfficialEmail());
        account.setPasswordHash("{noop}irrelevant");
        account.setEmployee(employee);
        account = userRepository.save(account);

        return UserPrincipal.fromClaims(account.getId(), employee.getId(), username,
                Set.of("EMPLOYEE"), permissions);
    }

    private WeeklyOff weeklyOff(DayOfWeek day) {
        WeeklyOff rule = new WeeklyOff();
        rule.setScopeType("GLOBAL");
        rule.setDayOfWeek((byte) day.getValue());
        return rule;
    }

    private Holiday holiday(LocalDate date) {
        Holiday entry = new Holiday();
        entry.setName("Test Holiday");
        entry.setHolidayDate(date);
        entry.setHolidayType("COMPANY");
        entry.setHolidayYear((short) date.getYear());
        return entry;
    }

    private void approveLeaveFor(Employee employee, LocalDate date) {
        LeaveType type = new LeaveType();
        type.setCode("CL");
        type.setName("Casual Leave");
        type = leaveTypeRepository.save(type);

        LeaveRequest request = new LeaveRequest();
        request.setRequestNumber("LV-TEST-" + employee.getEmployeeCode());
        request.setEmployee(employee);
        request.setLeaveType(type);
        request.setFromDate(date);
        request.setToDate(date);
        request.setTotalDays(BigDecimal.ONE);
        request.setReason("Test");
        request.setStatus(RequestStatus.APPROVED);
        request = leaveRequestRepository.save(request);

        LeaveRequestDay day = new LeaveRequestDay();
        day.setLeaveRequest(request);
        day.setEmployeeId(employee.getId());
        day.setLeaveDate(date);
        day.setDeductible(true);
        leaveRequestDayRepository.save(day);
    }
}
