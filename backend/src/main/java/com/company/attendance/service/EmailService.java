package com.company.attendance.service;

import com.company.attendance.config.MailProperties;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Sends the application's mail.
 *
 * <p>Three things here are deliberate and each exists because of a specific
 * way mail breaks an application that sends it.
 *
 * <p><strong>Never throws.</strong> A failure to send is logged and swallowed.
 * Mail is a notification about work that has already happened: the leave was
 * approved, the account was created, the password was reset. Letting a dead
 * SMTP server roll back an approval that has already been recorded would turn
 * an inconvenience into data loss, and the caller has no sensible way to
 * recover anyway.
 *
 * <p><strong>Sent off the request thread.</strong> {@code @Async} keeps an SMTP
 * round trip out of the time an employee waits for a page. With the timeouts
 * set in application.yml a dead host costs 5 seconds; on a bulk import of
 * forty people that is three minutes added to a request that would otherwise
 * finish in one.
 *
 * <p><strong>Off by default.</strong> With {@code app.mail.enabled} false the
 * message is logged instead of sent, so the whole flow can be exercised —
 * including the exact link somebody would have clicked — without a mail server
 * and without reaching a real inbox.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    public EmailService(JavaMailSender mailSender, MailProperties mailProperties) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
    }

    /**
     * Sends one message, as HTML with a plain-text alternative.
     *
     * @param to      recipient address; a blank one is skipped rather than sent
     *                to the server to be rejected
     * @param subject subject line
     * @param html    the HTML body
     * @param text    the plain-text body, for clients that will not render HTML
     */
    @Async
    public void send(String to, String subject, String html, String text) {
        if (to == null || to.isBlank()) {
            log.warn("No address to send '{}' to; skipped", subject);
            return;
        }

        if (!mailProperties.enabled()) {
            // The body is logged at debug, not info: it carries setup links,
            // and an application log is read by more people than an inbox is.
            log.info("Mail disabled; would have sent '{}' to {}", subject, to);
            log.debug("Body of '{}' to {}:\n{}", subject, to, text);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name());

            helper.setFrom(mailProperties.fromHeader());
            helper.setTo(to);
            helper.setSubject(subject);
            // Plain text first, HTML second — that order is what makes the
            // HTML the preferred alternative rather than the fallback.
            helper.setText(text, html);

            mailSender.send(message);
            log.info("Sent '{}' to {}", subject, to);
        } catch (MailException | jakarta.mail.MessagingException e) {
            // Deliberately not rethrown. See the class comment: the thing the
            // mail is about has already happened and committed.
            log.error("Could not send '{}' to {}: {}", subject, to, e.getMessage());
        }
    }
}
