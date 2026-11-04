package com.company.attendance.config;

import com.company.attendance.security.UserPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Feeds createdBy / updatedBy on every entity from the authenticated principal.
 * Returns empty for unauthenticated writes (migrations, schedulers), which is
 * why those columns are nullable.
 */
@Configuration
public class AuditorAwareConfig {

    @Bean
    public AuditorAware<Long> auditorAware() {
        return () -> Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(auth -> auth.isAuthenticated())
                .map(auth -> auth.getPrincipal())
                .filter(UserPrincipal.class::isInstance)
                .map(UserPrincipal.class::cast)
                .map(UserPrincipal::getUserId);
    }
}
