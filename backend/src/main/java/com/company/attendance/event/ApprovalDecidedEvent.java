package com.company.attendance.event;

/**
 * A request was approved, rejected, withdrawn or cancelled.
 *
 * <p>Same rule as {@link ApprovalRequestedEvent}: primitives only, so the
 * notification module never has to know what a LeaveRequest is.
 *
 * @param decision  APPROVED, REJECTED, WITHDRAWN or CANCELLED
 * @param comments  what the decider wrote; shown verbatim, because a rejection
 *                  without its reason is the most frustrating message an
 *                  employee can receive
 */
public record ApprovalDecidedEvent(
        String requestType,
        String requestNumber,
        Long requestId,
        Long applicantEmployeeId,
        String decision,
        Long actorUserId,
        String actorName,
        Long notifyEmployeeId,
        String summary,
        String comments,
        String link) {
}
