package com.orchpilot.workflow.pluginserver;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * What this engine has installed.
 *
 * <p>A top-level interface. Spring Data does not create proxies for repository interfaces nested inside a container
 * class, and this platform has already lost a startup to discovering that.
 */
public interface InstalledPluginRepository extends MongoRepository<InstalledPlugin, String> {

    List<InstalledPlugin> findAllByOrderByPluginIdAsc();
}
