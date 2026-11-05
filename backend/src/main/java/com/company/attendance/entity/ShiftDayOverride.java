package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * "On this weekday, these people work this shift instead."
 *
 * <p>Exists because {@link EmployeeShift} assigns a shift by date range, which
 * cannot express a different shift on Saturdays. Scoped like
 * {@link WeeklyOff}, so an office or department can differ from the company
 * without another schema change.
 */
@Entity
@Table(name = "shift_day_overrides")
@Getter
@Setter
@NoArgsConstructor
public class ShiftDayOverride extends BaseEntity {

    @Column(name = "scope_type", nullable = false, length = 30)
    private String scopeType = "GLOBAL";

    @Column(name = "scope_id")
    private Long scopeId;

    /** ISO day-of-week, matching java.time.DayOfWeek: Monday = 1, Sunday = 7. */
    @Column(name = "day_of_week", nullable = false)
    private byte dayOfWeek;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "shift_id", nullable = false)
    private Shift shift;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public boolean appliesTo(LocalDate date) {
        if (!active || date.getDayOfWeek().getValue() != dayOfWeek) {
            return false;
        }
        boolean startedOk = effectiveFrom == null || !effectiveFrom.isAfter(date);
        boolean notEnded = effectiveTo == null || !effectiveTo.isBefore(date);
        return startedOk && notEnded;
    }
}
