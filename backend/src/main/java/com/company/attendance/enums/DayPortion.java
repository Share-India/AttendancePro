package com.company.attendance.enums;

import java.math.BigDecimal;

/** How much of a day a leave covers. */
public enum DayPortion {
    FULL_DAY(new BigDecimal("1.0")),
    FIRST_HALF(new BigDecimal("0.5")),
    SECOND_HALF(new BigDecimal("0.5"));

    private final BigDecimal days;

    DayPortion(BigDecimal days) {
        this.days = days;
    }

    public BigDecimal days() {
        return days;
    }

    public boolean isHalf() {
        return this != FULL_DAY;
    }
}
