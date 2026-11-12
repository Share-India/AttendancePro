package com.company.attendance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        int maxFailedLoginAttempts,
        int accountLockoutMinutes,
        int passwordResetTokenValidityMinutes,
        /**
         * How long a new joiner's "set your password" link stays good. Longer
         * than a reset on purpose — see application.yml.
         */
        int setupTokenValidityHours,
        int bcryptStrength,
        String refreshCookieName,
        boolean refreshCookieSecure,
        String refreshCookieSameSite
) {
}
