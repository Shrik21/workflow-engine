package com.orchpilot.workflow.ai.connection;

import com.orchpilot.workflow.ai.AIProviderType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/** Persistence for {@link AIProviderConnection}. */
public interface AIProviderConnectionRepository extends MongoRepository<AIProviderConnection, String> {

    List<AIProviderConnection> findByProviderType(AIProviderType providerType);

    List<AIProviderConnection> findByEnabledTrue();
}
