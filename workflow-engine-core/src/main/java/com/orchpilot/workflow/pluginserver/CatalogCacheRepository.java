package com.orchpilot.workflow.pluginserver;

import org.springframework.data.mongodb.repository.MongoRepository;

/** The single cached catalogue snapshot. */
public interface CatalogCacheRepository extends MongoRepository<CatalogCache, String> {
}
