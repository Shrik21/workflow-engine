package com.orchpilot.pluginserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The plugin registry, storage and distribution service.
 *
 * <h2>What this service is</h2>
 *
 * <p>The authoritative record of which plugins exist, at which versions, with which node types and checksums,
 * and the place their archives are stored. Workflow services ask it what is available and download bytes from
 * it.
 *
 * <h2>What this service is not</h2>
 *
 * <p>A plugin host. It never loads an uploaded archive into a class loader, never instantiates a plugin's main
 * class, and never runs plugin code. Validation reads the declared manifest as data and inspects the archive
 * index. Everything that requires executing a plugin happens in the workflow service, in an isolated class
 * loader, deliberately on the other side of a network boundary from the store of every JAR anyone ever
 * uploaded.
 *
 * <p>That restraint is the security argument for splitting the services at all. A registry that also executed
 * its contents would be a single component holding both every artefact and the ability to run it.
 *
 * <p>Note the absence of {@code @EnableScheduling}: this service has no background work. Catalogue refreshing
 * is the client's concern, which keeps the registry a plain request-response service that can be scaled or
 * restarted without coordinating anything.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PluginServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PluginServerApplication.class, args);
    }
}
