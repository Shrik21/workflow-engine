package com.orchpilot.workflow.ai.cli;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Storage for AI CLI configurations.
 *
 * <p>Every finder is scoped by tenant. There is deliberately no {@code findById} on this interface beyond the
 * inherited one: the service always re-checks the tenant after loading, because an id alone must not be enough
 * to read another tenant's configuration — which would disclose the engine host's filesystem layout.
 */
public interface AiCliConfigurationRepository extends MongoRepository<AiCliConfiguration, String> {

    List<AiCliConfiguration> findByTenantIdOrderByNameAsc(String tenantId);

    List<AiCliConfiguration> findByTenantIdAndDefaultConfigurationIsTrue(String tenantId);

    Optional<AiCliConfiguration> findByTenantIdAndName(String tenantId, String name);

    List<AiCliConfiguration> findByTenantIdAndTypeAndEnabledIsTrue(String tenantId, String type);
}
