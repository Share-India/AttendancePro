package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A kind of leave and the rules that govern it.
 *
 * <p>All of it is data. An organisation that wants a new leave type adds a row;
 * nothing here is expressed in code, because these rules differ by company and
 * by jurisdiction and none of them belong in a deployment.
 */
@Entity
@Table(name = "leave_types")
@Getter
@Setter
@NoArgsConstructor
public class LeaveType extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_paid", nullable = false)
    private boolean paid = true;

    @Column(name = "is_encashable", nullable = false)
    private boolean encashable = false;

    @Column(name = "allow_half_day", nullable = false)
    private boolean allowHalfDay = true;

    @Column(name = "requires_attachment", nullable = false)
    private boolean requiresAttachment = false;

    /** Null means an attachment is never required on duration alone. */
    @Column(name = "attachment_required_after_days")
    private Short attachmentRequiredAfterDays;

    @Column(name = "min_notice_days", nullable = false)
    private short minNoticeDays = 0;

    @Column(name = "max_consecutive_days")
    private Short maxConsecutiveDays;

    /** Sick leave cannot be planned, so notice rules do not apply to it. */
    @Column(name = "exempt_from_notice", nullable = false)
    private boolean exemptFromNotice = false;

    @Column(name = "allow_backdated", nullable = false)
    private boolean allowBackdated = false;

    /** ALL, MALE or FEMALE. */
    @Column(name = "applicable_gender", nullable = false, length = 10)
    private String applicableGender = "ALL";

    @Column(name = "min_service_days", nullable = false)
    private short minServiceDays = 0;

    /**
     * Whether holidays and weekly offs inside the range are charged.
     *
     * <p>This decides whether a Friday-to-Monday leave costs four days or two.
     * It is a column rather than a constant because getting it wrong is the
     * single most common leave-system bug, and different types answer it
     * differently: casual leave usually skips the weekend, maternity does not.
     */
    @Column(name = "counts_holidays_weekly_offs", nullable = false)
    private boolean countsHolidaysWeeklyOffs = false;

    @Column(name = "accrual_type", nullable = false, length = 20)
    private String accrualType = "ANNUAL";

    @Column(name = "accrual_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal accrualDays = BigDecimal.ZERO;

    @Column(name = "allow_carry_forward", nullable = false)
    private boolean allowCarryForward = false;

    @Column(name = "max_carry_forward_days", precision = 5, scale = 2)
    private BigDecimal maxCarryForwardDays;

    @Column(name = "allow_negative_balance", nullable = false)
    private boolean allowNegativeBalance = false;

    /** Above this many days, HR approves as well as the manager. */
    @Column(name = "hr_approval_threshold_days", precision = 5, scale = 2)
    private BigDecimal hrApprovalThresholdDays;

    @Column(name = "display_order", nullable = false)
    private short displayOrder = 0;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Whether an employee of a given gender may take this type. */
    public boolean appliesToGender(String gender) {
        if ("ALL".equalsIgnoreCase(applicableGender)) {
            return true;
        }
        // An unrecorded gender cannot be checked against a restricted type, so
        // the request is allowed through rather than blocked on missing data.
        if (gender == null || gender.isBlank()) {
            return true;
        }
        return applicableGender.equalsIgnoreCase(gender.trim());
    }
}
