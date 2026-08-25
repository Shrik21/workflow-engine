package com.orchpilot.workflow.storage.repository;

import com.orchpilot.workflow.storage.model.WorkflowStorageSettings;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;

/**
 * Access to the one settings document per tenant.
 *
 * <p>The single-tenant lookup needs its own query because Spring Data turns a {@code null} argument on a derived
 * finder into {@code {tenantId: null}} only by accident of the driver's behaviour; spelling the query out makes
 * the intent explicit and keeps it working if that behaviour changes.
 */
public interface WorkflowStorageSettingsRepository extends MongoRepository<WorkflowStorageSettings, String> {

    Optional<WorkflowStorageSettings> findByTenantId(String tenantId);

    /** The single-tenant deployment's document: the one with no tenant discriminator. */
    @Query("{ 'tenantId': null }")
    Optional<WorkflowStorageSettings> findDefault();
}
