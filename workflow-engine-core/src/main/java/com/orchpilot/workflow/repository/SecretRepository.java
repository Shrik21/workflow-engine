package com.orchpilot.workflow.repository;

import com.orchpilot.workflow.model.SecretRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Access to encrypted credentials. Values are never decrypted at this layer.
 */
@Repository
public interface SecretRepository extends MongoRepository<SecretRecord, String> {
}
