package com.company.attendance.event;

import com.company.attendance.dto.response.leave.LeaveRequestResponse;
import com.company.attendance.dto.response.request.RegularizationResponse;
import com.company.attendance.dto.response.request.WorkFromHomeResponse;
import com.company.attendance.security.UserPrincipal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * One place that knows how to describe a request to the outside world.
 *
 * <p>The request services call this with the response they have already built,
 * so publishing costs them a single line and no knowledge of who is listening.
 * The dependency runs this way round on purpose: leave depends on the publisher,
 * the publisher depends on nothing but events, and notifications depend only on
 * events. Nothing has to import anything it should not.
 */
@Component
public class RequestEventPublisher {

    private final ApplicationEventPublisher publisher;

    public RequestEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    // -----------------------------------------------------------------
    // Leave
    // -----------------------------------------------------------------

    public void leaveRequested(LeaveRequestResponse request) {
        publisher.publishEvent(new ApprovalRequestedEvent(
                "LEAVE",
                request.requestNumber(),
                request.id(),
                request.employeeId(),
                request.employeeName(),
                request.currentApproverEmployeeId(),
                summariseLeave(request),
                "/approvals/leave"));
    }

    public void leaveDecided(LeaveRequestResponse request, UserPrincipal actor,
                             String decision, String comments) {
        publisher.publishEvent(new ApprovalDecidedEvent(
                "LEAVE",
                request.requestNumber(),
                request.id(),
                request.employeeId(),
                decision,
                actor == null ? null : actor.getUserId(),
                actorName(actor),
                request.employeeId(),
                summariseLeave(request),
                comments,
                "/leave/history"));
    }

    // -----------------------------------------------------------------
    // Regularization
    // -----------------------------------------------------------------

    public void regularizationRequested(RegularizationResponse request) {
        publisher.publishEvent(new ApprovalRequestedEvent(
                "REGULARIZATION",
                request.requestNumber(),
                request.id(),
                request.employeeId(),
                request.employeeName(),
                request.currentApproverEmployeeId(),
                "A correction to " + request.attendanceDate(),
                "/approvals/requests"));
    }

    public void regularizationDecided(RegularizationResponse request, UserPrincipal actor,
                                      String decision, String comments) {
        publisher.publishEvent(new ApprovalDecidedEvent(
                "REGULARIZATION",
                request.requestNumber(),
                request.id(),
                request.employeeId(),
                decision,
                actor == null ? null : actor.getUserId(),
                actorName(actor),
                request.employeeId(),
                "Your correction to " + request.attendanceDate(),
                comments,
                "/requests/regularization"));
    }

    // -----------------------------------------------------------------
    // Work from home
    // -----------------------------------------------------------------

    public void wfhRequested(WorkFromHomeResponse request) {
        publisher.publishEvent(new ApprovalRequestedEvent(
                "WFH",
                request.requestNumber(),
                request.id(),
                request.employeeId(),
                request.employeeName(),
                request.currentApproverEmployeeId(),
                summariseWfh(request),
                "/approvals/requests"));
    }

    public void wfhDecided(WorkFromHomeResponse request, UserPrincipal actor,
                           String decision, String comments) {
        publisher.publishEvent(new ApprovalDecidedEvent(
                "WFH",
                request.requestNumber(),
                request.id(),
                request.employeeId(),
                decision,
                actor == null ? null : actor.getUserId(),
                actorName(actor),
                request.employeeId(),
                summariseWfh(request),
                comments,
                "/requests/wfh"));
    }

    // -----------------------------------------------------------------

    /** The one line that tells an approver what they are being asked for. */
    private String summariseLeave(LeaveRequestResponse request) {
        String dates = request.fromDate().equals(request.toDate())
                ? String.valueOf(request.fromDate())
                : request.fromDate() + " to " + request.toDate();
        return request.leaveTypeName() + ", " + dates
                + " (" + request.totalDays() + " day" + (isOne(request) ? "" : "s") + ")";
    }

    private boolean isOne(LeaveRequestResponse request) {
        return request.totalDays() != null && request.totalDays().doubleValue() == 1d;
    }

    private String summariseWfh(WorkFromHomeResponse request) {
        String dates = request.fromDate().equals(request.toDate())
                ? String.valueOf(request.fromDate())
                : request.fromDate() + " to " + request.toDate();
        return "Work from home, " + dates
                + " (" + request.totalDays() + " day" + (request.totalDays() == 1 ? "" : "s") + ")";
    }

    /**
     * Deliberately not resolved here. The principal only carries a username
     * like EMP0002; the notification layer turns the user id into a name a
     * person recognises, in one place rather than at every publish site.
     */
    private String actorName(UserPrincipal actor) {
        return null;
    }
}
