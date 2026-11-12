package com.company.attendance.service;

import com.company.attendance.config.MailProperties;
import com.company.attendance.config.SecurityProperties;
import com.company.attendance.entity.PasswordResetToken;
import com.company.attendance.entity.User;
import com.company.attendance.repository.PasswordResetTokenRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.util.TokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Sends somebody a link to choose their own password.
 *
 * <p>This is how a new joiner gets in, and how an administrator helps somebody
 * who has lost their password. Both cases issue the same kind of one-time
 * token the forgot-password flow already uses — the only difference is how
 * long it lasts and what the mail says.
 *
 * <p><strong>Why a link rather than a password.</strong> A password mailed to
 * somebody stays in their inbox, in the sender's outbox and in two mail
 * servers' logs, indefinitely. A link is spent the moment it is used and is
 * worthless afterwards. It also means no administrator ever knows an
 * employee's working password, which is the property that makes
 * "HR can reset my password" safe to offer.
 *
 * <p>Outstanding tokens are invalidated first. Without that, an employee who
 * asks twice ends up with two live links and the older mail — the one more
 * likely to be found first — still works, which quietly doubles the window in
 * which a forwarded message is enough to take the account.
 */
@Service
public class PasswordSetupService {

    private static final Logger log = LoggerFactory.getLogger(PasswordSetupService.class);

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final SecurityProperties securityProperties;
    private final MailProperties mailProperties;
    private final EmailTemplates templates;
    private final EmailService emailService;

    public PasswordSetupService(PasswordResetTokenRepository tokenRepository,
                                UserRepository userRepository,
                                SecurityProperties securityProperties,
                                MailProperties mailProperties,
                                EmailTemplates templates,
                                EmailService emailService) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.securityProperties = securityProperties;
        this.mailProperties = mailProperties;
        this.templates = templates;
        this.emailService = emailService;
    }

    /**
     * Issues a setup link for a user and mails it to them.
     *
     * @param user    whose account it is for
     * @param isReset false for a new joiner, true when an administrator has
     *                reset an existing account. Only changes the wording — a
     *                new joiner should not be told their password "has been
     *                reset" when they never had one.
     * @return the address it was sent to, or null when the account has none
     */
    @Transactional
    public String sendSetupLink(User user, boolean isReset) {
        String address = addressFor(user);
        if (address == null) {
            // Not an exception: the account is still usable by an
            // administrator issuing a one-time password instead. But it must
            // be visible, or HR believes a mail went out that never did.
            log.warn("No email address for {}; no setup link sent", user.getUsername());
            return null;
        }

        // Read everything off the entity BEFORE invalidating, which is
        // @Modifying(clearAutomatically = true) and so detaches `user`.
        // Touching a lazy association afterwards throws
        // LazyInitializationException — and only for accounts whose employee
        // proxy had not already been resolved, which is the kind of bug that
        // passes every test and fails in production.
        String firstName = firstNameOf(user);
        String username = user.getUsername();
        Long userId = user.getId();

        int validityHours = securityProperties.setupTokenValidityHours();
        String rawToken = TokenHasher.generateToken();

        tokenRepository.invalidateOutstandingForUser(userId, Instant.now());

        PasswordResetToken token = new PasswordResetToken();
        // Detached after the clear above, so re-attach by reference rather
        // than persisting a stale instance.
        token.setUser(userRepository.getReferenceById(userId));
        // Only the hash is stored, so a database dump yields no usable link.
        token.setTokenHash(TokenHasher.hash(rawToken));
        token.setExpiresAt(Instant.now().plus(validityHours, ChronoUnit.HOURS));
        token.setCreatedAt(Instant.now());
        tokenRepository.save(token);

        String link = mailProperties.link("/reset-password?token=" + rawToken);
        var message = templates.passwordSetup(
                firstName, username, link,
                validityHours == 1 ? "1 hour" : validityHours + " hours", isReset);

        emailService.send(address, message.subject(), message.html(), message.text());
        return address;
    }

    /**
     * The address to write to: the employee record's official address, falling
     * back to whatever is on the login. Those are the same for an imported
     * employee, and differ only where somebody has been edited by hand.
     */
    private String addressFor(User user) {
        if (user.getEmployee() != null
                && user.getEmployee().getOfficialEmail() != null
                && !user.getEmployee().getOfficialEmail().isBlank()) {
            return user.getEmployee().getOfficialEmail();
        }
        return user.getEmail() == null || user.getEmail().isBlank() ? null : user.getEmail();
    }

    /** "Hello Asha" reads better than "Hello S0107". */
    private String firstNameOf(User user) {
        if (user.getEmployee() != null && user.getEmployee().getFirstName() != null) {
            return user.getEmployee().getFirstName();
        }
        return user.getUsername();
    }
}
