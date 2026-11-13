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
 *
 * <p><strong>The sandwich rule is applied within a single request.</strong>
 * Friday-to-Monday in one application charges the weekend; Friday in one
 * application and Monday in another does not, because the weekend falls
 * outside both date ranges. Closing that gap means the later request
 * absorbing days that lie outside its own span — writing
 * {@link LeaveRequestDay} rows beyond {@code fromDate}/{@code toDate} — or
 * reopening a request that has already been approved and had its balance
 * settled. Both are defensible; neither is free, and which is right is a
 * decision about the record, not about the arithmetic.
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

        int sandwiched = applySandwichRule(days);
        if (sandwiched > 0) {
            // Recount rather than adjust: the sandwich pass flips days to
            // deductible, and adding their portions to a running total that
            // was computed before the flip is how a figure and its own day
            // list stop agreeing.
            deductibleTotal = BigDecimal.ZERO;
            for (LeaveRequestDay day : days) {
                if (day.isDeductible()) {
                    deductibleTotal = deductibleTotal.add(day.getDayPortion().days());
                }
            }
            skippedHolidays = countSkipped(days, DayType.HOLIDAY);
            skippedWeeklyOffs = countSkipped(days, DayType.WEEKLY_OFF);
        }

        return new Expansion(days, deductibleTotal,
                explain(skippedHolidays, skippedWeeklyOffs, sandwiched));
    }

    /**
     * Charges a holiday or weekly off that has leave on the working day either
     * side of it — Share India's sandwich rule.
     *
     * <p>Take Friday and Monday off and the weekend is charged too. Take only
     * Friday, or only Monday, and it is not. That asymmetry is the whole point
     * of the rule: it prices a four-day absence as four days rather than two.
     *
     * <p>A run is only sandwiched when there is a chargeable leave day
     * immediately before <em>and</em> immediately after it, both inside this
     * request. A half day counts as leave on that side — somebody who takes
     * Friday afternoon and Monday morning has still bridged the weekend.
     *
     * <p><strong>Within one request only.</strong> Applying for Friday and
     * Monday as two separate requests does not trigger this, because the
     * weekend lies outside both of their date ranges and charging it would
     * mean reopening a request that has already been approved and settled.
     * See the note on the class for what closing that gap would involve.
     *
     * @return how many days were turned into chargeable days
     */
    private int applySandwichRule(List<LeaveRequestDay> days) {
        int charged = 0;

        int index = 0;
        while (index < days.size()) {
            if (days.get(index).isDeductible()) {
                index++;
                continue;
            }

            // The run of consecutive non-chargeable days starting here.
            int runStart = index;
            int runEnd = index;
            while (runEnd + 1 < days.size() && !days.get(runEnd + 1).isDeductible()) {
                runEnd++;
            }

            boolean leaveBefore = runStart > 0 && days.get(runStart - 1).isDeductible();
            boolean leaveAfter = runEnd + 1 < days.size() && days.get(runEnd + 1).isDeductible();

            if (leaveBefore && leaveAfter) {
                for (int i = runStart; i <= runEnd; i++) {
                    LeaveRequestDay day = days.get(i);
                    // Always a whole day: half portions belong to the
                    // boundaries of the request, and a bridged weekend is not
                    // a boundary.
                    day.setDayPortion(DayPortion.FULL_DAY);
                    day.setDeductible(true);
                    charged++;
                }
            }

            index = runEnd + 1;
        }

        return charged;
    }

    private int countSkipped(List<LeaveRequestDay> days, DayType dayType) {
        return (int) days.stream()
                .filter(day -> !day.isDeductible())
                .filter(day -> day.getDayType() == dayType)
                .count();
    }

    /**
     * A sentence explaining any difference between the span and the charge.
     * Null when they match, so the UI shows nothing rather than a truism.
     */
    private String explain(int holidays, int weeklyOffs, int sandwiched) {
        if (holidays == 0 && weeklyOffs == 0 && sandwiched == 0) {
            return null;
        }

        StringBuilder message = new StringBuilder();

        if (sandwiched > 0) {
            // Said first, and said plainly. This is the part that costs
            // somebody days they did not select, so it is the part they will
            // query — and "sandwich rule" means nothing to most people.
            message.append("Charged for ")
                    .append(sandwiched)
                    .append(sandwiched == 1 ? " non-working day" : " non-working days")
                    .append(" falling between two days of leave.");
            if (holidays == 0 && weeklyOffs == 0) {
                return message.toString();
            }
            message.append(' ');
        }

        message.append("Not charged for ");
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
