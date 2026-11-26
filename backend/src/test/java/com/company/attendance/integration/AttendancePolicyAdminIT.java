package com.company.attendance.integration;

import com.company.attendance.dto.request.master.AttendancePolicyRequest;
import com.company.attendance.dto.response.master.AttendancePolicyResponse;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.Shift;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendancePolicyRepository;
import com.company.attendance.repository.AuditLogRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.service.AttendancePolicyAdminService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Changing the attendance rules from the application.
 *
 * <p>Every number here was configurable in the database and changeable
 * nowhere, so each adjustment was an UPDATE typed against production. That is
 * how a full day came to equal the length of the shift, which made a full day
 * unreachable for eighteen people and cost two of them half a day's pay.
 *
 * <p>So the ordering checks matter more than the plumbing: they are the thing
 * that stops the same class of mistake being made again through a nicer
 * interface.
 */
@SpringBootTest
@ActiveProfiles("test")
class AttendancePolicyAdminIT {

    @Autowired AttendancePolicyAdminService service;
    @Autowired AttendancePolicyRepository policyRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Long policyId;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        AttendancePolicy policy = new AttendancePolicy();
        policy.setCode("DEFAULT");
        policy.setName("Default Attendance Policy");
        policyId = policyRepository.save(policy).getId();
    }

    /** The request, with sensible values, varied by the caller. */
    private AttendancePolicyRequest request(short fullDay, short shortDay, short halfDay,
                                            short minimum, short window, short perMonth) {
        return new AttendancePolicyRequest(
                "Default Attendance Policy", "The company default",
                fullDay, shortDay, halfDay, minimum,
                (short) 15, (short) 15, (short) 3,
                (short) 60, true, true, true,
                window, perMonth, (short) 8, (short) 0,
                false, (short) 30);
    }

    private AttendancePolicyRequest valid() {
        return request((short) 480, (short) 420, (short) 240, (short) 120, (short) 7, (short) 3);
    }

    private Shift shift(short fullDayMinutes) {
        Shift shift = new Shift();
        shift.setCode("GEN");
        shift.setName("General Shift");
        shift.setStartTime(LocalTime.of(9, 30));
        shift.setEndTime(LocalTime.of(18, 30));
        shift.setFullDayMinutes(fullDayMinutes);
        return shiftRepository.save(shift);
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("the correction limits can be changed without touching the database")
    void correctionLimitsAreEditable() {
        // The whole point of the screen.
        AttendancePolicyResponse saved = service.update(policyId,
                request((short) 480, (short) 420, (short) 240, (short) 120,
                        (short) 14, (short) 5));

        assertThat(saved.regularizationWindowDays()).isEqualTo((short) 14);
        assertThat(saved.maxRegularizationsPerMonth()).isEqualTo((short) 5);

        AttendancePolicy reloaded = policyRepository.findById(policyId).orElseThrow();
        assertThat(reloaded.getRegularizationWindowDays()).isEqualTo((short) 14);
        assertThat(reloaded.getMaxRegularizationsPerMonth()).isEqualTo((short) 5);
    }

    @Test
    @DisplayName("a short day longer than a full day is refused, and says why")
    void shortDayCannotExceedFullDay() {
        assertThatThrownBy(() -> service.update(policyId,
                request((short) 480, (short) 500, (short) 240, (short) 120,
                        (short) 7, (short) 3)))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> {
                    BusinessException e = (BusinessException) thrown;
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.getMessage()).contains("Nothing would ever reach it");
                });
    }

    @Test
    @DisplayName("a half day longer than a short day is refused")
    void halfDayCannotExceedShortDay() {
        assertThatThrownBy(() -> service.update(policyId,
                request((short) 480, (short) 420, (short) 450, (short) 120,
                        (short) 7, (short) 3)))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("swallow"));
    }

    @Test
    @DisplayName("a minimum above a half day is refused")
    void minimumCannotExceedHalfDay() {
        assertThatThrownBy(() -> service.update(policyId,
                request((short) 480, (short) 420, (short) 240, (short) 300,
                        (short) 7, (short) 3)))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("a refused change leaves the policy exactly as it was")
    void nothingIsPartiallyApplied() {
        service.update(policyId, valid());

        assertThatThrownBy(() -> service.update(policyId,
                request((short) 480, (short) 500, (short) 240, (short) 120,
                        (short) 21, (short) 9)))
                .isInstanceOf(BusinessException.class);

        AttendancePolicy reloaded = policyRepository.findById(policyId).orElseThrow();
        assertThat(reloaded.getRegularizationWindowDays()).isEqualTo((short) 7);
        assertThat(reloaded.getShortDayMinutes()).isEqualTo((short) 420);
    }

    @Test
    @DisplayName("the change is audited with both the old and the new figures")
    void auditedWithBothSides() {
        service.update(policyId, valid());
        service.update(policyId,
                request((short) 480, (short) 420, (short) 240, (short) 120,
                        (short) 30, (short) 3));

        assertThat(auditLogRepository.findAll())
                .anySatisfy(log -> {
                    assertThat(log.getAction().name()).isEqualTo("POLICY_CHANGE");
                    assertThat(log.getOldValue()).contains("\"regularizationWindowDays\":7");
                    assertThat(log.getNewValue()).contains("\"regularizationWindowDays\":30");
                });
    }

    @Test
    @DisplayName("the screen is told when shifts make the day lengths a dead letter")
    void warnsWhenShiftsOverride() {
        // A shift's own value wins whenever it is above zero. Without this
        // flag an administrator would change a full day here and watch
        // nothing happen to anybody.
        shift((short) 480);

        assertThat(service.findAll()).singleElement()
                .satisfies(policy -> assertThat(policy.shiftOverridesDayLengths()).isTrue());
    }

    @Test
    @DisplayName("with no shift setting its own, the policy's day lengths are what apply")
    void noWarningWhenShiftsDefer() {
        shift((short) 0);

        assertThat(service.findAll()).singleElement()
                .satisfies(policy -> assertThat(policy.shiftOverridesDayLengths()).isFalse());
    }

    @Test
    @DisplayName("the code is not editable")
    void codeIsNotTouched() {
        // Policy assignments point at the code. Renaming it would detach every
        // employee from the rules they are under, silently.
        AttendancePolicyResponse saved = service.update(policyId, valid());

        assertThat(saved.code()).isEqualTo("DEFAULT");
    }

    @Test
    @DisplayName("equal thresholds are allowed, only inverted ones are refused")
    void equalThresholdsAreFine() {
        // A company with no short-day band sets it equal to a full day. That
        // is a choice, not a mistake, and the check must not forbid it.
        AttendancePolicyResponse saved = service.update(policyId,
                request((short) 480, (short) 480, (short) 240, (short) 120,
                        (short) 7, (short) 3));

        assertThat(saved.shortDayMinutes()).isEqualTo((short) 480);
    }
}
