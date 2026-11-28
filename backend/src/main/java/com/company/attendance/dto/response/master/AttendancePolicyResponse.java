package com.company.attendance.dto.response.master;

import com.company.attendance.entity.AttendancePolicy;

import java.math.BigDecimal;

/**
 * An attendance policy as the settings screen shows it.
 *
 * @param shiftOverridesDayLengths whether any active shift sets its own day
 *        lengths. When it does, the four thresholds here are a fallback that
 *        nobody actually falls back to, and the screen has to say so rather
 *        than letting somebody change a number that will do nothing.
 */
public record AttendancePolicyResponse(
        Long id,
        String code,
        String name,
        String description,

        short fullDayMinutes,
        short shortDayMinutes,
        short halfDayMinutes,
        short minAttendanceMinutes,

        short gracePeriodMinutes,
        short earlyExitGraceMinutes,
        short earlyExitsAllowedPerMonth,
        short lateMarksPerPenalty,
        BigDecimal lateMarkPenaltyDays,
        String lateMarkResetPeriod,

        short maxBreakMinutes,
        boolean deductBreakFromWorking,
        boolean allowPunchOnHoliday,
        boolean allowPunchOnWeeklyOff,

        short regularizationWindowDays,
        short maxRegularizationsPerMonth,
        short maxWfhDaysPerMonth,
        short wfhMinNoticeDays,

        boolean punchInReminderEnabled,
        short punchInReminderDelayMinutes,

        boolean active,
        boolean shiftOverridesDayLengths
) {

    public static AttendancePolicyResponse from(AttendancePolicy policy,
                                                boolean shiftOverridesDayLengths) {
        return new AttendancePolicyResponse(
                policy.getId(),
                policy.getCode(),
                policy.getName(),
                policy.getDescription(),
                policy.getFullDayMinutes(),
                policy.getShortDayMinutes(),
                policy.getHalfDayMinutes(),
                policy.getMinAttendanceMinutes(),
                policy.getGracePeriodMinutes(),
                policy.getEarlyExitGraceMinutes(),
                policy.getEarlyExitsAllowedPerMonth(),
                policy.getLateMarksPerPenalty(),
                policy.getLateMarkPenaltyDays(),
                policy.getLateMarkResetPeriod(),
                policy.getMaxBreakMinutes(),
                policy.isDeductBreakFromWorking(),
                policy.isAllowPunchOnHoliday(),
                policy.isAllowPunchOnWeeklyOff(),
                policy.getRegularizationWindowDays(),
                policy.getMaxRegularizationsPerMonth(),
                policy.getMaxWfhDaysPerMonth(),
                policy.getWfhMinNoticeDays(),
                policy.isPunchInReminderEnabled(),
                policy.getPunchInReminderDelayMinutes(),
                policy.isActive(),
                shiftOverridesDayLengths);
    }
}
