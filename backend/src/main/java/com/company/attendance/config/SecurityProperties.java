package com.company.attendance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        int maxFailedLoginAttempts,
        int accountLockoutMinutes,
        int passwordResetTokenValidityMinutes,
        int bcryptStrength,
        String refreshCookieName,
        boolean refreshCookieSecure,
        String refreshCookieSameSite
) {
}
