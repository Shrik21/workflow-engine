package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.sdk.context.PluginSettings;
import com.orchpilot.workflow.utility.MapPaths;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Installation-scoped plugin settings backed by an immutable snapshot of the version document.
 *
 * <p>A snapshot rather than a live view: a plugin's configuration must not change under it between two
 * nodes of the same workflow. Changing settings requires a reload, which is an explicit, auditable act.
 */
public class MapPluginSettings implements PluginSettings {

    private final Map<String, Object> settings;

    /**
     * @param settings settings from the plugin version document, may be {@code null}
     */
    public MapPluginSettings(Map<String, Object> settings) {
        this.settings = settings == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    @Override
    public Optional<Object> find(String key) {
        return MapPaths.find(settings, key);
    }

    @Override
    public Map<String, Object> asMap() {
        return settings;
    }
}
