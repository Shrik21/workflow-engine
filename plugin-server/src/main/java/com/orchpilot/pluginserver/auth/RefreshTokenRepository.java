package com.orchpilot.pluginserver.auth;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Refresh tokens, looked up by hash. A top-level interface, as every repository here is. */
public interface RefreshTokenRepository extends MongoRepository<RefreshTokenRecord, String> {

    Optional<RefreshTokenRecord> findByTokenHash(String tokenHash);

    List<RefreshTokenRecord> findByUserIdAndRevokedFalse(String userId);

    List<RefreshTokenRecord> findByUserId(String userId);
}
