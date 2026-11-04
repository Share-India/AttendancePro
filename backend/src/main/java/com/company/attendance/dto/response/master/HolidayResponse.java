package com.company.attendance.dto.response.master;

import java.time.LocalDate;

public record HolidayResponse(
        Long id,
        String name,
        LocalDate holidayDate,
        String holidayType,
        String description,
        int holidayYear,
        boolean active,
        /** True once the date has passed, so the UI need not re-derive it. */
        boolean past
) {
}
