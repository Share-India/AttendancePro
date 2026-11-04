package com.company.attendance.service;

import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.enums.DayPortion;
import com.company.attendance.enums.DayType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a date range into the individual days a leave actually covers.
 *
 * <p>This is where "does a Friday-to-Monday leave cost four days or two?" is
 * answered, and it is answered from the leave type's own configuration rather
 * than a rule in code. Casual leave usually skips the weekend; maternity leave
 * does not. Both are correct, for different types.
 *
 * <p>Non-deductible days are kept rather than discarded so the UI can show the
 * employee <em>why</em> the charge differs from the span they selected.
 */
@Service
public class LeaveDayExpansionService {

    private final CalendarService calendarService;

    public LeaveDayExpansionService(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    /** The expansion of a range, with the chargeable total already summed. */
    public record Expansion(List<LeaveRequestDay> days, BigDecimal deductibleDays, String explanation) {
    }

    /**
     * Expands a range into days.
     *
     * <p>Half-day portions apply only to the boundaries. A single-day request
     * takes its portion from {@code fromPortion}; on a multi-day request the
     * first and last days may each be a half, and everything between is whole.
     */
    @Transactional(readOnly = true)
    public Expansion expand(Employee employee,
                            LeaveType leaveType,
                            LocalDate fromDate,
                            LocalDate toDate,
                            DayPortion fromPortion,
                            DayPortion toPortion) {

        List<LeaveRequestDay> days = new ArrayList<>();
        BigDecimal deductibleTotal = BigDecimal.ZERO;
        int skippedHolidays = 0;
        int skippedWeeklyOffs = 0;

        boolean singleDay = fromDate.equals(toDate);

        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            DayType dayType = calendarService.resolveDayType(employee, date);

            DayPortion portion;
            if (singleDay) {
                portion = fromPortion;
            } else if (date.equals(fromDate)) {
                // A leave starting after lunch charges half that first day.
                portion = fromPortion == DayPortion.SECOND_HALF ? DayPortion.SECOND_HALF : DayPortion.FULL_DAY;
            } else if (date.equals(toDate)) {
                // A leave ending at lunch charges half that last day.
                portion = toPortion == DayPortion.FIRST_HALF ? DayPortion.FIRST_HALF : DayPortion.FULL_DAY;
            } else {
                portion = DayPortion.FULL_DAY;
            }

            boolean deductible = leaveType.isCountsHolidaysWeeklyOffs() || dayType == DayType.WORKING;

            if (!deductible) {
                if (dayType == DayType.HOLIDAY) {
                    skippedHolidays++;
                } else if (dayType == DayType.WEEKLY_OFF) {
                    skippedWeeklyOffs++;
                }
            }

            LeaveRequestDay day = new LeaveRequestDay();
            day.setEmployeeId(employee.getId());
            day.setLeaveDate(date);
            day.setDayPortion(portion);
            day.setDeductible(deductible);
            day.setDayType(dayType);
            days.add(day);

            if (deductible) {
                deductibleTotal = deductibleTotal.add(portion.days());
            }
        }

        return new Expansion(days, deductibleTotal, explain(skippedHolidays, skippedWeeklyOffs));
    }

    /**
     * A sentence explaining any difference between the span and the charge.
     * Null when they match, so the UI shows nothing rather than a truism.
     */
    private String explain(int holidays, int weeklyOffs) {
        if (holidays == 0 && weeklyOffs == 0) {
            return null;
        }

        StringBuilder message = new StringBuilder("Not charged for ");
        if (holidays > 0) {
            message.append(holidays).append(holidays == 1 ? " holiday" : " holidays");
        }
        if (holidays > 0 && weeklyOffs > 0) {
            message.append(" and ");
        }
        if (weeklyOffs > 0) {
            message.append(weeklyOffs).append(weeklyOffs == 1 ? " weekly off" : " weekly offs");
        }
        return message.append(" inside these dates.").toString();
    }
}
