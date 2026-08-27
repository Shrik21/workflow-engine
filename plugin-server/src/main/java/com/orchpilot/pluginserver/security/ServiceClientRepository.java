package com.orchpilot.pluginserver.security;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/** Registered service clients, keyed by client id. */
public interface ServiceClientRepository extends MongoRepository<ServiceClient, String> {

    List<ServiceClient> findByEnabledTrue();

    long countByEnabledTrue();
}
