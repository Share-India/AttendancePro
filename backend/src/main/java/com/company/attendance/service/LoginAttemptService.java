package com.company.attendance.service;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.config.SecurityProperties;
import com.company.attendance.entity.LoginAttempt;
import com.company.attendance.entity.User;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.repository.LoginAttemptRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.util.IpAddressUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Records authentication attempts and drives the lockout counter.
 *
 * <p>This is a separate bean rather than private methods on the auth service
 * for one reason: a failed login ends in a thrown exception, which rolls the
 * caller's transaction back. If the attempt row and the incremented counter
 * were written in that transaction they would vanish with it, and the lockout
 * would never trigger. {@code REQUIRES_NEW} commits them independently — and
 * Spring only applies that through a proxy, so self-invocation would not work.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final LoginAttemptRepository loginAttemptRepository;
    private final UserRepository userRepository;
    private final SecurityProperties securityProperties;
    private final AuditLogService auditLogService;

    public LoginAttemptService(LoginAttemptRepository loginAttemptRepository,
                               UserRepository userRepository,
                               SecurityProperties securityProperties,
                               AuditLogService auditLogService) {
        this.loginAttemptRepository = loginAttemptRepository;
        this.userRepository = userRepository;
        this.securityProperties = securityProperties;
        this.auditLogService = auditLogService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String identifier, Long userId, boolean successful, String failureReason) {
        try {
            HttpServletRequest request = currentRequest();
            LoginAttempt attempt = new LoginAttempt();
            attempt.setUsernameAttempted(truncate(identifier, 150));
            attempt.setUserId(userId);
            attempt.setSuccessful(successful);
            attempt.setFailureReason(failureReason);
            attempt.setIpAddress(IpAddressUtils.resolve(request));
            attempt.setUserAgent(IpAddressUtils.userAgent(request));
            attempt.setAttemptedAt(Instant.now());
            loginAttemptRepository.save(attempt);
        } catch (Exception e) {
            log.error("Could not record login attempt for {}: {}", identifier, e.getMessage());
        }
    }

    /**
     * Increments the failure counter and locks the account once it reaches the
     * configured threshold. Committed independently of the failing login.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailure(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            short attempts = (short) (user.getFailedLoginAttempts() + 1);
            user.setFailedLoginAttempts(attempts);

            if (attempts >= securityProperties.maxFailedLoginAttempts()) {
                user.setLocked(true);
                user.setLockedUntil(Instant.now()
                        .plus(securityProperties.accountLockoutMinutes(), ChronoUnit.MINUTES));
                log.warn("Account {} locked after {} consecutive failed attempts", user.getUsername(), attempts);
                auditLogService.recordAuthEvent(AuditAction.ACCOUNT_LOCKED, user.getId(), user.getUsername(),
                        "Locked after " + attempts + " consecutive failed attempts");
            }
            userRepository.save(user);
        });
    }

    /** Clears the counter after a successful sign-in. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearFailures(Long userId, String ipAddress) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setFailedLoginAttempts((short) 0);
            user.setLocked(false);
            user.setLockedUntil(null);
            user.setLastLoginAt(Instant.now());
            user.setLastLoginIp(ipAddress);
            userRepository.save(user);
        });
    }

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes.getRequest()
                : null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
