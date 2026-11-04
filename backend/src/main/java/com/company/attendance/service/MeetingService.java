package com.company.attendance.service;

import com.company.attendance.dto.request.meeting.MeetingCancelRequest;
import com.company.attendance.dto.request.meeting.MeetingCreateRequest;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.meeting.MeetingParticipantResponse;
import com.company.attendance.dto.response.meeting.MeetingResponse;
import com.company.attendance.dto.response.meeting.MeetingStatusResponse;
import com.company.attendance.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.List;

/** Morning meetings: scheduling them, joining them, and who turned up. */
public interface MeetingService {

    MeetingResponse create(UserPrincipal principal, MeetingCreateRequest request);

    MeetingResponse update(UserPrincipal principal, Long meetingId, MeetingCreateRequest request);

    /** Freezes the roster and tells everyone on it. */
    MeetingResponse publish(UserPrincipal principal, Long meetingId);

    MeetingResponse start(UserPrincipal principal, Long meetingId);

    /** Stamps the end and derives every participant's status. */
    MeetingResponse end(UserPrincipal principal, Long meetingId);

    MeetingResponse cancel(UserPrincipal principal, Long meetingId, MeetingCancelRequest request);

    MeetingResponse get(UserPrincipal principal, Long meetingId);

    MeetingStatusResponse status(UserPrincipal principal, Long meetingId);

    PageResponse<MeetingResponse> myMeetings(UserPrincipal principal, Pageable pageable);

    PageResponse<MeetingResponse> manage(UserPrincipal principal, String status, Pageable pageable);

    List<MeetingParticipantResponse> roster(UserPrincipal principal, Long meetingId);

    /**
     * Records that this person opened the link and returns where to send them.
     *
     * <p>Returns the external URL rather than redirecting, so the controller
     * owns the HTTP shape and this stays testable without a servlet.
     */
    String registerJoin(UserPrincipal principal, Long meetingId, String ipAddress, String userAgent);

    /** The host's own edit view, which is the only place the raw link is returned. */
    String meetingUrlForEdit(UserPrincipal principal, Long meetingId);
}
