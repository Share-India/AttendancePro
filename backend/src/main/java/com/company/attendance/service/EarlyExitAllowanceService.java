package com.company.attendance.service;

import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.Shift;
import com.company.attendance.repository.AttendanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * The month's allowance of early departures.
 *
 * <p>Early-exit grace used to apply every day — fifteen minutes every
 * evening, with nothing counting the total — so somebody could leave a
 * quarter of an hour early on every working day of a month, five and a half
 * hours in all, and the system would record not a minute of it. The figure
 * simply did not exist to be looked at.
 *
 * <p>Two departures a month are forgiven now, and the third is recorded in
 * full.
 *
 * <p><strong>Occasions, not minutes.</strong> {@link MonthlyGraceService}
 * gives lateness a budget of minutes, because arriving is a moment and five
 * minutes late differs from twelve by seven minutes the pool can hold.
 * Leaving early is an occasion: the rule is about how often somebody goes
 * home before the shift ends, so the allowance counts occasions. Each one
 * still forgives up to the shift's or policy's grace, so leaving 40 minutes
 * early spends one of the two and still records 25.
 *
 * <p><strong>A day is not independent.</strong> Whether the 20th is recorded
 * depends on what the 3rd and the 10th spent — with the same two consequences
 * {@link MonthlyGraceService} documents, and for the same reasons:
 * re-settling a day excludes that day's own prior spend, and correcting an
 * earlier day does not retroactively re-settle the later ones.
 *
 * <p>This service decides what is <em>recorded</em>. It costs nobody
 * anything: {@code early_exit_minutes} is read by the attendance screen, the
 * dashboard's count and the Early Exits report, and by no pay calculation.
 */
@Service
public class EarlyExitAllowanceService {

    private final AttendanceRepository attendanceRepository;
    private final CalendarService calendarService;

    public EarlyExitAllowanceService(AttendanceRepository attendanceRepository,
                                     CalendarService calendarService) {
        this.attendanceRepository = attendanceRepository;
        this.calendarService = calendarService;
    }

    /**
     * How a day's early departure is split between the allowance and the
     * record.
     *
     * @param graceUsedMinutes what this day draws from the month's allowance
     * @param earlyExitMinutes what is recorded against the day after that
     */
    public record Allowance(int graceUsedMinutes, int earlyExitMinutes) {

        static Allowance none(int earlyExitMinutes) {
            return new Allowance(0, Math.max(0, earlyExitMinutes));
        }
    }

    /**
     * Applies the month's remaining allowance to one day's early departure.
     *
     * @param employeeId whose allowance
     * @param date       the business date being settled
     * @param rawEarlyExitMinutes minutes before the shift ended, before any grace
     * @param graceMinutes        what one forgiven departure covers
     * @param allowedPerMonth     how many departures a month are forgiven
     */
    @Transactional(readOnly = true)
    public Allowance apply(Long employeeId, LocalDate date, int rawEarlyExitMinutes,
                           int graceMinutes, int allowedPerMonth) {

        if (rawEarlyExitMinutes <= 0 || employeeId == null || date == null) {
            return Allowance.none(rawEarlyExitMinutes);
        }
        if (allowedPerMonth <= 0 || graceMinutes <= 0) {
            return Allowance.none(rawEarlyExitMinutes);
        }

        // The allowance covers the month, but never reaches back past go-live,
        // for the same reason the lateness pool does not: departures recorded
        // before anybody was using the system would otherwise spend occasions
        // people have from day one.
        LocalDate monthStart = monthStartNotBeforeTracking(date);
        LocalDate monthEnd = date.withDayOfMonth(1).plusMonths(1).minusDays(1);

        long alreadySpent = attendanceRepository
                .countEarlyExitGraceUsedInMonthExcluding(employeeId, monthStart, monthEnd, date);

        if (alreadySpent >= allowedPerMonth) {
            // Out of occasions. The whole departure is on the record.
            return Allowance.none(rawEarlyExitMinutes);
        }

        int used = Math.min(rawEarlyExitMinutes, graceMinutes);
        return new Allowance(used, rawEarlyExitMinutes - used);
    }

    /**
     * Applies the allowance to a day and writes both figures onto it.
     *
     * <p>The two belong together — the minutes recorded and the minutes
     * forgiven are one decision — and every settlement path needs both. A
     * caller that set only the early-exit minutes would leave the occasion
     * unspent and forgive the same departure again next week.
     *
     * @param rawEarlyExitMinutes minutes before the shift ended, before any grace
     */
    @Transactional(readOnly = true)
    public void applyTo(Attendance attendance, int rawEarlyExitMinutes,
                        Shift shift, AttendancePolicy policy) {

        Allowance allowance = apply(
                attendance.getEmployee() == null ? null : attendance.getEmployee().getId(),
                attendance.getAttendanceDate(),
                rawEarlyExitMinutes,
                graceMinutesFor(shift, policy),
                policy == null ? 0 : policy.getEarlyExitsAllowedPerMonth());

        attendance.setEarlyExitGraceUsedMinutes(allowance.graceUsedMinutes());
        attendance.setEarlyExitMinutes(allowance.earlyExitMinutes());
    }

    /** Occasions still available to this employee for the month containing {@code date}. */
    @Transactional(readOnly = true)
    public int remainingFor(Long employeeId, LocalDate date, int allowedPerMonth) {
        long spent = attendanceRepository.countEarlyExitGraceUsedInMonthExcluding(
                employeeId, monthStartNotBeforeTracking(date),
                date.withDayOfMonth(1).plusMonths(1).minusDays(1), date);
        return (int) Math.max(0, allowedPerMonth - spent);
    }

    /**
     * The shift's grace, falling back to the policy's.
     *
     * <p>Zero on the shift means "ask the policy" rather than "no grace",
     * which is the convention the rest of the engine uses and the trap V25
     * had to document for the lateness side.
     */
    private int graceMinutesFor(Shift shift, AttendancePolicy policy) {
        if (shift != null && shift.getEarlyExitGraceMinutes() > 0) {
            return shift.getEarlyExitGraceMinutes();
        }
        return policy == null ? 0 : policy.getEarlyExitGraceMinutes();
    }

    private LocalDate monthStartNotBeforeTracking(LocalDate date) {
        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDate tracking = calendarService.trackingStartDate();
        return tracking != null && tracking.isAfter(monthStart) ? tracking : monthStart;
    }
}
