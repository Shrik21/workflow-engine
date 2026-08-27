package com.orchpilot.workflow.pluginserver;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * The installation history.
 *
 * <p>A top-level interface. Spring Data does not create proxies for repository interfaces nested inside a container
 * class, and this platform has already lost a startup to discovering that.
 */
public interface PluginInstallationRepository extends MongoRepository<PluginInstallation, String> {

    /** One plugin's history, newest first. */
    List<PluginInstallation> findByPluginIdOrderByAtDesc(String pluginId);

    /** The whole engine's recent history, newest first. */
    List<PluginInstallation> findTop100ByOrderByAtDesc();
}
