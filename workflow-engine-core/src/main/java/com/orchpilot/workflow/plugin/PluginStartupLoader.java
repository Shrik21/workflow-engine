package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Reloads plugins that were active when the engine last ran.
 *
 * <p>Plugins live in MongoDB, not on the classpath, so "which plugins does this engine have" is a database question
 * answered at startup. Without this, a restart would silently lose every integration and every published workflow
 * depending on one would begin failing.
 *
 * <p>A plugin that fails to load does not stop the engine from starting. Its version is marked {@code FAILED} with
 * the reason recorded, {@code GET /api/plugins} shows it, and workflows that need it fail with a message naming it.
 * The alternative, refusing to start, would let one bad third-party JAR take down the whole platform.
 */
@Component
@Order(100)
public class PluginStartupLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PluginStartupLoader.class);

    private final PluginManager pluginManager;
    private final WorkflowEngineProperties properties;

    public PluginStartupLoader(PluginManager pluginManager, WorkflowEngineProperties properties) {
        this.pluginManager = pluginManager;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getPlugins().isAutoLoadOnStartup()) {
            log.info("Plugin auto-load is disabled; no plugins will be loaded until activated through the API");
            return;
        }
        long start = System.currentTimeMillis();
        int loaded = pluginManager.loadActiveVersions();
        log.info("Plugin startup load finished: {} version(s) in {} ms", loaded,
                System.currentTimeMillis() - start);
    }
}
