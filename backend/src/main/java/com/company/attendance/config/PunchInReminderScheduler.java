package com.company.attendance.config;

import com.company.attendance.service.PunchInReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The only timer in this application.
 *
 * <p>Everything else settles lazily: an exam's status catches up when somebody
 * reads it, a meeting's does the same, and absence is derived on the spot
 * rather than written by a nightly sweep. That design holds because every one
 * of those answers is a value somebody asks for, so the work can wait until
 * they ask.
 *
 * <p>A reminder is not a value. Nobody opens a screen at 10:00 in a way that
 * should cause mail to leave the building, and the person who needs telling is
 * precisely the one not using the application. The action goes outward and it
 * has to happen at a time, so it needs a clock. One job, kept as small as the
 * requirement allows.
 *
 * <p><strong>No distributed lock.</strong> There is one backend container, so
 * there is one scheduler. If a second is ever run, two sweeps could overlap --
 * the notification row that prevents a repeat is written by the first to
 * commit, and the loser would send a duplicate. Before scaling out, this needs
 * ShedLock or an advisory lock. It is written down here rather than guarded
 * for a case that does not exist, but it is a real edge and not an oversight.
 *
 * <p>Switched off by configuration as well as by the policy. The property
 * keeps the timer from running at all in tests and in a local run; the policy
 * flag is the one HR controls.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.reminders.punch-in.scheduled", havingValue = "true")
public class PunchInReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(PunchInReminderScheduler.class);

    private final PunchInReminderService reminderService;

    public PunchInReminderScheduler(PunchInReminderService reminderService) {
        this.reminderService = reminderService;
    }

    /**
     * Sweeps every five minutes.
     *
     * <p>fixedDelay rather than fixedRate: a slow sweep must not have the next
     * one queued behind it, which is how a scheduler ends up running two at
     * once against the same people.
     *
     * <p>Five minutes is the resolution of the reminder, not its frequency --
     * each person is mailed once, at worst five minutes after they are due.
     * An initial delay keeps it off the startup path, where it would compete
     * with Flyway and the first requests.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    public void sweep() {
        try {
            reminderService.sweep(Instant.now());
        } catch (Exception e) {
            // A scheduled method that throws is simply not run again by some
            // configurations, which would stop reminders silently and for
            // good. Swallowed and logged so the next tick still happens.
            log.error("Punch-in reminder sweep failed; will try again at the next tick", e);
        }
    }
}
