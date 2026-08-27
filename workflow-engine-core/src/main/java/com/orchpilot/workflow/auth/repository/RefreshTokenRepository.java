package com.orchpilot.workflow.auth.repository;

import com.orchpilot.workflow.auth.model.RefreshToken;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Refresh tokens, addressed only by hash.
 *
 * <p>There is intentionally no {@code findByToken}: the raw token is never stored, so no such query
 * could exist. Callers hash first, then look up.
 */
public interface RefreshTokenRepository extends MongoRepository<RefreshToken, String> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedFalse(String userId);

    /**
     * @param familyId rotation chain identifier
     * @return every live token in the chain, for revoking a family after a detected replay
     */
    List<RefreshToken> findByFamilyIdAndRevokedFalse(String familyId);

    long countByUserIdAndRevokedFalse(String userId);

    void deleteByUserId(String userId);
}
