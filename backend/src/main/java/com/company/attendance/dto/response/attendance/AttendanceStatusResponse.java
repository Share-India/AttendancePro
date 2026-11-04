package com.company.attendance.dto.response.attendance;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Live state for the dashboard.
 *
 * <p>workedMinutes is computed on the server. The browser animates seconds
 * between polls for a smooth timer, but the number it starts from is always
 * the server's — a wrong client clock cannot inflate hours.
 */
public record AttendanceStatusResponse(
        Long attendanceId,
        LocalDate attendanceDate,
        String status,
        String dayType,
        String workMode,

        Instant punchInTime,
        Instant punchOutTime,
        Instant serverTime,

        int workedMinutes,
        int breakMinutes,
        int expectedWorkMinutes,
        int lateMinutes,
        int earlyExitMinutes,
        int overtimeMinutes,

        boolean onBreak,
        Instant currentBreakStartedAt,
        String currentBreakType,

        boolean canPunchIn,
        boolean canPunchOut,
        boolean canStartBreak,
        boolean canEndBreak,
        /** Why a punch is unavailable, when it is. */
        String blockedReason,

        ShiftSummary shift,
        String punchInLocation,
        String punchOutLocation,
        boolean suspicious,
        List<String> suspicionReasons
) {

    public record ShiftSummary(
            Long id,
            String code,
            String name,
            @JsonFormat(pattern = "HH:mm") LocalTime startTime,
            @JsonFormat(pattern = "HH:mm") LocalTime endTime,
            boolean nightShift,
            int gracePeriodMinutes,
            int standardWorkMinutes
    ) {
    }
}
