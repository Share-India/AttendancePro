package com.company.attendance.dto.request.master;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * A company-wide holiday.
 *
 * <p>There is no office field, and that is a decision rather than an
 * omission: every holiday here applies to everybody. Offices in different
 * states do observe different days, so if that is ever needed it wants a
 * join table and a deliberate change to {@code CalendarService}, not a
 * nullable column bolted on later.
 *
 * <p>{@code holidayYear} is not accepted from the caller. It is a separate
 * column on the table that has to agree with the date, and asking a client
 * to send a value that can only ever be derived is asking for the two to
 * drift. The service sets it.
 */
public record HolidayRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 150)
        String name,

        @NotNull(message = "Date is required")
        LocalDate holidayDate,

        /*
         * The column allows OPTIONAL and RESTRICTED too, but those mean a day
         * each employee may choose to take, and nothing in this application
         * models that choice — CalendarService excuses everyone from any
         * active holiday, whatever its type. Accepting them would quietly
         * give the whole company the day off. Only the three types that do
         * apply to everybody are allowed here.
         */
        @NotBlank(message = "Type is required")
        @Pattern(regexp = "NATIONAL|STATE|COMPANY",
                message = "Type must be NATIONAL, STATE or COMPANY")
        String holidayType,

        @Size(max = 500)
        String description,

        Boolean active
) {
}
