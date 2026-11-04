package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.meeting.MeetingCancelRequest;
import com.company.attendance.dto.request.meeting.MeetingCreateRequest;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.meeting.MeetingParticipantResponse;
import com.company.attendance.dto.response.meeting.MeetingResponse;
import com.company.attendance.dto.response.meeting.MeetingStatusResponse;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.entity.Meeting;
import com.company.attendance.entity.MeetingAudience;
import com.company.attendance.entity.MeetingJoinEvent;
import com.company.attendance.entity.MeetingParticipant;
import com.company.attendance.enums.AccessScope;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.MeetingAttendanceSource;
import com.company.attendance.enums.MeetingAttendanceStatus;
import com.company.attendance.enums.MeetingScopeType;
import com.company.attendance.enums.MeetingStatus;
import com.company.attendance.enums.NotificationType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.CompanySettingRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.LeaveRequestDayRepository;
import com.company.attendance.repository.MeetingAudienceRepository;
import com.company.attendance.repository.MeetingJoinEventRepository;
import com.company.attendance.repository.MeetingParticipantRepository;
import com.company.attendance.repository.MeetingRepository;
import com.company.attendance.security.AccessScopeResolver;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.CalendarService;
import com.company.attendance.service.HierarchyService;
import com.company.attendance.service.MeetingService;
import com.company.attendance.service.NotificationService;
import com.company.attendance.util.DateTimeUtils;
import com.company.attendance.util.MeetingUrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Morning meetings.
 *
 * <p>Worth knowing before changing anything here: the application cannot see
 * inside a Google Meet or a Zoom call. The only attendance signal is that
 * somebody opened our join link, which is a proxy and not proof. Everything
 * below is shaped to keep that distinction visible rather than quietly
 * presenting a click as presence.
 */
@Service
public class MeetingServiceImpl implements MeetingService {

    private static final Logger log = LoggerFactory.getLogger(MeetingServiceImpl.class);

    private static final String PERM_MANAGE_ALL = "MEETING_MANAGE_ALL";
    private static final String PERM_MANAGE_TEAM = "MEETING_MANAGE_TEAM";

    private static final String SETTING_LATE_AFTER = "meeting.late_after_minutes";
    private static final String SETTING_JOIN_GRACE = "meeting.join_grace_minutes";
    private static final String SETTING_CLOSE_GRACE = "meeting.auto_close_grace_minutes";

    private final MeetingRepository meetingRepository;
    private final MeetingAudienceRepository audienceRepository;
    private final MeetingParticipantRepository participantRepository;
    private final MeetingJoinEventRepository joinEventRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestDayRepository leaveRequestDayRepository;
    private final CompanySettingRepository companySettingRepository;
    private final CalendarService calendarService;
    private final HierarchyService hierarchyService;
    private final AccessScopeResolver accessScopeResolver;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    public MeetingServiceImpl(MeetingRepository meetingRepository,
                              MeetingAudienceRepository audienceRepository,
                              MeetingParticipantRepository participantRepository,
                              MeetingJoinEventRepository joinEventRepository,
                              EmployeeRepository employeeRepository,
                              LeaveRequestDayRepository leaveRequestDayRepository,
                              CompanySettingRepository companySettingRepository,
                              CalendarService calendarService,
                              HierarchyService hierarchyService,
                              AccessScopeResolver accessScopeResolver,
                              AuditLogService auditLogService,
                              NotificationService notificationService) {
        this.meetingRepository = meetingRepository;
        this.audienceRepository = audienceRepository;
        this.participantRepository = participantRepository;
        this.joinEventRepository = joinEventRepository;
        this.employeeRepository = employeeRepository;
        this.leaveRequestDayRepository = leaveRequestDayRepository;
        this.companySettingRepository = companySettingRepository;
        this.calendarService = calendarService;
        this.hierarchyService = hierarchyService;
        this.accessScopeResolver = accessScopeResolver;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
    }

    // ---------------------------------------------------------------- create

    @Override
    @Transactional
    public MeetingResponse create(UserPrincipal principal, MeetingCreateRequest request) {
        Employee host = requireEmployee(principal);
        validateWindow(request);

        Meeting meeting = new Meeting();
        meeting.setMeetingCode(nextMeetingCode());
        meeting.setHost(host);
        applyEditableFields(meeting, request);
        Meeting saved = meetingRepository.save(meeting);

        replaceAudience(saved, request.audience());

        auditLogService.record(AuditAction.CREATE, "Meeting", saved.getId(),
                "Created meeting " + saved.getMeetingCode() + " — " + saved.getTitle(),
                null, Map.of("code", saved.getMeetingCode(), "provider", saved.getProvider().name()));

        return toResponse(saved, principal);
    }

    @Override
    @Transactional
    public MeetingResponse update(UserPrincipal principal, Long meetingId, MeetingCreateRequest request) {
        Meeting meeting = requireManageable(principal, meetingId);
        if (meeting.getStatus() != MeetingStatus.DRAFT && meeting.getStatus() != MeetingStatus.SCHEDULED) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "A meeting can only be edited before it opens.");
        }
        validateWindow(request);
        applyEditableFields(meeting, request);
        Meeting saved = meetingRepository.save(meeting);

        // Re-resolving the audience of a published meeting would silently move
        // the goalposts for an attendance figure somebody may already have read.
        if (saved.getStatus() == MeetingStatus.DRAFT) {
            replaceAudience(saved, request.audience());
        }

        auditLogService.record(AuditAction.UPDATE, "Meeting", saved.getId(),
                "Updated meeting " + saved.getMeetingCode(), null,
                Map.of("title", saved.getTitle()));
        return toResponse(saved, principal);
    }

    // --------------------------------------------------------------- publish

    @Override
    @Transactional
    public MeetingResponse publish(UserPrincipal principal, Long meetingId) {
        Meeting meeting = requireManageable(principal, meetingId);
        if (meeting.getStatus() != MeetingStatus.DRAFT) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Only a draft meeting can be published.");
        }

        List<Employee> roster = resolveAudience(meeting);
        if (roster.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Nobody matches that audience, so there would be no one to invite.");
        }

        Set<Long> already = new HashSet<>(participantRepository.findEmployeeIds(meeting.getId()));
        for (Employee employee : roster) {
            if (already.contains(employee.getId())) {
                continue;
            }
            MeetingParticipant participant = new MeetingParticipant();
            participant.setMeeting(meeting);
            participant.setEmployee(employee);
            participant.setStatus(MeetingAttendanceStatus.INVITED);
            participantRepository.save(participant);
        }

        meeting.setStatus(MeetingStatus.SCHEDULED);
        Meeting saved = meetingRepository.save(meeting);

        auditLogService.record(AuditAction.MEETING_PUBLISH, "Meeting", saved.getId(),
                "Published " + saved.getMeetingCode() + " to " + roster.size() + " people",
                Map.of("status", MeetingStatus.DRAFT.name()),
                Map.of("status", MeetingStatus.SCHEDULED.name(), "invited", roster.size()));

        notifyRoster(saved, roster, NotificationType.MEETING_SCHEDULED,
                "Meeting scheduled",
                saved.getTitle() + " — " + formatWhen(saved, saved.getScheduledStartAt()));

        return toResponse(saved, principal);
    }

    // ------------------------------------------------------- start, end, cancel

    @Override
    @Transactional
    public MeetingResponse start(UserPrincipal principal, Long meetingId) {
        Meeting meeting = requireManageable(principal, meetingId);
        if (meeting.getStatus() != MeetingStatus.SCHEDULED) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Only a scheduled meeting can be started.");
        }
        meeting.setStatus(MeetingStatus.OPEN);
        meeting.setActualStartedAt(Instant.now());
        Meeting saved = meetingRepository.save(meeting);

        auditLogService.record(AuditAction.MEETING_START, "Meeting", saved.getId(),
                "Started " + saved.getMeetingCode(),
                Map.of("status", MeetingStatus.SCHEDULED.name()),
                Map.of("status", MeetingStatus.OPEN.name()));
        return toResponse(saved, principal);
    }

    @Override
    @Transactional
    public MeetingResponse end(UserPrincipal principal, Long meetingId) {
        Meeting meeting = requireManageable(principal, meetingId);
        if (meeting.getStatus() != MeetingStatus.OPEN && meeting.getStatus() != MeetingStatus.SCHEDULED) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "That meeting is not running.");
        }
        meeting.setActualEndedAt(Instant.now());
        meeting.setStatus(MeetingStatus.ENDED);
        Meeting saved = meetingRepository.save(meeting);

        finalise(saved);

        auditLogService.record(AuditAction.MEETING_END, "Meeting", saved.getId(),
                "Ended " + saved.getMeetingCode(),
                Map.of("status", MeetingStatus.OPEN.name()),
                Map.of("status", MeetingStatus.FINALISED.name()));
        return toResponse(saved, principal);
    }

    @Override
    @Transactional
    public MeetingResponse cancel(UserPrincipal principal, Long meetingId, MeetingCancelRequest request) {
        Meeting meeting = requireManageable(principal, meetingId);
        if (meeting.getStatus() != MeetingStatus.DRAFT && meeting.getStatus() != MeetingStatus.SCHEDULED) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "A meeting that has already run cannot be cancelled.");
        }
        meeting.setStatus(MeetingStatus.CANCELLED);
        meeting.setCancellationReason(request.reason());
        Meeting saved = meetingRepository.save(meeting);

        auditLogService.record(AuditAction.MEETING_CANCEL, "Meeting", saved.getId(),
                "Cancelled " + saved.getMeetingCode() + " — " + request.reason(),
                null, Map.of("status", MeetingStatus.CANCELLED.name()));

        List<Employee> roster = participantRepository.findRoster(saved.getId()).stream()
                .map(MeetingParticipant::getEmployee)
                .toList();
        notifyRoster(saved, roster, NotificationType.MEETING_CANCELLED,
                "Meeting cancelled", saved.getTitle() + " — " + request.reason());

        return toResponse(saved, principal);
    }

    // ------------------------------------------------------------------ join

    @Override
    @Transactional
    public String registerJoin(UserPrincipal principal, Long meetingId, String ipAddress, String userAgent) {
        Employee employee = requireEmployee(principal);
        Meeting meeting = requireMeeting(meetingId);

        MeetingParticipant participant = participantRepository
                .findByMeetingIdAndEmployeeId(meetingId, employee.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED,
                        "You are not on the list for this meeting."));

        assertJoinable(meeting);

        Instant now = Instant.now();

        MeetingJoinEvent event = new MeetingJoinEvent();
        event.setMeeting(meeting);
        event.setEmployee(employee);
        event.setClickedAt(now);
        event.setIpAddress(ipAddress);
        event.setUserAgent(truncate(userAgent, 300));
        joinEventRepository.save(event);

        // First click wins. A reconnect must never make an early arrival look late.
        if (participant.getFirstJoinedAt() == null) {
            participant.setFirstJoinedAt(now);
            participant.setAttendanceSource(MeetingAttendanceSource.LINK_CLICK);
            participant.setStatus(isLate(meeting, now)
                    ? MeetingAttendanceStatus.JOINED_LATE
                    : MeetingAttendanceStatus.JOINED);
            participant.setExcuseReason(null);
        }
        participant.setLastClickedAt(now);
        participant.setClickCount(participant.getClickCount() + 1);
        participantRepository.save(participant);

        // A scheduled meeting whose first attendee arrives before the host
        // pressed Start is open in every sense that matters.
        if (meeting.getStatus() == MeetingStatus.SCHEDULED) {
            meeting.setStatus(MeetingStatus.OPEN);
            meeting.setActualStartedAt(
                    meeting.getActualStartedAt() == null ? now : meeting.getActualStartedAt());
            meetingRepository.save(meeting);
        }

        auditLogService.record(AuditAction.MEETING_JOIN, "Meeting", meeting.getId(),
                employee.getEmployeeCode() + " opened the link for " + meeting.getMeetingCode());

        return meeting.getMeetingUrl();
    }

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional
    public MeetingResponse get(UserPrincipal principal, Long meetingId) {
        Meeting meeting = requireMeeting(meetingId);
        assertVisible(principal, meeting);
        finaliseIfOverdue(meeting);
        return toResponse(meeting, principal);
    }

    @Override
    @Transactional
    public MeetingStatusResponse status(UserPrincipal principal, Long meetingId) {
        Meeting meeting = requireMeeting(meetingId);
        assertVisible(principal, meeting);
        finaliseIfOverdue(meeting);

        int invited = participantRepository.findEmployeeIds(meetingId).size();
        long joined = participantRepository.countByMeetingIdAndStatusIn(meetingId,
                List.of(MeetingAttendanceStatus.JOINED, MeetingAttendanceStatus.JOINED_LATE));

        return new MeetingStatusResponse(
                meeting.getId(),
                meeting.getStatus().name(),
                Instant.now(),
                meeting.getScheduledStartAt(),
                meeting.getScheduledEndAt(),
                meeting.getActualStartedAt(),
                meeting.getActualEndedAt(),
                invited,
                (int) joined,
                canJoin(principal, meeting),
                canManage(principal, meeting) && meeting.getStatus() == MeetingStatus.SCHEDULED,
                canManage(principal, meeting)
                        && (meeting.getStatus() == MeetingStatus.OPEN
                            || meeting.getStatus() == MeetingStatus.SCHEDULED));
    }

    @Override
    @Transactional
    public PageResponse<MeetingResponse> myMeetings(UserPrincipal principal, Pageable pageable) {
        Employee employee = requireEmployee(principal);
        finaliseOverdueForReads();
        Page<Meeting> page = meetingRepository.findForParticipant(employee.getId(), pageable);
        return PageResponse.of(page, meeting -> toResponse(meeting, principal));
    }

    @Override
    @Transactional
    public PageResponse<MeetingResponse> manage(UserPrincipal principal, String status, Pageable pageable) {
        Employee actor = requireEmployee(principal);
        finaliseOverdueForReads();

        AccessScope scope = accessScopeResolver.resolve(principal, PERM_MANAGE_ALL, PERM_MANAGE_TEAM);
        boolean unrestricted = scope == AccessScope.ORG;

        Collection<Long> hostIds;
        if (unrestricted) {
            hostIds = Set.of(-1L);
        } else {
            Set<Long> ids = new HashSet<>(
                    hierarchyService.getSubordinateIds(actor.getId(), businessDate(actor)));
            ids.add(actor.getId());
            hostIds = ids;
        }

        MeetingStatus parsed = parseStatus(status);
        Page<Meeting> page = meetingRepository.findForHosts(unrestricted, hostIds, parsed, pageable);
        return PageResponse.of(page, meeting -> toResponse(meeting, principal));
    }

    @Override
    @Transactional
    public List<MeetingParticipantResponse> roster(UserPrincipal principal, Long meetingId) {
        Meeting meeting = requireMeeting(meetingId);
        if (!canManage(principal, meeting) && !principal.hasPermission("MEETING_REPORT_VIEW")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "You cannot see who attended this meeting.");
        }
        finaliseIfOverdue(meeting);

        return participantRepository.findRoster(meetingId).stream()
                .map(this::toParticipantResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String meetingUrlForEdit(UserPrincipal principal, Long meetingId) {
        Meeting meeting = requireManageable(principal, meetingId);
        return meeting.getMeetingUrl();
    }

    // ------------------------------------------------------------ finalisation

    /**
     * Gives every participant a terminal status.
     *
     * <p>Idempotent and guarded by {@code finalisedAt}, because it is reached
     * both from the host pressing End and from the lazy path below.
     */
    private void finalise(Meeting meeting) {
        if (meeting.getFinalisedAt() != null) {
            return;
        }

        List<MeetingParticipant> roster = participantRepository.findRoster(meeting.getId());
        if (roster.isEmpty()) {
            meeting.setFinalisedAt(Instant.now());
            meeting.setStatus(MeetingStatus.FINALISED);
            meetingRepository.save(meeting);
            return;
        }

        List<Employee> employees = roster.stream().map(MeetingParticipant::getEmployee).toList();
        LocalDate meetingDate = meetingDate(meeting, employees.get(0));

        // Batched, exactly as the dashboard does it: one call for holidays and
        // weekly offs, one for approved leave.
        Map<Long, DayType> dayTypes = calendarService.resolveDayTypes(employees, meetingDate);
        Set<Long> onLeave = leaveRequestDayRepository
                .findApprovedForEmployeesOn(employees.stream().map(Employee::getId).toList(), meetingDate)
                .stream()
                .map(LeaveRequestDay::getEmployeeId)
                .collect(Collectors.toSet());

        Instant endedAt = meeting.getActualEndedAt() != null
                ? meeting.getActualEndedAt()
                : meeting.getScheduledEndAt();

        for (MeetingParticipant participant : roster) {
            Employee employee = participant.getEmployee();

            if (participant.getFirstJoinedAt() != null) {
                int minutes = (int) Math.max(0,
                        Duration.between(participant.getFirstJoinedAt(), endedAt).toMinutes());
                participant.setAttendedMinutesEstimate(minutes);
                participantRepository.save(participant);
                continue;
            }

            String excuse = excuseFor(employee, meetingDate, dayTypes, onLeave);
            if (excuse != null) {
                participant.setStatus(MeetingAttendanceStatus.EXCUSED);
                participant.setExcuseReason(excuse);
            } else {
                participant.setStatus(MeetingAttendanceStatus.NOT_JOINED);
            }
            participantRepository.save(participant);
        }

        meeting.setFinalisedAt(Instant.now());
        meeting.setStatus(MeetingStatus.FINALISED);
        meetingRepository.save(meeting);
    }

    /**
     * Why this person is not counted as a no-show, or null if they are.
     *
     * <p>Somebody on sanctioned leave must never appear beside somebody who
     * simply did not turn up, and nobody is counted at all on a day outside
     * their employment.
     */
    private String excuseFor(Employee employee,
                             LocalDate date,
                             Map<Long, DayType> dayTypes,
                             Set<Long> onLeave) {
        if (!wasEmployedOn(employee, date)) {
            return "NOT_EMPLOYED";
        }
        if (onLeave.contains(employee.getId())) {
            return "ON_LEAVE";
        }
        DayType dayType = dayTypes.get(employee.getId());
        if (dayType == DayType.HOLIDAY) {
            return "HOLIDAY";
        }
        if (dayType == DayType.WEEKLY_OFF) {
            return "WEEKLY_OFF";
        }
        return null;
    }

    /** The guard that stops somebody being a no-show before they were hired. */
    private boolean wasEmployedOn(Employee employee, LocalDate date) {
        if (employee.getJoiningDate() != null && date.isBefore(employee.getJoiningDate())) {
            return false;
        }
        return employee.getExitDate() == null || !date.isAfter(employee.getExitDate());
    }

    /**
     * Closes a meeting the host forgot to end.
     *
     * <p>There is no scheduler in this application, so instead of a sweep the
     * meeting finalises itself the next time anybody reads it. That keeps the
     * reports from being blocked on a human remembering to press a button.
     */
    private void finaliseIfOverdue(Meeting meeting) {
        if (meeting.getStatus() != MeetingStatus.OPEN && meeting.getStatus() != MeetingStatus.SCHEDULED) {
            return;
        }
        Instant cutoff = meeting.getScheduledEndAt().plus(Duration.ofMinutes(setting(SETTING_CLOSE_GRACE, 30)));
        if (Instant.now().isBefore(cutoff)) {
            return;
        }
        if (meeting.getActualEndedAt() == null) {
            meeting.setActualEndedAt(meeting.getScheduledEndAt());
        }
        meeting.setStatus(MeetingStatus.ENDED);
        finalise(meeting);
        log.debug("Auto-closed meeting {} past its window", meeting.getMeetingCode());
    }

    private void finaliseOverdueForReads() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(setting(SETTING_CLOSE_GRACE, 30)));
        for (Meeting stale : meetingRepository.findStale(cutoff)) {
            finaliseIfOverdue(stale);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void applyEditableFields(Meeting meeting, MeetingCreateRequest request) {
        String url = MeetingUrlValidator.validate(request.meetingUrl());
        meeting.setTitle(request.title().trim());
        meeting.setAgenda(blankToNull(request.agenda()));
        meeting.setMeetingUrl(url);
        meeting.setProvider(MeetingUrlValidator.providerOf(url));
        meeting.setScheduledStartAt(request.scheduledStartAt());
        meeting.setScheduledEndAt(request.scheduledEndAt());
        meeting.setLateAfterMinutes(request.lateAfterMinutes());
    }

    private void validateWindow(MeetingCreateRequest request) {
        if (!request.scheduledEndAt().isAfter(request.scheduledStartAt())) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "The meeting has to end after it starts.");
        }
        if (Duration.between(request.scheduledStartAt(), request.scheduledEndAt()).toHours() > 24) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "A meeting longer than a day is almost certainly a mistake.");
        }
    }

    private void replaceAudience(Meeting meeting, List<MeetingCreateRequest.AudienceScope> scopes) {
        audienceRepository.deleteByMeetingId(meeting.getId());
        List<MeetingCreateRequest.AudienceScope> requested =
                (scopes == null || scopes.isEmpty())
                        ? List.of(new MeetingCreateRequest.AudienceScope(MeetingScopeType.TEAM.name(), null))
                        : scopes;

        Set<String> seen = new LinkedHashSet<>();
        for (MeetingCreateRequest.AudienceScope scope : requested) {
            MeetingScopeType type = parseScopeType(scope.scopeType());
            String key = type.name() + ":" + scope.scopeId();
            if (!seen.add(key)) {
                continue;
            }
            MeetingAudience row = new MeetingAudience();
            row.setMeeting(meeting);
            row.setScopeType(type);
            row.setScopeId(type == MeetingScopeType.GLOBAL || type == MeetingScopeType.TEAM
                    ? null : scope.scopeId());
            audienceRepository.save(row);
        }
    }

    /** Turns the audience rules into concrete people, once, at publish. */
    private List<Employee> resolveAudience(Meeting meeting) {
        List<MeetingAudience> scopes = audienceRepository.findByMeetingId(meeting.getId());
        LocalDate onDate = meetingDate(meeting, meeting.getHost());

        Set<Long> ids = new LinkedHashSet<>();
        List<Employee> all = null;

        for (MeetingAudience scope : scopes) {
            switch (scope.getScopeType()) {
                case GLOBAL -> {
                    all = all == null ? employeeRepository.findAll() : all;
                    all.stream().filter(Employee::isActive).map(Employee::getId).forEach(ids::add);
                }
                case TEAM -> {
                    ids.addAll(hierarchyService.getSubordinateIds(meeting.getHost().getId(), onDate));
                    ids.add(meeting.getHost().getId());
                }
                case DEPARTMENT -> {
                    all = all == null ? employeeRepository.findAll() : all;
                    all.stream()
                            .filter(Employee::isActive)
                            .filter(e -> e.getDepartment() != null
                                    && e.getDepartment().getId().equals(scope.getScopeId()))
                            .map(Employee::getId)
                            .forEach(ids::add);
                }
                case OFFICE -> {
                    all = all == null ? employeeRepository.findAll() : all;
                    all.stream()
                            .filter(Employee::isActive)
                            .filter(e -> e.getOffice() != null
                                    && e.getOffice().getId().equals(scope.getScopeId()))
                            .map(Employee::getId)
                            .forEach(ids::add);
                }
                case EMPLOYEE -> {
                    if (scope.getScopeId() != null) {
                        ids.add(scope.getScopeId());
                    }
                }
                default -> throw new IllegalStateException("Unhandled scope " + scope.getScopeType());
            }
        }

        if (ids.isEmpty()) {
            return List.of();
        }
        return employeeRepository.findAllById(ids).stream()
                .filter(Employee::isActive)
                .filter(e -> wasEmployedOn(e, onDate))
                .sorted(Comparator.comparing(Employee::getEmployeeCode))
                .toList();
    }

    private void assertJoinable(Meeting meeting) {
        if (meeting.getStatus() == MeetingStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION, "That meeting was cancelled.");
        }
        if (meeting.getStatus() == MeetingStatus.DRAFT) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "That meeting has not been published yet.");
        }
        if (meeting.getStatus() == MeetingStatus.ENDED || meeting.getStatus() == MeetingStatus.FINALISED) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION, "That meeting has finished.");
        }

        Duration grace = Duration.ofMinutes(setting(SETTING_JOIN_GRACE, 15));
        Instant now = Instant.now();
        if (now.isBefore(meeting.getScheduledStartAt().minus(grace))) {
            throw new BusinessException(ErrorCode.OUTSIDE_PUNCH_WINDOW,
                    "That meeting has not opened yet.");
        }
        if (now.isAfter(meeting.getScheduledEndAt().plus(grace))) {
            throw new BusinessException(ErrorCode.OUTSIDE_PUNCH_WINDOW,
                    "That meeting's window has closed.");
        }
    }

    private boolean isLate(Meeting meeting, Instant at) {
        short threshold = meeting.getLateAfterMinutes() != null
                ? meeting.getLateAfterMinutes()
                : (short) setting(SETTING_LATE_AFTER, 5);
        Instant reference = meeting.getActualStartedAt() != null
                ? meeting.getActualStartedAt()
                : meeting.getScheduledStartAt();
        return at.isAfter(reference.plus(Duration.ofMinutes(threshold)));
    }

    private Meeting requireMeeting(Long meetingId) {
        return meetingRepository.findById(meetingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "That meeting does not exist."));
    }

    private Meeting requireManageable(UserPrincipal principal, Long meetingId) {
        Meeting meeting = requireMeeting(meetingId);
        if (!canManage(principal, meeting)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "You cannot manage this meeting.");
        }
        return meeting;
    }

    private boolean canManage(UserPrincipal principal, Meeting meeting) {
        if (principal.hasPermission(PERM_MANAGE_ALL)) {
            return true;
        }
        if (!principal.hasPermission(PERM_MANAGE_TEAM) || principal.getEmployeeId() == null) {
            return false;
        }
        Long hostId = meeting.getHost().getId();
        if (hostId.equals(principal.getEmployeeId())) {
            return true;
        }
        return hierarchyService.managesEmployee(principal.getEmployeeId(), hostId, LocalDate.now());
    }

    private boolean canJoin(UserPrincipal principal, Meeting meeting) {
        if (principal.getEmployeeId() == null) {
            return false;
        }
        if (!participantRepository.existsByMeetingIdAndEmployeeId(
                meeting.getId(), principal.getEmployeeId())) {
            return false;
        }
        try {
            assertJoinable(meeting);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    private void assertVisible(UserPrincipal principal, Meeting meeting) {
        if (canManage(principal, meeting)) {
            return;
        }
        if (principal.getEmployeeId() != null
                && participantRepository.existsByMeetingIdAndEmployeeId(
                        meeting.getId(), principal.getEmployeeId())) {
            return;
        }
        throw new BusinessException(ErrorCode.ACCESS_DENIED,
                "You were not invited to that meeting.");
    }

    private Employee requireEmployee(UserPrincipal principal) {
        if (principal.getEmployeeId() == null) {
            throw new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                    "This account is not linked to an employee record.");
        }
        return employeeRepository.findById(principal.getEmployeeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Employee not found"));
    }

    /**
     * The calendar date a meeting belongs to, in the office's own timezone.
     *
     * <p>The JVM runs on UTC, so a bare LocalDate.now() would put a 09:30 IST
     * morning meeting on the previous day and look up the wrong holidays.
     */
    private LocalDate meetingDate(Meeting meeting, Employee reference) {
        ZoneId zone = DateTimeUtils.zoneOf(reference);
        return DateTimeUtils.localDate(meeting.getScheduledStartAt(), zone);
    }

    private LocalDate businessDate(Employee employee) {
        return DateTimeUtils.localDate(Instant.now(), DateTimeUtils.zoneOf(employee));
    }

    private String formatWhen(Meeting meeting, Instant at) {
        ZoneId zone = DateTimeUtils.zoneOf(meeting.getHost());
        return DateTimeUtils.localDate(at, zone) + " " + DateTimeUtils.localTime(at, zone);
    }

    private void notifyRoster(Meeting meeting,
                              List<Employee> roster,
                              NotificationType type,
                              String title,
                              String body) {
        for (Employee employee : roster) {
            notificationService.notifyEmployee(employee.getId(), type, title, body,
                    "/meetings/" + meeting.getId(), "Meeting", meeting.getId(), null, null);
        }
    }

    private int setting(String key, int fallback) {
        return companySettingRepository.findBySettingKey(key)
                .map(s -> {
                    try {
                        return Integer.parseInt(s.getSettingValue().trim());
                    } catch (NumberFormatException e) {
                        log.warn("Setting {} is not a number: {}", key, s.getSettingValue());
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private MeetingStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MeetingStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Unknown meeting status '" + raw + "'.");
        }
    }

    private MeetingScopeType parseScopeType(String raw) {
        try {
            return MeetingScopeType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Unknown audience type '" + raw + "'.");
        }
    }

    private String nextMeetingCode() {
        int year = LocalDate.now().getYear();
        long sequence = meetingRepository.count() + 1;
        String candidate = String.format("MTG-%d-%06d", year, sequence);
        while (meetingRepository.existsByMeetingCode(candidate)) {
            sequence++;
            candidate = String.format("MTG-%d-%06d", year, sequence);
        }
        return candidate;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ---------------------------------------------------------------- mapping

    private MeetingResponse toResponse(Meeting meeting, UserPrincipal principal) {
        List<MeetingParticipant> roster = participantRepository.findRoster(meeting.getId());
        long joined = roster.stream()
                .filter(p -> p.getStatus() == MeetingAttendanceStatus.JOINED
                        || p.getStatus() == MeetingAttendanceStatus.JOINED_LATE)
                .count();

        Optional<MeetingParticipant> mine = principal.getEmployeeId() == null
                ? Optional.empty()
                : roster.stream()
                        .filter(p -> p.getEmployee().getId().equals(principal.getEmployeeId()))
                        .findFirst();

        boolean manage = canManage(principal, meeting);
        boolean editable = meeting.getStatus() == MeetingStatus.DRAFT
                || meeting.getStatus() == MeetingStatus.SCHEDULED;

        List<MeetingResponse.AudienceScope> audience = audienceRepository
                .findByMeetingId(meeting.getId()).stream()
                .map(a -> new MeetingResponse.AudienceScope(
                        a.getScopeType().name(), a.getScopeId(), labelFor(a)))
                .toList();

        return new MeetingResponse(
                meeting.getId(),
                meeting.getMeetingCode(),
                meeting.getTitle(),
                meeting.getAgenda(),
                meeting.getProvider().name(),
                meeting.getHost().getId(),
                meeting.getHost().getFullName(),
                meeting.getScheduledStartAt(),
                meeting.getScheduledEndAt(),
                meeting.getActualStartedAt(),
                meeting.getActualEndedAt(),
                meeting.getStatus().name(),
                meeting.getLateAfterMinutes(),
                meeting.getCancellationReason(),
                roster.size(),
                (int) joined,
                canJoin(principal, meeting),
                manage && meeting.getStatus() == MeetingStatus.SCHEDULED,
                manage && (meeting.getStatus() == MeetingStatus.OPEN
                        || meeting.getStatus() == MeetingStatus.SCHEDULED),
                manage && editable,
                manage && editable,
                mine.map(p -> p.getStatus().name()).orElse(null),
                mine.map(MeetingParticipant::getFirstJoinedAt).orElse(null),
                audience);
    }

    private String labelFor(MeetingAudience audience) {
        return switch (audience.getScopeType()) {
            case GLOBAL -> "Everyone";
            case TEAM -> "The host's team";
            case DEPARTMENT -> "Department #" + audience.getScopeId();
            case OFFICE -> "Office #" + audience.getScopeId();
            case EMPLOYEE -> "One person";
        };
    }

    private MeetingParticipantResponse toParticipantResponse(MeetingParticipant participant) {
        Employee employee = participant.getEmployee();
        return new MeetingParticipantResponse(
                participant.getId(),
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                employee.getDepartment() != null ? employee.getDepartment().getName() : null,
                participant.getStatus().name(),
                participant.getFirstJoinedAt(),
                participant.getClickCount(),
                participant.getAttendedMinutesEstimate(),
                participant.getAttendanceSource() != null
                        ? participant.getAttendanceSource().name() : null,
                participant.getExcuseReason());
    }
}
