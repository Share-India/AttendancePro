package com.company.attendance.service;

import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.Shift;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.WorkMode;
import com.company.attendance.util.DateTimeUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Turns punch times into minutes and a status.
 *
 * <p>Every threshold arrives as an argument; nothing is read from a constant.
 * The shift's own values win where it defines them, because a night shift and a
 * general shift legitimately disagree about what a full day is; the policy is
 * the fallback.
 *
 * <p>Deliberately free of repository and clock access so the arithmetic can be
 * tested exhaustively without a database.
 */
@Service
public class AttendanceCalculationService {

    /**
     * The computed shape of one day.
     */
    public record DayCalculation(
            int workingMinutes,
            int breakMinutes,
            int lateMinutes,
            int earlyExitMinutes,
            int overtimeMinutes,
            AttendanceStatus status
    ) {
    }

    /**
     * Minutes late, measured against the shift start plus its grace period.
     *
     * <p>Grace is forgiveness, not a shifted start: arriving 7 minutes into a
     * 15-minute grace is 0 late, and arriving at 22 minutes is 7 late, not 22.
     */
    public int calculateLateMinutes(Instant punchInTime, LocalDate businessDate, ZoneId zone,
                                    Shift shift, AttendancePolicy policy) {
        if (punchInTime == null || shift == null) {
            return 0;
        }
        int graceMinutes = shift.getGracePeriodMinutes() > 0
                ? shift.getGracePeriodMinutes()
                : policy.getGracePeriodMinutes();

        Instant graceEnds = DateTimeUtils.shiftStartInstant(businessDate, zone, shift)
                .plusSeconds(graceMinutes * 60L);

        return DateTimeUtils.minutesBetween(graceEnds, punchInTime);
    }

    /** Minutes left early, measured against the shift end minus its grace. */
    public int calculateEarlyExitMinutes(Instant punchOutTime, LocalDate businessDate, ZoneId zone,
                                         Shift shift, AttendancePolicy policy) {
        if (punchOutTime == null || shift == null) {
            return 0;
        }
        int graceMinutes = shift.getEarlyExitGraceMinutes() > 0
                ? shift.getEarlyExitGraceMinutes()
                : policy.getEarlyExitGraceMinutes();

        Instant graceStarts = DateTimeUtils.shiftEndInstant(businessDate, zone, shift)
                .minusSeconds(graceMinutes * 60L);

        return DateTimeUtils.minutesBetween(punchOutTime, graceStarts);
    }

    /**
     * Working minutes: gross elapsed time less the breaks that are deductible.
     *
     * <p>A paid break still counts as working time, which is why the caller
     * passes only the deductible total rather than every break.
     */
    public int calculateWorkingMinutes(Instant punchInTime, Instant punchOutTime, int deductibleBreakMinutes) {
        if (punchInTime == null || punchOutTime == null) {
            return 0;
        }
        int gross = DateTimeUtils.minutesBetween(punchInTime, punchOutTime);
        return Math.max(0, gross - Math.max(0, deductibleBreakMinutes));
    }

    /**
     * Overtime beyond the shift's standard working time.
     *
     * <p>Returned unapproved and uncapped; the overtime policy decides the
     * minimum that counts and the daily ceiling.
     */
    public int calculateOvertimeMinutes(int workingMinutes, Shift shift) {
        if (shift == null) {
            return 0;
        }
        return Math.max(0, workingMinutes - shift.getStandardWorkMinutes());
    }

    /**
     * The settled status for a completed day.
     *
     * <p>A day that is a holiday or weekly off keeps that identity even when
     * someone worked it — the hours still count, and overtime policy decides
     * what they are worth, but the day is not converted into an ordinary
     * present day.
     */
    public AttendanceStatus determineStatus(int workingMinutes,
                                            DayType dayType,
                                            WorkMode workMode,
                                            Shift shift,
                                            AttendancePolicy policy) {

        int fullDay = shift != null && shift.getFullDayMinutes() > 0
                ? shift.getFullDayMinutes() : policy.getFullDayMinutes();
        int halfDay = shift != null && shift.getHalfDayMinutes() > 0
                ? shift.getHalfDayMinutes() : policy.getHalfDayMinutes();
        int minimum = shift != null && shift.getMinAttendanceMinutes() > 0
                ? shift.getMinAttendanceMinutes() : policy.getMinAttendanceMinutes();

        if (dayType == DayType.HOLIDAY) {
            return AttendanceStatus.HOLIDAY;
        }
        if (dayType == DayType.WEEKLY_OFF) {
            return AttendanceStatus.WEEKLY_OFF;
        }

        if (workingMinutes >= fullDay) {
            return workMode == WorkMode.WFH ? AttendanceStatus.WFH : AttendanceStatus.PRESENT;
        }
        if (workingMinutes >= halfDay) {
            return AttendanceStatus.HALF_DAY;
        }
        if (workingMinutes >= minimum) {
            return AttendanceStatus.INSUFFICIENT_HOURS;
        }
        return AttendanceStatus.ABSENT;
    }

    /**
     * Computes the whole day at once. Used at punch-out and whenever a day is
     * recomputed after a correction.
     */
    public DayCalculation calculateDay(Instant punchInTime,
                                       Instant punchOutTime,
                                       int deductibleBreakMinutes,
                                       int totalBreakMinutes,
                                       LocalDate businessDate,
                                       ZoneId zone,
                                       Shift shift,
                                       AttendancePolicy policy,
                                       DayType dayType,
                                       WorkMode workMode) {

        int workingMinutes = calculateWorkingMinutes(punchInTime, punchOutTime, deductibleBreakMinutes);
        int lateMinutes = calculateLateMinutes(punchInTime, businessDate, zone, shift, policy);
        int earlyExitMinutes = calculateEarlyExitMinutes(punchOutTime, businessDate, zone, shift, policy);
        int overtimeMinutes = calculateOvertimeMinutes(workingMinutes, shift);
        AttendanceStatus status = determineStatus(workingMinutes, dayType, workMode, shift, policy);

        return new DayCalculation(
                workingMinutes, totalBreakMinutes, lateMinutes, earlyExitMinutes, overtimeMinutes, status);
    }
}
