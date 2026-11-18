package com.company.attendance.unit;

import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.Shift;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.WorkMode;
import com.company.attendance.service.AttendanceCalculationService;
import com.company.attendance.util.DateTimeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attendance arithmetic, tested without a database.
 *
 * <p>This is where a quiet error costs the most: every figure here reaches
 * payroll, and an off-by-one in grace handling or a mishandled night shift is
 * invisible until someone is paid wrongly.
 */
class AttendanceCalculationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);

    private AttendanceCalculationService service;
    private AttendancePolicy policy;
    private Shift generalShift;

    @BeforeEach
    void setUp() {
        service = new AttendanceCalculationService();

        policy = new AttendancePolicy();
        policy.setFullDayMinutes((short) 480);
        policy.setShortDayMinutes((short) 420);
        policy.setHalfDayMinutes((short) 240);
        policy.setMinAttendanceMinutes((short) 120);
        policy.setGracePeriodMinutes((short) 15);
        policy.setEarlyExitGraceMinutes((short) 15);

        generalShift = new Shift();
        generalShift.setCode("GEN");
        generalShift.setStartTime(LocalTime.of(9, 30));
        generalShift.setEndTime(LocalTime.of(18, 30));
        generalShift.setGracePeriodMinutes((short) 15);
        generalShift.setEarlyExitGraceMinutes((short) 15);
        generalShift.setStandardWorkMinutes((short) 480);
        generalShift.setFullDayMinutes((short) 480);
        generalShift.setShortDayMinutes((short) 420);
        generalShift.setHalfDayMinutes((short) 240);
        generalShift.setMinAttendanceMinutes((short) 120);
    }

    private Instant at(LocalDate date, int hour, int minute) {
        return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(IST).toInstant();
    }

    // -----------------------------------------------------------------

    @Nested
    @DisplayName("working minutes")
    class WorkingMinutes {

        @Test
        @DisplayName("the worked example from the specification: 09:20 to 18:30 less a 45 minute break")
        void specificationExample() {
            int minutes = service.calculateWorkingMinutes(
                    at(MONDAY, 9, 20), at(MONDAY, 18, 30), 45);

            assertThat(minutes).isEqualTo(505);
            assertThat(DateTimeUtils.formatMinutes(minutes)).isEqualTo("8h 25m");
        }

        @Test
        @DisplayName("a paid break is not deducted, so the caller passes only deductible minutes")
        void paidBreaksAreNotDeducted() {
            assertThat(service.calculateWorkingMinutes(at(MONDAY, 9, 0), at(MONDAY, 18, 0), 0))
                    .isEqualTo(540);
        }

        @Test
        @DisplayName("breaks longer than the day cannot drive working time negative")
        void neverNegative() {
            assertThat(service.calculateWorkingMinutes(at(MONDAY, 9, 0), at(MONDAY, 10, 0), 300))
                    .isZero();
        }

        @Test
        @DisplayName("an unfinished day has no working minutes yet")
        void openDayIsZero() {
            assertThat(service.calculateWorkingMinutes(at(MONDAY, 9, 0), null, 0)).isZero();
        }
    }

    @Nested
    @DisplayName("late marks")
    class LateMarks {

        @Test
        @DisplayName("arriving inside the grace period is not late at all")
        void withinGraceIsNotLate() {
            // Shift 09:30, grace 15 minutes, arrived 09:40.
            assertThat(service.calculateLateMinutes(at(MONDAY, 9, 40), MONDAY, IST, generalShift, policy))
                    .isZero();
        }

        @Test
        @DisplayName("grace is forgiveness, not a later start: 09:52 is 7 minutes late, not 22")
        void graceIsForgivenessNotAShiftedStart() {
            assertThat(service.calculateLateMinutes(at(MONDAY, 9, 52), MONDAY, IST, generalShift, policy))
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("arriving exactly at the end of grace is not late")
        void boundaryIsNotLate() {
            assertThat(service.calculateLateMinutes(at(MONDAY, 9, 45), MONDAY, IST, generalShift, policy))
                    .isZero();
        }

        @Test
        @DisplayName("arriving early is never negative")
        void earlyArrivalIsZero() {
            assertThat(service.calculateLateMinutes(at(MONDAY, 8, 50), MONDAY, IST, generalShift, policy))
                    .isZero();
        }
    }

    @Nested
    @DisplayName("early exit")
    class EarlyExit {

        @Test
        @DisplayName("leaving 45 minutes early counts 30 after the 15 minute grace")
        void appliesGrace() {
            // Shift ends 18:30, grace 15, left 17:45.
            assertThat(service.calculateEarlyExitMinutes(at(MONDAY, 17, 45), MONDAY, IST, generalShift, policy))
                    .isEqualTo(30);
        }

        @Test
        @DisplayName("staying past the shift end is never a negative early exit")
        void stayingLateIsZero() {
            assertThat(service.calculateEarlyExitMinutes(at(MONDAY, 19, 30), MONDAY, IST, generalShift, policy))
                    .isZero();
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("meeting the full-day threshold is present")
        void fullDay() {
            assertThat(service.determineStatus(480, DayType.WORKING, WorkMode.OFFICE, generalShift, policy))
                    .isEqualTo(AttendanceStatus.PRESENT);
        }

        @Test
        @DisplayName("between half and full day is a half day")
        void halfDay() {
            assertThat(service.determineStatus(300, DayType.WORKING, WorkMode.OFFICE, generalShift, policy))
                    .isEqualTo(AttendanceStatus.HALF_DAY);
        }

        @Test
        @DisplayName("below the half day but above the minimum is insufficient hours, not absent")
        void insufficientHours() {
            assertThat(service.determineStatus(180, DayType.WORKING, WorkMode.OFFICE, generalShift, policy))
                    .isEqualTo(AttendanceStatus.INSUFFICIENT_HOURS);
        }

        @Test
        @DisplayName("below the minimum is absent")
        void absent() {
            assertThat(service.determineStatus(60, DayType.WORKING, WorkMode.OFFICE, generalShift, policy))
                    .isEqualTo(AttendanceStatus.ABSENT);
        }

        @Test
        @DisplayName("a full day worked from home is recorded as WFH, not plain present")
        void workFromHomeKeepsItsIdentity() {
            assertThat(service.determineStatus(480, DayType.WORKING, WorkMode.WFH, generalShift, policy))
                    .isEqualTo(AttendanceStatus.WFH);
        }

        @Test
        @DisplayName("a holiday stays a holiday even when it was worked")
        void holidayKeepsItsIdentity() {
            assertThat(service.determineStatus(480, DayType.HOLIDAY, WorkMode.OFFICE, generalShift, policy))
                    .isEqualTo(AttendanceStatus.HOLIDAY);
        }

        @Test
        @DisplayName("a weekly off stays a weekly off even when it was worked")
        void weeklyOffKeepsItsIdentity() {
            assertThat(service.determineStatus(480, DayType.WEEKLY_OFF, WorkMode.OFFICE, generalShift, policy))
                    .isEqualTo(AttendanceStatus.WEEKLY_OFF);
        }

        @Test
        @DisplayName("the shift's own thresholds win over the policy's")
        void shiftOverridesPolicy() {
            Shift shortShift = new Shift();
            shortShift.setStartTime(LocalTime.of(9, 0));
            shortShift.setEndTime(LocalTime.of(14, 0));
            shortShift.setFullDayMinutes((short) 300);
            shortShift.setHalfDayMinutes((short) 150);
            shortShift.setMinAttendanceMinutes((short) 60);
            shortShift.setStandardWorkMinutes((short) 300);

            // 300 minutes is a full day on this shift, but only a half day
            // against the 480-minute company policy.
            assertThat(service.determineStatus(300, DayType.WORKING, WorkMode.OFFICE, shortShift, policy))
                    .isEqualTo(AttendanceStatus.PRESENT);
        }
    }

    @Nested
    @DisplayName("overtime")
    class Overtime {

        @Test
        @DisplayName("minutes beyond the shift's standard time are overtime")
        void beyondStandard() {
            assertThat(service.calculateOvertimeMinutes(505, generalShift)).isEqualTo(25);
        }

        @Test
        @DisplayName("a short day earns no negative overtime")
        void shortDayIsZero() {
            assertThat(service.calculateOvertimeMinutes(400, generalShift)).isZero();
        }
    }

    @Nested
    @DisplayName("night shifts")
    class NightShifts {

        private Shift nightShift() {
            Shift shift = new Shift();
            shift.setStartTime(LocalTime.of(22, 0));
            shift.setEndTime(LocalTime.of(7, 0));
            shift.setNightShift(true);
            shift.setGracePeriodMinutes((short) 10);
            shift.setEarlyExitGraceMinutes((short) 10);
            shift.setStandardWorkMinutes((short) 480);
            shift.setFullDayMinutes((short) 480);
            shift.setHalfDayMinutes((short) 240);
            shift.setMinAttendanceMinutes((short) 120);
            return shift;
        }

        @Test
        @DisplayName("punching out the next morning still belongs to the night the shift began")
        void punchOutBelongsToShiftStartDate() {
            Shift night = nightShift();
            // 07:10 the following morning, before the 22:00 start time.
            Instant nextMorning = at(MONDAY.plusDays(1), 7, 10);

            assertThat(DateTimeUtils.resolveBusinessDate(nextMorning, IST, night)).isEqualTo(MONDAY);
        }

        @Test
        @DisplayName("punching in at the start of the night belongs to that same night")
        void punchInBelongsToItsOwnDate() {
            Shift night = nightShift();
            assertThat(DateTimeUtils.resolveBusinessDate(at(MONDAY, 22, 5), IST, night)).isEqualTo(MONDAY);
        }

        @Test
        @DisplayName("a day shift is never reassigned to the previous date")
        void dayShiftIsUnaffected() {
            assertThat(DateTimeUtils.resolveBusinessDate(at(MONDAY, 7, 10), IST, generalShift))
                    .isEqualTo(MONDAY);
        }

        @Test
        @DisplayName("a night shift's end time is measured on the following calendar day")
        void earlyExitSpansMidnight() {
            Shift night = nightShift();
            // Left at 06:00, shift ends 07:00 next day with 10 minutes grace,
            // so 50 minutes early rather than a nonsensical figure.
            Instant leftEarly = at(MONDAY.plusDays(1), 6, 0);

            assertThat(service.calculateEarlyExitMinutes(leftEarly, MONDAY, IST, night, policy))
                    .isEqualTo(50);
        }

        @Test
        @DisplayName("a full night shift computes as a full day, not two partial ones")
        void fullNightIsOneFullDay() {
            Shift night = nightShift();
            var calculation = service.calculateDay(
                    at(MONDAY, 22, 0), at(MONDAY.plusDays(1), 7, 0),
                    60, 60, MONDAY, IST, night, policy, DayType.WORKING, WorkMode.OFFICE);

            assertThat(calculation.workingMinutes()).isEqualTo(480);
            assertThat(calculation.status()).isEqualTo(AttendanceStatus.PRESENT);
        }
    }

    @Nested
    @DisplayName("timezone independence")
    class TimezoneIndependence {

        @Test
        @DisplayName("the same wall-clock day in a different zone yields the same minutes")
        void sameShapeAcrossZones() {
            ZoneId newYork = ZoneId.of("America/New_York");

            Instant istIn = LocalDateTime.of(MONDAY, LocalTime.of(9, 30)).atZone(IST).toInstant();
            Instant istOut = LocalDateTime.of(MONDAY, LocalTime.of(18, 30)).atZone(IST).toInstant();

            Instant nyIn = LocalDateTime.of(MONDAY, LocalTime.of(9, 30)).atZone(newYork).toInstant();
            Instant nyOut = LocalDateTime.of(MONDAY, LocalTime.of(18, 30)).atZone(newYork).toInstant();

            assertThat(service.calculateWorkingMinutes(istIn, istOut, 45))
                    .isEqualTo(service.calculateWorkingMinutes(nyIn, nyOut, 45));

            // And late marks agree too, each measured in its own office's zone.
            assertThat(service.calculateLateMinutes(istIn, MONDAY, IST, generalShift, policy))
                    .isEqualTo(service.calculateLateMinutes(nyIn, MONDAY, newYork, generalShift, policy));
        }
    }

    @Nested
    @DisplayName("a full day has to be reachable inside the shift")
    class FullDayIsReachable {

        /** A nine-hour span, as every one of this company's shifts is. */
        private Shift nineHourShift(int fullDayMinutes) {
            Shift shift = new Shift();
            shift.setCode("TEST");
            shift.setStartTime(LocalTime.of(8, 30));
            shift.setEndTime(LocalTime.of(17, 30));
            shift.setStandardWorkMinutes((short) fullDayMinutes);
            shift.setFullDayMinutes((short) fullDayMinutes);
            shift.setShortDayMinutes((short) (fullDayMinutes - 60));
            shift.setHalfDayMinutes((short) (fullDayMinutes / 2));
            shift.setMinAttendanceMinutes((short) 120);
            return shift;
        }

        @Test
        @DisplayName("working the whole shift less the break allowance is a full day")
        void breaksDoNotCostHalfADay() {
            // 9 hours present, 45 minutes of deductible break -- lunch and a
            // personal break at Share India's limits -- leaves 495 worked.
            // With the full day set to 480 that is a present day, as it must
            // be: a rule nobody can satisfy without skipping lunch is not a
            // rule, it is a defect.
            Shift shift = nineHourShift(480);

            assertThat(service.determineStatus(495, DayType.WORKING, WorkMode.OFFICE, shift, policy))
                    .isEqualTo(AttendanceStatus.PRESENT);
        }

        @Test
        @DisplayName("a full day equal to the whole span makes an ordinary day a half day")
        void theMisconfigurationThisGuardsAgainst() {
            // Early and Late were defined this way: full_day_minutes 540 on a
            // 540-minute span. Eighteen people could not reach a full day
            // without working every minute and taking no break at all. Two
            // lost half a day's pay before it was noticed.
            //
            // Kept as a test rather than a comment because the arithmetic is
            // not wrong here -- the configuration was. This is what that
            // configuration does, stated plainly.
            Shift impossible = nineHourShift(540);

            // With the short-day band in place this no longer costs half a
            // day's pay -- but it is still not a full day, which is wrong for
            // somebody who worked one. The band limits the damage of a bad
            // shift definition; it does not excuse one.
            assertThat(service.determineStatus(535, DayType.WORKING, WorkMode.OFFICE, impossible, policy))
                    .isEqualTo(AttendanceStatus.SHORT_DAY);

            // The same day against a correctly configured shift.
            assertThat(service.determineStatus(535, DayType.WORKING, WorkMode.OFFICE,
                    nineHourShift(480), policy))
                    .isEqualTo(AttendanceStatus.PRESENT);
        }

        @Test
        @DisplayName("the expected day and a full day are different numbers")
        void expectedIsNineHoursButAFullDayIsEight() {
            // The punch screen promises nine hours because that is how long
            // somebody is at work. A full day needs eight, because work is
            // counted net of lunch. Collapsing the two breaks one or the
            // other: equal at 540 makes a full day unreachable, equal at 480
            // makes the screen promise a day nobody works.
            Shift shift = nineHourShift(480);
            shift.setStandardWorkMinutes((short) 540);

            // Present the whole span, 45 minutes of deductible break.
            assertThat(service.determineStatus(495, DayType.WORKING, WorkMode.OFFICE, shift, policy))
                    .isEqualTo(AttendanceStatus.PRESENT);

            // And no overtime for having eaten quickly.
            assertThat(service.calculateOvertimeMinutes(495, shift)).isZero();
            assertThat(service.calculateOvertimeMinutes(570, shift)).isEqualTo(30);
        }

        @Test
        @DisplayName("running short by under an hour is a short day, not half a day")
        void shortOfTheMarkIsNotHalfADay() {
            // Twenty minutes late and away on time used to cost half a day's
            // pay -- the same as leaving at lunchtime, because there was
            // nothing between the two.
            Shift shift = nineHourShift(480);

            assertThat(service.determineStatus(460, DayType.WORKING, WorkMode.OFFICE, shift, policy))
                    .isEqualTo(AttendanceStatus.SHORT_DAY);
            assertThat(service.determineStatus(450, DayType.WORKING, WorkMode.OFFICE, shift, policy))
                    .isEqualTo(AttendanceStatus.SHORT_DAY);

            // The boundary itself: exactly an hour short is still a short day.
            assertThat(service.determineStatus(420, DayType.WORKING, WorkMode.OFFICE, shift, policy))
                    .isEqualTo(AttendanceStatus.SHORT_DAY);

            // Past an hour, the day really has not been worked.
            assertThat(service.determineStatus(419, DayType.WORKING, WorkMode.OFFICE, shift, policy))
                    .isEqualTo(AttendanceStatus.HALF_DAY);
        }

        @Test
        @DisplayName("a genuinely short day is still a half day")
        void realHalfDaysAreUntouched() {
            // The correction must not turn every short day into a full one.
            Shift shift = nineHourShift(480);

            assertThat(service.determineStatus(300, DayType.WORKING, WorkMode.OFFICE, shift, policy))
                    .isEqualTo(AttendanceStatus.HALF_DAY);
            assertThat(service.determineStatus(150, DayType.WORKING, WorkMode.OFFICE, shift, policy))
                    .isEqualTo(AttendanceStatus.INSUFFICIENT_HOURS);
        }
    }
}
