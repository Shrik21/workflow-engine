package com.orchpilot.pluginserver.model;

import com.orchpilot.workflow.sdk.manifest.PluginManifest;

/**
 * A third-party library a plugin version declares.
 *
 * <p>Recorded, not resolved. A plugin ships its dependencies inside its own archive, which is precisely what lets
 * two plugins use two incompatible versions of the same library without either breaking. The registry keeps the
 * list so an operator can answer "what is in this thing" and so a future check can warn when a plugin expects
 * something from the engine that the engine no longer provides.
 *
 * @param groupId    Maven group
 * @param artifactId Maven artifact
 * @param version    version, as declared by the author
 * @param scope      {@code bundled} when shaded into the archive, {@code provided} when expected from the engine
 */
public record PluginDependency(String groupId, String artifactId, String version, String scope) {

    public static PluginDependency from(PluginManifest.ManifestDependency dependency) {
        return new PluginDependency(dependency.groupId(), dependency.artifactId(),
                dependency.version(), dependency.scope());
    }

    /** @return {@code groupId:artifactId:version} */
    public String coordinate() {
        return groupId + ":" + artifactId + ":" + version;
    }

    /**
     * @return whether the plugin expects the engine to provide this, which is the only kind that can conflict
     *         with the engine's own classpath
     */
    public boolean isProvided() {
        return "provided".equalsIgnoreCase(scope);
    }
}
