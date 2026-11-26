package com.company.attendance.service;

import com.company.attendance.config.MailProperties;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.User;
import com.company.attendance.enums.DayState;
import com.company.attendance.enums.NotificationType;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.NotificationRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.util.DateTimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Tells the people who have not punched in that they have not punched in.
 *
 * <p>The Team screen has shown this every morning since go-live: a column of
 * "Not yet in" against people who are not on leave, not on a holiday and not
 * on a weekly off. Nobody told them. They found out when the day was settled
 * against them, by which time the only remedy was a correction request.
 *
 * <p><strong>Who is chased.</strong> Not decided here. {@link DayStateResolver}
 * decides, and the dashboard shows that same answer, so the mail can never
 * disagree with the screen. Only {@link DayState#NOT_YET_IN} is chased --
 * never ABSENT, because by the time the day is over the reminder is useless,
 * and never the three states that mean somebody is legitimately away.
 *
 * <p><strong>When.</strong> A configurable number of minutes after each
 * person's own shift starts, in their own office timezone. Shift starts here
 * span 08:30 to 10:00; a single clock time would chase the early shift ninety
 * minutes late and greet the late shift as they walked in.
 *
 * <p><strong>Once.</strong> The condition stays true until the person acts, and
 * the sweep runs every few minutes, so without a record of what has been sent
 * somebody genuinely away would be mailed all morning. The notification row is
 * that record, and it survives a restart.
 */
@Service
public class PunchInReminderService {

    private static final Logger log = LoggerFactory.getLogger(PunchInReminderService.class);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a");

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final DayStateResolver dayStateResolver;
    private final PolicyResolutionService policyResolutionService;
    private final EmailTemplates emailTemplates;
    private final EmailService emailService;
    private final MailProperties mailProperties;

    public PunchInReminderService(EmployeeRepository employeeRepository,
                                  UserRepository userRepository,
                                  NotificationRepository notificationRepository,
                                  NotificationService notificationService,
                                  DayStateResolver dayStateResolver,
                                  PolicyResolutionService policyResolutionService,
                                  EmailTemplates emailTemplates,
                                  EmailService emailService,
                                  MailProperties mailProperties) {
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
        this.dayStateResolver = dayStateResolver;
        this.policyResolutionService = policyResolutionService;
        this.emailTemplates = emailTemplates;
        this.emailService = emailService;
        this.mailProperties = mailProperties;
    }

    /** What one sweep did, for the log and for the tests. */
    public record Sweep(int considered, int due, int reminded) {
        static Sweep nothing() {
            return new Sweep(0, 0, 0);
        }
    }

    /**
     * One pass over everybody.
     *
     * <p>Read-only on the hot path: the great majority of calls find nobody
     * due and touch nothing. Writes happen through
     * {@link NotificationService}, which opens its own transaction.
     */
    @Transactional(readOnly = true)
    public Sweep sweep(Instant now) {
        List<Employee> population = employeeRepository.findAll().stream()
                .filter(Employee::isEmployable)
                .toList();

        if (population.isEmpty()) {
            return Sweep.nothing();
        }

        // One date for everybody, in head office's timezone. Every office on
        // this deployment is Asia/Kolkata, so this is exact today. It would
        // stop being exact the day an office opens in another zone, around
        // the hours where the two disagree on the date -- the individual
        // timings below are already resolved per office, so it is this line
        // that would need to become per-office, not those.
        LocalDate today = DateTimeUtils.localDate(now, DateTimeUtils.FALLBACK_ZONE);

        List<DayStateResolver.MemberDay> days = dayStateResolver.resolveDays(population, today);

        int due = 0;
        int reminded = 0;

        for (DayStateResolver.MemberDay day : days) {
            if (day.state() != DayState.NOT_YET_IN) {
                continue;
            }
            Employee employee = day.employee();

            AttendancePolicy policy;
            try {
                policy = policyResolutionService.resolveAttendancePolicy(employee, today);
            } catch (RuntimeException noPolicy) {
                // Resolution throws when an employee matches no assignment.
                // One misconfigured person must not cost everybody else their
                // reminder, so it is logged against that person and the sweep
                // carries on.
                log.warn("No attendance policy resolves for {}; not reminded",
                        employee.getEmployeeCode());
                continue;
            }

            if (!policy.isPunchInReminderEnabled()) {
                continue;
            }
            if (!isDue(employee, today, now, day.shift(), policy.getPunchInReminderDelayMinutes())) {
                continue;
            }
            due++;

            try {
                if (remind(employee, today, day.shift())) {
                    reminded++;
                }
            } catch (RuntimeException failed) {
                // Same reasoning one level down: a bad address or a missing
                // user account is that person's problem, not the sweep's.
                log.warn("Could not remind {}", employee.getEmployeeCode(), failed);
            }
        }

        if (reminded > 0) {
            log.info("Punch-in reminders: {} of {} people had not punched in and were due; "
                    + "{} reminded", due, days.size(), reminded);
        }
        return new Sweep(days.size(), due, reminded);
    }

    /**
     * Whether enough of this person's own morning has passed.
     *
     * <p>Someone with no shift on record is never chased. The alternative --
     * falling back to a company-wide time -- would mail them at an hour that
     * has nothing to do with when they are expected, and a missing shift is a
     * data problem to fix rather than a reason to send mail.
     */
    private boolean isDue(Employee employee, LocalDate date, Instant now,
                          Shift shift, short delayMinutes) {
        if (shift == null) {
            return false;
        }
        ZoneId zone = DateTimeUtils.zoneOf(employee);
        Instant dueAt = DateTimeUtils.shiftStartInstant(date, zone, shift)
                .plus(Duration.ofMinutes(delayMinutes));
        return !now.isBefore(dueAt);
    }

    /**
     * Sends one reminder, if this person has not already had today's.
     *
     * @return whether a reminder actually went out
     */
    private boolean remind(Employee employee, LocalDate date, Shift shift) {
        Optional<User> account = userRepository.findByEmployeeId(employee.getId());
        if (account.isEmpty() || !account.get().isActive()) {
            // No login means no way to act on the reminder either.
            return false;
        }
        Long userId = account.get().getId();
        String forDay = date.toString();

        if (notificationRepository.existsByRecipientUserIdAndTypeAndEntityId(
                userId, NotificationType.ATTENDANCE_PUNCH_IN_REMINDER, forDay)) {
            return false;
        }

        String shiftStart = shift.getStartTime() == null
                ? "the start of your shift"
                : shift.getStartTime().format(CLOCK);
        String link = mailProperties.link("/dashboard");

        // The in-app notification is written first, and it is what stops a
        // second send. Writing it after the mail would mean a crash between
        // the two leaves no record, and the next sweep mails the same person
        // again.
        notificationService.notifyUser(
                userId,
                NotificationType.ATTENDANCE_PUNCH_IN_REMINDER,
                "You have not punched in today",
                "Your shift started at " + shiftStart + ". Punch in to record the day.",
                "/dashboard",
                "ATTENDANCE_DATE",
                forDay,
                null,
                "Attendance Pro");

        // notifyUser swallows its own failures, by design -- a notification
        // must never roll back the thing it describes. Here that generosity
        // would cost something: with no row written, nothing stops the next
        // sweep, and the mail would go out again every five minutes until the
        // person punched in. So the record is confirmed before the message
        // that depends on it leaves.
        if (!notificationRepository.existsByRecipientUserIdAndTypeAndEntityId(
                userId, NotificationType.ATTENDANCE_PUNCH_IN_REMINDER, forDay)) {
            log.error("Could not record the reminder for {}; not sending mail, "
                    + "because nothing would stop it being sent again",
                    employee.getEmployeeCode());
            return false;
        }

        EmailTemplates.Message message =
                emailTemplates.punchInReminder(firstNameOf(employee), shiftStart, link);
        emailService.send(employee.getOfficialEmail(), message.subject(),
                message.html(), message.text());

        return true;
    }

    private String firstNameOf(Employee employee) {
        String first = employee.getFirstName();
        return first == null || first.isBlank() ? "there" : first;
    }
}
