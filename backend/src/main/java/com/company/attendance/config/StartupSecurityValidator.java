package com.company.attendance.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import com.company.attendance.repository.UserRepository;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Refuses to serve traffic in an unsafe configuration.
 *
 * <p>Every check here exists because the safe value is easy to forget and the
 * consequence of forgetting is silent. A missing {@code JWT_SECRET} does not
 * crash anything — the application starts perfectly well on the placeholder
 * committed in {@code application.yml}, and anyone who can read this repository
 * can then mint a token for any employee in the company. A misconfiguration
 * that fails loudly on startup costs someone ten minutes; one that fails
 * silently costs considerably more.
 *
 * <p>Deliberately scoped to real deployments. Development and tests keep the
 * convenient defaults, because a local checkout that will not start is its own
 * kind of problem.
 */
@Component
public class StartupSecurityValidator implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityValidator.class);

    /** Profiles where convenience beats strictness. Everything else is a deployment. */
    private static final Set<String> RELAXED_PROFILES = Set.of("dev", "test", "local");

    /**
     * The placeholder in application.yml. It is checked by value rather than by
     * "was the environment variable set", because the failure that matters is
     * this exact string reaching production, however it got there.
     */
    private static final String KNOWN_DEFAULT_SECRET =
            "ZGV2LW9ubHktc2VjcmV0LWRvLW5vdC11c2UtaW4tcHJvZHVjdGlvbi1jaGFuZ2UtbWUtbm93LTEyMzQ1Njc4OTA=";

    /** HS512 signs with a 512-bit key; anything shorter weakens the algorithm. */
    private static final int MIN_SECRET_BYTES = 64;

    /**
     * The bootstrap administrator seeded by V6, and the BCrypt hash of the
     * password documented alongside it.
     *
     * <p>{@code must_change_password} stops the account being used, but it does
     * not stop anyone using the published password to sign in and rotate it
     * themselves — which hands over a SUPER_ADMIN account. The credential is in
     * a committed migration, so it must be treated as public.
     */
    private static final String BOOTSTRAP_USERNAME = "EMP0001";
    private static final String BOOTSTRAP_PASSWORD_HASH =
            "$2a$12$RJKZwn5VqVvPkuoCGWpbBu6q/hjaNVePEs/wFWFH1v9.mHPxcBCIy";

    private final Environment environment;
    private final JwtProperties jwtProperties;
    private final SecurityProperties securityProperties;
    private final CorsProperties corsProperties;
    private final UserRepository userRepository;

    public StartupSecurityValidator(Environment environment,
                                    JwtProperties jwtProperties,
                                    SecurityProperties securityProperties,
                                    CorsProperties corsProperties,
                                    UserRepository userRepository) {
        this.environment = environment;
        this.jwtProperties = jwtProperties;
        this.securityProperties = securityProperties;
        this.corsProperties = corsProperties;
        this.userRepository = userRepository;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<String> problems = new ArrayList<>(check(activeProfiles()));
        problems.addAll(checkBootstrapAccount());

        if (problems.isEmpty()) {
            return;
        }

        String message = "Refusing to run with this configuration:\n  - "
                + String.join("\n  - ", problems);

        if (isRelaxed(activeProfiles())) {
            // Locally these are warnings: a checkout that will not start is
            // its own problem, and nobody is at risk on a laptop.
            log.warn("{}\n(These are warnings because a relaxed profile is active.)", message);
            return;
        }

        log.error(message);
        throw new IllegalStateException(message);
    }

    /** Extracted so it can be exercised directly, profile by profile. */
    public List<String> check(List<String> activeProfiles) {
        List<String> problems = new ArrayList<>();

        String secret = jwtProperties.secret();

        if (secret == null || secret.isBlank()) {
            problems.add("JWT_SECRET is not set.");
        } else {
            if (KNOWN_DEFAULT_SECRET.equals(secret.trim())) {
                problems.add("JWT_SECRET is still the placeholder committed in application.yml. "
                        + "Anyone with access to this source can forge a token for any user. "
                        + "Generate one with: openssl rand -base64 64");
            }
            int bytes = decodedLength(secret);
            if (bytes < MIN_SECRET_BYTES) {
                problems.add("JWT_SECRET decodes to " + bytes + " bytes; HS512 needs at least "
                        + MIN_SECRET_BYTES + ". Generate one with: openssl rand -base64 64");
            }
        }

        if (!securityProperties.refreshCookieSecure()) {
            problems.add("app.security.refresh-cookie-secure is false, so the refresh cookie "
                    + "will be sent over plain HTTP. Set REFRESH_COOKIE_SECURE=true.");
        }

        List<String> origins = corsProperties.allowedOrigins();
        if (origins != null && origins.stream().anyMatch(origin -> "*".equals(origin.trim()))) {
            problems.add("CORS allows any origin while credentials are enabled. "
                    + "Set CORS_ALLOWED_ORIGINS to the exact front-end origins.");
        }
        if (origins != null && origins.stream().anyMatch(origin -> origin.startsWith("http://")
                && !origin.contains("localhost") && !origin.contains("127.0.0.1"))) {
            problems.add("CORS allows a plain-HTTP origin: " + origins
                    + ". Tokens would travel unencrypted.");
        }

        if (environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, false)) {
            problems.add("Swagger UI is enabled. It publishes the whole API surface; "
                    + "set SWAGGER_ENABLED=false.");
        }

        if (securityProperties.bcryptStrength() < 10) {
            problems.add("BCrypt strength is " + securityProperties.bcryptStrength()
                    + ", which is too cheap to slow down an offline crack. Use 12 or more.");
        }

        return problems;
    }

    /**
     * Checked separately because it needs the database, which the pure
     * configuration checks deliberately do not.
     */
    private List<String> checkBootstrapAccount() {
        try {
            boolean untouched = userRepository.findByUsernameIgnoreCase(BOOTSTRAP_USERNAME)
                    .map(user -> BOOTSTRAP_PASSWORD_HASH.equals(user.getPasswordHash()))
                    .orElse(false);

            if (untouched) {
                return List.of("The bootstrap administrator " + BOOTSTRAP_USERNAME
                        + " still has the password published in V6__seed_reference_data.sql. "
                        + "Anyone can sign in with it and set a new one, taking the account. "
                        + "Rotate it, or disable the account.");
            }
        } catch (Exception e) {
            // Never let this check itself be the reason the app will not serve.
            log.warn("Could not check the bootstrap account", e);
        }
        return List.of();
    }

    private boolean isRelaxed(List<String> activeProfiles) {
        // No profile at all means someone is running it straight from an IDE.
        return activeProfiles.isEmpty()
                || activeProfiles.stream().anyMatch(RELAXED_PROFILES::contains);
    }

    private List<String> activeProfiles() {
        return List.of(environment.getActiveProfiles());
    }

    /**
     * How many bytes the secret actually provides.
     *
     * <p>Base64 where possible, raw bytes otherwise — a secret that is not
     * base64 is still a secret, and rejecting it for the wrong reason would
     * send someone looking in the wrong place.
     */
    private int decodedLength(String secret) {
        try {
            return Base64.getDecoder().decode(secret).length;
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(StandardCharsets.UTF_8).length;
        }
    }
}
