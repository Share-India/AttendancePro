package com.company.attendance.event;

import com.company.attendance.enums.NotificationType;
import com.company.attendance.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;

/**
 * Turns request-lifecycle events into things people read.
 *
 * <p>This is the only place that knows both that leave exists and that
 * notifications exist, and it only knows either through the plain values on an
 * event. That keeps the modules independent while still allowing the wording to
 * be specific: "Your leave was rejected" is worth more than "Request updated".
 *
 * <p>Bound to {@code AFTER_COMMIT}, not to the publish itself. A notification
 * about an approval that then rolled back is worse than no notification at all:
 * the employee reads that they can take Friday off and comes back to find the
 * request still pending. {@code fallbackExecution} keeps it working when an
 * event is published with no transaction in progress, which would otherwise
 * drop the notification silently.
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApprovalRequested(ApprovalRequestedEvent event) {
        if (event.approverEmployeeId() == null) {
            // Nobody was routed to. The request is not lost — it surfaces in the
            // HR inbox — but there is no individual to tell.
            log.debug("{} {} has no approver yet; nobody notified",
                    event.requestType(), event.requestNumber());
            return;
        }

        NotificationType type = switch (event.requestType()) {
            case "LEAVE" -> NotificationType.LEAVE_APPROVAL_REQUESTED;
            case "REGULARIZATION" -> NotificationType.REGULARIZATION_APPROVAL_REQUESTED;
            default -> NotificationType.WFH_APPROVAL_REQUESTED;
        };

        notificationService.notifyEmployee(
                event.approverEmployeeId(),
                type,
                event.applicantName() + " needs your approval",
                event.summary(),
                event.link(),
                event.requestType(),
                event.requestId(),
                null,
                event.applicantName());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApprovalDecided(ApprovalDecidedEvent event) {
        NotificationType type = resolveDecisionType(event);

        String title = switch (event.decision()) {
            case "APPROVED" -> describe(event.requestType()) + " was approved";
            case "REJECTED" -> describe(event.requestType()) + " was rejected";
            case "WITHDRAWN" -> event.summary();
            case "CANCELLED" -> describe(event.requestType()) + " was cancelled";
            default -> describe(event.requestType()) + " was updated";
        };

        // A rejection without its reason is the most frustrating message an
        // employee can get, so the comment is carried through verbatim.
        String body = event.comments() != null && !event.comments().isBlank()
                ? event.summary() + " — " + event.comments()
                : event.summary();

        notificationService.notifyEmployee(
                event.notifyEmployeeId() != null ? event.notifyEmployeeId() : event.applicantEmployeeId(),
                type,
                title,
                body,
                event.link(),
                event.requestType(),
                event.requestId(),
                event.actorUserId(),
                event.actorName());
    }

    private NotificationType resolveDecisionType(ApprovalDecidedEvent event) {
        if ("WITHDRAWN".equals(event.decision()) || "CANCELLED".equals(event.decision())) {
            return NotificationType.REQUEST_WITHDRAWN;
        }
        boolean approved = "APPROVED".equals(event.decision());

        return switch (event.requestType()) {
            case "LEAVE" -> approved ? NotificationType.LEAVE_APPROVED : NotificationType.LEAVE_REJECTED;
            case "REGULARIZATION" -> approved
                    ? NotificationType.REGULARIZATION_APPROVED
                    : NotificationType.REGULARIZATION_REJECTED;
            default -> approved ? NotificationType.WFH_APPROVED : NotificationType.WFH_REJECTED;
        };
    }

    /** Wording a person would use, not the enum name. */
    private String describe(String requestType) {
        return switch (requestType) {
            case "LEAVE" -> "Your leave request";
            case "REGULARIZATION" -> "Your attendance correction";
            case "WFH" -> "Your work-from-home request";
            default -> "Your " + requestType.toLowerCase(Locale.ROOT) + " request";
        };
    }
}
