package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * A recurring non-working day. Scoped so a department or an individual can
 * differ from the company default.
 */
@Entity
@Table(name = "weekly_offs")
@Getter
@Setter
@NoArgsConstructor
public class WeeklyOff extends BaseEntity {

    @Column(name = "scope_type", nullable = false, length = 30)
    private String scopeType = "GLOBAL";

    @Column(name = "scope_id")
    private Long scopeId;

    /** ISO day-of-week, matching java.time.DayOfWeek: Monday = 1, Sunday = 7. */
    @Column(name = "day_of_week", nullable = false)
    private byte dayOfWeek;

    /** Null means every week. Values such as 2 and 4 express alternate Saturdays. */
    @Column(name = "week_of_month")
    private Byte weekOfMonth;

    @Column(name = "is_half_day", nullable = false)
    private boolean halfDay = false;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public boolean isEffectiveOn(LocalDate date) {
        boolean startedOk = effectiveFrom == null || !effectiveFrom.isAfter(date);
        boolean notEnded = effectiveTo == null || !effectiveTo.isBefore(date);
        return startedOk && notEnded;
    }

    /**
     * Whether this rule applies to a specific date, accounting for the
     * alternate-week case.
     */
    public boolean appliesTo(LocalDate date) {
        if (!active || !isEffectiveOn(date) || date.getDayOfWeek().getValue() != dayOfWeek) {
            return false;
        }
        if (weekOfMonth == null) {
            return true;
        }
        // Which occurrence of this weekday the date is within its month.
        int occurrence = ((date.getDayOfMonth() - 1) / 7) + 1;
        return occurrence == weekOfMonth;
    }
}
