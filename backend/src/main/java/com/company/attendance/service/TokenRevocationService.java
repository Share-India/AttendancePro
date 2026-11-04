package com.company.attendance.service;

import com.company.attendance.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Token revocations that must survive the failure that triggered them.
 *
 * <p>Refresh-token theft detection ends in a thrown exception. If the
 * revocation were written in the caller's transaction, the rollback would undo
 * it and the stolen token would remain valid — the opposite of what the check
 * exists to do. {@code REQUIRES_NEW} commits the revocation independently, and
 * Spring only honours that through a proxy, so this cannot live as a private
 * method on the auth service.
 */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public TokenRevocationService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** Revokes every live token in a rotation chain. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(String familyId, String reason) {
        int revoked = refreshTokenRepository.revokeFamily(familyId, Instant.now(), reason);
        log.warn("Revoked {} token(s) in family {} ({})", revoked, familyId, reason);
        return revoked;
    }

    /** Revokes every live session for a user. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(Long userId, String reason) {
        int revoked = refreshTokenRepository.revokeAllForUser(userId, Instant.now(), reason);
        log.info("Revoked {} session(s) for user {} ({})", revoked, userId, reason);
        return revoked;
    }
}
