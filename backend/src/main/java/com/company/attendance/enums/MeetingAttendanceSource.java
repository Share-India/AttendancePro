package com.company.attendance.enums;

/**
 * Where a participant's attendance record came from, and therefore how much
 * it can be trusted.
 *
 * <p>Only LINK_CLICK is implemented. The other two are the upgrade paths, and
 * they exist here from the start so that adopting one later is a new branch
 * rather than a schema change:
 *
 * <ul>
 *   <li>LINK_CLICK — the person opened our join link. Cheap, provider-agnostic,
 *       and a proxy rather than proof.</li>
 *   <li>HOST_MARKED — a human confirmed the roster afterwards.</li>
 *   <li>PROVIDER_IMPORT — real join and leave times from a platform's own
 *       attendance export. The only source that can populate left_at.</li>
 * </ul>
 */
public enum MeetingAttendanceSource {
    LINK_CLICK,
    HOST_MARKED,
    PROVIDER_IMPORT
}
