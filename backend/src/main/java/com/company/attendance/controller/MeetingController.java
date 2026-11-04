package com.company.attendance.controller;

import com.company.attendance.dto.request.meeting.MeetingCancelRequest;
import com.company.attendance.dto.request.meeting.MeetingCreateRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.meeting.MeetingParticipantResponse;
import com.company.attendance.dto.response.meeting.MeetingResponse;
import com.company.attendance.dto.response.meeting.MeetingStatusResponse;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.MeetingService;
import com.company.attendance.util.IpAddressUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/meetings")
@Tag(name = "Meetings", description = "Morning meetings and who joined them")
public class MeetingController {

    private final MeetingService meetingService;

    public MeetingController(MeetingService meetingService) {
        this.meetingService = meetingService;
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('MEETING_VIEW_SELF')")
    @Operation(summary = "Meetings I have been invited to")
    public ResponseEntity<ApiResponse<PageResponse<MeetingResponse>>> myMeetings(
            @CurrentUser UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                meetingService.myMeetings(principal, pageable)));
    }

    @GetMapping("/manage")
    @PreAuthorize("hasAnyAuthority('MEETING_MANAGE_TEAM', 'MEETING_MANAGE_ALL')")
    @Operation(summary = "Meetings I can run")
    public ResponseEntity<ApiResponse<PageResponse<MeetingResponse>>> manage(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "scheduledStartAt",
                    direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                meetingService.manage(principal, status, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MEETING_VIEW_SELF')")
    @Operation(summary = "One meeting")
    public ResponseEntity<ApiResponse<MeetingResponse>> get(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(meetingService.get(principal, id)));
    }

    @GetMapping("/{id}/status")
    @PreAuthorize("hasAuthority('MEETING_VIEW_SELF')")
    @Operation(summary = "Live status, polled while a meeting is running")
    public ResponseEntity<ApiResponse<MeetingStatusResponse>> status(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(meetingService.status(principal, id)));
    }

    /**
     * Records that this person opened the meeting and hands back the link.
     *
     * <p>A POST, and emphatically not a GET returning a redirect. Two reasons,
     * both of which bite in practice:
     *
     * <ul>
     *   <li>This has a side effect. Browsers prefetch links, chat clients
     *       unfurl them, and mail scanners fetch them to check for malware.
     *       Any of those issuing a speculative GET would record somebody as
     *       having joined a meeting they never opened.</li>
     *   <li>Authentication here is a bearer token held in memory by the SPA,
     *       not a cookie, so a plain link in a new tab would arrive
     *       unauthenticated anyway.</li>
     * </ul>
     *
     * <p>The URL is returned only after the click has been recorded, which is
     * why it is safe to send here and nowhere else.
     */
    @PostMapping("/{id}/join")
    @PreAuthorize("hasAuthority('MEETING_JOIN')")
    @Operation(summary = "Record attendance and return the meeting link")
    public ResponseEntity<ApiResponse<Map<String, String>>> join(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            HttpServletRequest request) {
        String target = meetingService.registerJoin(principal, id,
                IpAddressUtils.resolve(request), IpAddressUtils.userAgent(request));
        return ResponseEntity.ok(ApiResponse.success(Map.of("meetingUrl", target)));
    }

    @GetMapping("/{id}/participants")
    @PreAuthorize("hasAnyAuthority('MEETING_REPORT_VIEW', 'MEETING_MANAGE_TEAM', 'MEETING_MANAGE_ALL')")
    @Operation(summary = "Who was invited, and who opened the link")
    public ResponseEntity<ApiResponse<List<MeetingParticipantResponse>>> participants(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(meetingService.roster(principal, id)));
    }

    /** The raw link, for the edit form only. Everyone else joins through /join. */
    @GetMapping("/{id}/link")
    @PreAuthorize("hasAnyAuthority('MEETING_MANAGE_TEAM', 'MEETING_MANAGE_ALL')")
    @Operation(summary = "The meeting link, for editing")
    public ResponseEntity<ApiResponse<Map<String, String>>> link(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("meetingUrl", meetingService.meetingUrlForEdit(principal, id))));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('MEETING_MANAGE_TEAM', 'MEETING_MANAGE_ALL')")
    @Operation(summary = "Create a meeting")
    public ResponseEntity<ApiResponse<MeetingResponse>> create(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody MeetingCreateRequest request) {
        MeetingResponse created = meetingService.create(principal, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Meeting created. Publish it to invite people."));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('MEETING_MANAGE_TEAM', 'MEETING_MANAGE_ALL')")
    @Operation(summary = "Edit a meeting before it opens")
    public ResponseEntity<ApiResponse<MeetingResponse>> update(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody MeetingCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                meetingService.update(principal, id, request), "Meeting updated."));
    }

    @PutMapping("/{id}/publish")
    @PreAuthorize("hasAnyAuthority('MEETING_MANAGE_TEAM', 'MEETING_MANAGE_ALL')")
    @Operation(summary = "Freeze the roster and invite everyone on it")
    public ResponseEntity<ApiResponse<MeetingResponse>> publish(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        MeetingResponse published = meetingService.publish(principal, id);
        return ResponseEntity.ok(ApiResponse.success(published,
                published.invitedCount() + " people invited."));
    }

    @PutMapping("/{id}/start")
    @PreAuthorize("hasAnyAuthority('MEETING_MANAGE_TEAM', 'MEETING_MANAGE_ALL')")
    @Operation(summary = "Mark the meeting as started")
    public ResponseEntity<ApiResponse<MeetingResponse>> start(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                meetingService.start(principal, id), "Meeting started."));
    }

    @PutMapping("/{id}/end")
    @PreAuthorize("hasAnyAuthority('MEETING_MANAGE_TEAM', 'MEETING_MANAGE_ALL')")
    @Operation(summary = "End the meeting and work out who attended")
    public ResponseEntity<ApiResponse<MeetingResponse>> end(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                meetingService.end(principal, id), "Meeting ended. Attendance recorded."));
    }

    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('MEETING_MANAGE_TEAM', 'MEETING_MANAGE_ALL')")
    @Operation(summary = "Cancel a meeting that has not run")
    public ResponseEntity<ApiResponse<MeetingResponse>> cancel(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody MeetingCancelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                meetingService.cancel(principal, id, request), "Meeting cancelled."));
    }
}
