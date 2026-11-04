package com.company.attendance.enums;

/**
 * Which platform the link points at, derived from the URL host when the
 * meeting is saved.
 *
 * <p>Display and grouping only. The application never talks to any of these
 * services — it redirects to the link and records that the redirect happened.
 */
public enum MeetingProvider {
    GOOGLE_MEET,
    ZOOM,
    MICROSOFT_TEAMS,
    WEBEX,
    OTHER
}
