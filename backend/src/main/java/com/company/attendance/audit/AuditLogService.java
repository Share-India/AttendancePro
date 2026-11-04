package com.company.attendance.audit;

import com.company.attendance.entity.AuditLog;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.repository.AuditLogRepository;
import com.company.attendance.security.RequestIdFilter;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.util.IpAddressUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

/**
 * Writes the audit trail.
 *
 * <p>Every write runs in {@code REQUIRES_NEW} so a rolled-back business
 * transaction still leaves its attempt on record — a failed approval or a
 * rejected login is exactly the kind of event an auditor asks about.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String entityType, Object entityId, String description) {
        record(action, entityType, entityId, description, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String entityType, Object entityId, String description,
                       Object oldValue, Object newValue) {
        try {
            HttpServletRequest request = currentRequest();
            UserPrincipal principal = currentPrincipal();

            AuditLog entry = AuditLog.builder()
                    .actorUserId(principal != null ? principal.getUserId() : null)
                    .actorEmployeeId(principal != null ? principal.getEmployeeId() : null)
                    .actorUsername(principal != null ? principal.getUsername() : null)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId != null ? String.valueOf(entityId) : null)
                    .oldValue(toJson(oldValue))
                    .newValue(toJson(newValue))
                    .description(truncate(description, 500))
                    .ipAddress(IpAddressUtils.resolve(request))
                    .userAgent(IpAddressUtils.userAgent(request))
                    .traceId(MDC.get(RequestIdFilter.MDC_KEY))
                    .createdAt(Instant.now())
                    .build();

            auditLogRepository.save(entry);
        } catch (Exception e) {
            // An audit failure must never break the operation being audited.
            log.error("Failed to write audit entry for {} on {}: {}", action, entityType, e.getMessage(), e);
        }
    }

    /** Used by the auth path, where the actor is known before authentication completes. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthEvent(AuditAction action, Long userId, String username, String description) {
        try {
            HttpServletRequest request = currentRequest();
            AuditLog entry = AuditLog.builder()
                    .actorUserId(userId)
                    .actorUsername(username)
                    .action(action)
                    .entityType("User")
                    .entityId(userId != null ? String.valueOf(userId) : null)
                    .description(truncate(description, 500))
                    .ipAddress(IpAddressUtils.resolve(request))
                    .userAgent(IpAddressUtils.userAgent(request))
                    .traceId(MDC.get(RequestIdFilter.MDC_KEY))
                    .createdAt(Instant.now())
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to write auth audit entry for {}: {}", action, e.getMessage(), e);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise audit value of type {}", value.getClass().getSimpleName());
            return null;
        }
    }

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes.getRequest()
                : null;
    }

    private UserPrincipal currentPrincipal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return principal;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
