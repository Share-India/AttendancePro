package com.company.attendance.dto.request.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Setting a day's punch times directly.
 *
 * <p>No employee id and no date: both come from the path, so the thing being
 * corrected is named by the URL rather than by a body a client composed.
 *
 * <p>The reason is required and is not a formality. This write bypasses the
 * approval that normally stands between somebody and a change to their own pay,
 * so the only remaining account of <em>why</em> is this field. It is stored on
 * the audit row and on the attendance record's remarks, where the employee can
 * see it too.
 *
 * @param punchInTime  null leaves the existing punch-in alone
 * @param punchOutTime null leaves the existing punch-out alone
 * @param clearPunchOut sets the punch-out back to nothing, reopening the day —
 *                      the only way to express "they never left", which a null
 *                      {@code punchOutTime} cannot say
 */
public record AttendanceOverrideRequest(

        Instant punchInTime,

        Instant punchOutTime,

        boolean clearPunchOut,

        @NotBlank(message = "Say why these times are being changed")
        @Size(max = 500, message = "That reason is too long")
        String reason) {
}
