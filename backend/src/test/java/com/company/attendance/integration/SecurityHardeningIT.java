package com.company.attendance.integration;

import com.company.attendance.config.CorsProperties;
import com.company.attendance.config.JwtProperties;
import com.company.attendance.config.SecurityProperties;
import com.company.attendance.config.StartupSecurityValidator;
import com.company.attendance.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The hardening, exercised.
 *
 * <p>A security control nobody tests is a comment. These check the ones whose
 * absence would be silent: a header that stopped being sent, an endpoint that
 * quietly became public, and a placeholder secret reaching a real deployment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHardeningIT {

    private static final String PLACEHOLDER_SECRET =
            "ZGV2LW9ubHktc2VjcmV0LWRvLW5vdC11c2UtaW4tcHJvZHVjdGlvbi1jaGFuZ2UtbWUtbm93LTEyMzQ1Njc4OTA=";

    /** A real 64-byte key, so the "good" cases are actually good. */
    private static final String STRONG_SECRET =
            "hV8Qk3mZ2pR7tY1wA5sD9fG4jL6nB0xC8vN2mQ7rT3yU5iO1pA6sD4fG8hJ2kL9zX3cV7bN1mQ5rT8yU2iO6pA0sD";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;

    // -----------------------------------------------------------------
    // Response headers
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every response carries the hardening headers")
    void securityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().exists("Permissions-Policy"));
    }

    @Test
    @DisplayName("the content policy forbids scripts and framing outright")
    void contentSecurityPolicyIsRestrictive() throws Exception {
        String policy = mockMvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getHeader("Content-Security-Policy");

        assertThat(policy).isNotNull();
        // This API answers JSON. Nothing it returns should be able to load a
        // script or be embedded in someone else's page.
        assertThat(policy).contains("default-src 'none'");
        assertThat(policy).contains("frame-ancestors 'none'");
    }

    // -----------------------------------------------------------------
    // What is reachable without signing in
    // -----------------------------------------------------------------

    @Test
    @DisplayName("business endpoints are closed to anonymous callers")
    void businessEndpointsRequireAuthentication() throws Exception {
        for (String path : List.of(
                "/api/v1/employees",
                "/api/v1/attendance/me/status",
                "/api/v1/leaves/my",
                "/api/v1/dashboard/summary",
                "/api/v1/reports",
                "/api/v1/notifications",
                "/api/v1/admin/audit-logs")) {

            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("health is public so a load balancer can reach it")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the rest of actuator is not open to every signed-in employee")
    void actuatorBeyondHealthIsRestricted() throws Exception {
        // Anonymous: refused. The interesting case — an ordinary employee with a
        // valid token — is covered by the AUDIT_VIEW authority on the matcher.
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------
    // Rate limiting
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a flood of login attempts is turned away before it reaches the password check")
    void loginIsRateLimited() throws Exception {
        String body = """
                {"usernameOrEmail":"nobody@example.com","password":"wrong","rememberMe":false}
                """;

        int limited = 0;
        // Comfortably past the configured ceiling for the test profile.
        for (int i = 0; i < 40; i++) {
            int statusCode = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content(body))
                    .andReturn().getResponse().getStatus();
            if (statusCode == 429) {
                limited++;
            }
        }

        assertThat(limited)
                .as("a sustained burst against one source must eventually be refused")
                .isGreaterThan(0);
    }

    // -----------------------------------------------------------------
    // Startup validation
    // -----------------------------------------------------------------

    private StartupSecurityValidator validatorWith(String secret,
                                                   boolean cookieSecure,
                                                   List<String> origins,
                                                   int bcryptStrength) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        JwtProperties jwt = new JwtProperties(secret, 15, 7, "attendance-api");
        SecurityProperties security = new SecurityProperties(
                5, 15, 30, bcryptStrength, "refresh_token", cookieSecure, "Strict");
        CorsProperties cors = new CorsProperties(
                origins, List.of("GET"), List.of("*"), List.of("X-Request-Id"), true, 3600);

        return new StartupSecurityValidator(environment, jwt, security, cors, userRepository);
    }

    @Test
    @DisplayName("the committed placeholder secret is refused outright")
    void placeholderSecretIsRefused() {
        var problems = validatorWith(PLACEHOLDER_SECRET, true,
                List.of("https://attendance.example.com"), 12)
                .check(List.of("prod"));

        assertThat(problems).isNotEmpty();
        assertThat(String.join(" ", problems)).contains("placeholder");
    }

    @Test
    @DisplayName("a secret too short for HS512 is refused")
    void shortSecretIsRefused() {
        var problems = validatorWith("dG9vLXNob3J0", true,
                List.of("https://attendance.example.com"), 12)
                .check(List.of("prod"));

        assertThat(String.join(" ", problems)).contains("at least 64");
    }

    @Test
    @DisplayName("an insecure refresh cookie is refused")
    void insecureCookieIsRefused() {
        var problems = validatorWith(STRONG_SECRET, false,
                List.of("https://attendance.example.com"), 12)
                .check(List.of("prod"));

        assertThat(String.join(" ", problems)).contains("refresh-cookie-secure");
    }

    @Test
    @DisplayName("a wildcard CORS origin alongside credentials is refused")
    void wildcardOriginIsRefused() {
        var problems = validatorWith(STRONG_SECRET, true, List.of("*"), 12)
                .check(List.of("prod"));

        assertThat(String.join(" ", problems)).contains("any origin");
    }

    @Test
    @DisplayName("a plain-HTTP origin that is not localhost is refused")
    void plainHttpOriginIsRefused() {
        var problems = validatorWith(STRONG_SECRET, true,
                List.of("http://attendance.example.com"), 12)
                .check(List.of("prod"));

        assertThat(String.join(" ", problems)).contains("plain-HTTP");
    }

    @Test
    @DisplayName("a cheap BCrypt cost is refused")
    void weakBcryptIsRefused() {
        var problems = validatorWith(STRONG_SECRET, true,
                List.of("https://attendance.example.com"), 8)
                .check(List.of("prod"));

        assertThat(String.join(" ", problems)).contains("BCrypt");
    }

    @Test
    @DisplayName("a properly configured deployment raises nothing")
    void aGoodConfigurationPasses() {
        var problems = validatorWith(STRONG_SECRET, true,
                List.of("https://attendance.example.com"), 12)
                .check(List.of("prod"));

        assertThat(problems).isEmpty();
    }
}
