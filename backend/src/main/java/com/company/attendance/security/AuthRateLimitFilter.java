package com.company.attendance.security;

import com.company.attendance.util.IpAddressUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Slows down bulk attacks on the endpoints that accept credentials.
 *
 * <p>Account lockout already covers one attacker guessing at one account. It
 * does nothing about the opposite shape — one common password tried against a
 * thousand usernames — because no single account ever reaches its failure
 * limit. That attack is bounded by requests per source, which is what this
 * counts.
 *
 * <p>Deliberately in memory, and deliberately per instance. A shared limiter
 * behind Redis is the right answer for a fleet, but an in-memory one that
 * works today is worth more than a distributed one that is still a TODO. The
 * map is bounded and swept, so it cannot itself become the denial of service.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    /** Only the endpoints that accept credentials or send mail. */
    private static final Set<String> GUARDED = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password");

    /**
     * Above this many tracked sources the map is swept. Chosen so a normal
     * office never reaches it and a spray from a botnet cannot grow the map
     * without bound.
     */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final ObjectMapper objectMapper;
    private final int maxRequests;
    private final long windowSeconds;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AuthRateLimitFilter(ObjectMapper objectMapper,
                               @Value("${app.security.auth-rate-limit.max-requests:20}") int maxRequests,
                               @Value("${app.security.auth-rate-limit.window-seconds:60}") long windowSeconds) {
        this.objectMapper = objectMapper;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    /** One source's count, and when its window started. */
    private static final class Window {
        private final AtomicInteger count = new AtomicInteger();
        private volatile Instant startedAt = Instant.now();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !GUARDED.contains(request.getRequestURI())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String source = IpAddressUtils.resolve(request);
        if (source == null || source.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        if (isOverLimit(source)) {
            // Logged at warn: a source hitting this is either under attack or
            // is the attack, and both are worth seeing in the log.
            log.warn("Rate limit reached for {} on {}", source, request.getRequestURI());
            reject(response);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isOverLimit(String source) {
        sweepIfCrowded();

        Window window = windows.computeIfAbsent(source, key -> new Window());

        synchronized (window) {
            if (window.startedAt.plusSeconds(windowSeconds).isBefore(Instant.now())) {
                window.startedAt = Instant.now();
                window.count.set(0);
            }
            return window.count.incrementAndGet() > maxRequests;
        }
    }

    /** Drops windows that have expired, so the map tracks only live sources. */
    private void sweepIfCrowded() {
        if (windows.size() < SWEEP_THRESHOLD) {
            return;
        }
        Instant cutoff = Instant.now().minusSeconds(windowSeconds);
        windows.entrySet().removeIf(entry -> entry.getValue().startedAt.isBefore(cutoff));
    }

    /**
     * Answers in the same envelope every other error uses, and says nothing
     * about whether the credentials were valid — a rate-limit response that
     * differed by account would be an enumeration oracle of its own.
     */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(windowSeconds));

        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "success", false,
                "errorCode", "RATE_LIMITED",
                "message", "Too many attempts. Wait " + windowSeconds + " seconds and try again."));
    }
}
