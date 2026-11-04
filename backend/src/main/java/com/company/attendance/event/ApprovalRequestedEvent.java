package com.company.attendance.event;

/**
 * Something now needs a decision from somebody.
 *
 * <p>Carries only primitives and strings. That is the point: the notification
 * module must be able to react to a leave request without importing anything
 * from the leave module, or the dependency graph grows a cycle the first time
 * a notification wants to say something specific about a leave type.
 *
 * @param requestType          LEAVE, REGULARIZATION or WFH — drives wording only
 * @param requestNumber        the human-facing reference, e.g. LV-2026-000012
 * @param requestId            the row id, for building the link
 * @param applicantEmployeeId  who raised it
 * @param applicantName        their name at the time it was raised
 * @param approverEmployeeId   who must decide; null when routing found nobody
 * @param summary              one line describing what is being asked for
 * @param link                 app-relative path to open it
 */
public record ApprovalRequestedEvent(
        String requestType,
        String requestNumber,
        Long requestId,
        Long applicantEmployeeId,
        String applicantName,
        Long approverEmployeeId,
        String summary,
        String link) {
}
