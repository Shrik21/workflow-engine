package com.orchpilot.workflow.externalform;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Persistence for {@link ExternalFormAccessToken}. Lookups are by hash, never by raw token. */
public interface ExternalFormAccessTokenRepository extends MongoRepository<ExternalFormAccessToken, String> {

    /** The one live-or-dead token for a hash; the unique index guarantees at most one. */
    Optional<ExternalFormAccessToken> findByTokenHash(String tokenHash);

    /** Every token minted for a task, newest first — for the management view and for regeneration. */
    List<ExternalFormAccessToken> findByTaskIdOrderByCreatedAtDesc(String taskId);

    /** The active tokens for a task, so regeneration can revoke whatever is still live. */
    List<ExternalFormAccessToken> findByTaskIdAndStatus(String taskId, ExternalFormTokenStatus status);
}
