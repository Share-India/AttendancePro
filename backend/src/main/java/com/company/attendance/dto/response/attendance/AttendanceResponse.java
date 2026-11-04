package com.company.attendance.dto.response.attendance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** One day's attendance record, as shown in history and calendar detail. */
public record AttendanceResponse(
        Long id,
        Long employeeId,
        String employeeCode,
        String employeeName,
        LocalDate attendanceDate,
        String dayOfWeek,

        Instant punchInTime,
        Instant punchOutTime,

        int workingMinutes,
        int breakMinutes,
        int overtimeMinutes,
        int lateMinutes,
        int earlyExitMinutes,

        String workingHours,
        String attendanceStatus,
        String workMode,
        String source,

        String punchInLocation,
        String punchOutLocation,
        BigDecimal punchInDistanceMeters,
        BigDecimal punchOutDistanceMeters,

        String shiftName,
        boolean regularized,
        boolean suspicious,
        List<String> suspicionReasons,
        String remarks,
        List<BreakSummary> breaks
) {

    public record BreakSummary(
            Long id,
            String breakType,
            Instant startTime,
            Instant endTime,
            int durationMinutes,
            boolean exceededLimit,
            boolean autoClosed
    ) {
    }
}
