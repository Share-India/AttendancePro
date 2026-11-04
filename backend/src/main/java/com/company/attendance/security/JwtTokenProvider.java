package com.company.attendance.security;

import com.company.attendance.config.JwtProperties;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and verifies the short-lived access token. Roles and permissions are
 * embedded as claims so authorization costs no database read per request; the
 * trade is that a permission change takes effect at the next refresh (at most
 * the access-token lifetime), or immediately via logout-all.
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private static final String CLAIM_EMPLOYEE_ID = "eid";
    private static final String CLAIM_USERNAME = "usr";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMISSIONS = "perms";

    private final SecretKey signingKey;
    private final JwtProperties properties;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        byte[] keyBytes = Decoders.BASE64.decode(properties.secret());
        if (keyBytes.length < 64) {
            throw new IllegalStateException(
                    "app.jwt.secret must decode to at least 64 bytes for HS512. Set the JWT_SECRET environment variable.");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(UserPrincipal principal) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.accessTokenExpirationMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(String.valueOf(principal.getUserId()))
                .issuer(properties.issuer())
                .id(UUID.randomUUID().toString())
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(expiry))
                .claim(CLAIM_EMPLOYEE_ID, principal.getEmployeeId())
                .claim(CLAIM_USERNAME, principal.getUsername())
                .claim(CLAIM_ROLES, List.copyOf(principal.getRoles()))
                .claim(CLAIM_PERMISSIONS, List.copyOf(principal.getPermissions()))
                .signWith(signingKey, Jwts.SIG.HS512)
                .compact();
    }

    public long getAccessTokenExpirySeconds() {
        return properties.accessTokenExpirationMinutes() * 60;
    }

    /**
     * Parses and verifies a token, translating library exceptions into our own
     * error codes so the frontend can distinguish "expired" (refresh and retry)
     * from "invalid" (sign in again).
     */
    public UserPrincipal parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = Long.valueOf(claims.getSubject());
            Number employeeId = claims.get(CLAIM_EMPLOYEE_ID, Number.class);

            return UserPrincipal.fromClaims(
                    userId,
                    employeeId != null ? employeeId.longValue() : null,
                    claims.get(CLAIM_USERNAME, String.class),
                    toStringSet(claims.get(CLAIM_ROLES, List.class)),
                    toStringSet(claims.get(CLAIM_PERMISSIONS, List.class))
            );
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected JWT: {}", e.getMessage());
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> toStringSet(List<?> raw) {
        if (raw == null) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        ((List<Object>) raw).forEach(value -> values.add(String.valueOf(value)));
        return values;
    }
}
