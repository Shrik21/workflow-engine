package com.orchpilot.workflow.portability;

import com.orchpilot.workflow.model.PluginStatus;
import com.orchpilot.workflow.model.PluginVersion;
import com.orchpilot.workflow.repository.PluginVersionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares the plugins a package needs against what this environment has installed.
 *
 * <h2>The compatibility rule, and the two things it will not do</h2>
 *
 * A dependency is satisfied when an installed version of that plugin is greater than or equal to the one the
 * workflow was authored against — a newer plugin is assumed backward-compatible, an older one is not. So it
 * <em>never</em> reports a newer installed plugin as a problem, and it <em>never</em> proposes a downgrade: the
 * specification is explicit that an import must not silently move a plugin backwards, because another workflow
 * on this engine may depend on the newer one. A missing plugin and an out-of-date plugin are reported so the
 * importer can install or update through the plugin server, but this resolver only reads state; it installs
 * nothing.
 */
@Component
public class PluginDependencyResolver {

    /** How an installed plugin measures up to what a package requires. */
    public enum Compatibility {
        /** Installed, at a version at least as new as required. */
        COMPATIBLE,
        /** Installed, but older than required. An update is needed; a downgrade is never proposed. */
        INCOMPATIBLE,
        /** Not installed at all. */
        MISSING
    }

    /**
     * One dependency's status.
     *
     * @param pluginId         the plugin
     * @param requiredVersion  the version the workflow was authored against
     * @param installedVersion the newest installed version, or null when none is installed
     * @param compatibility    the verdict
     */
    public record Result(String pluginId, String requiredVersion, String installedVersion,
                         Compatibility compatibility) {
    }

    private final PluginVersionRepository versions;

    public PluginDependencyResolver(PluginVersionRepository versions) {
        this.versions = versions;
    }

    /**
     * Resolves every dependency of a package.
     *
     * @param dependencies the package's plugin dependencies
     * @return one result per dependency, in the package's order
     */
    public List<Result> resolve(List<WorkflowPackage.PluginDependency> dependencies) {
        List<Result> results = new ArrayList<>();
        for (WorkflowPackage.PluginDependency dependency : dependencies) {
            results.add(resolveOne(dependency));
        }
        return results;
    }

    private Result resolveOne(WorkflowPackage.PluginDependency dependency) {
        String pluginId = dependency.getPluginId();
        String required = dependency.getVersion();

        String newestInstalled = versions.findByPluginIdOrderByUploadedAtDesc(pluginId).stream()
                .filter(version -> version.getStatus() != PluginStatus.DELETED
                        && version.getStatus() != PluginStatus.FAILED)
                .map(PluginVersion::getVersion)
                .max(PluginDependencyResolver::compareVersions)
                .orElse(null);

        if (newestInstalled == null) {
            return new Result(pluginId, required, null, Compatibility.MISSING);
        }
        boolean compatible = required == null || required.isBlank()
                || compareVersions(newestInstalled, required) >= 0;
        return new Result(pluginId, required, newestInstalled,
                compatible ? Compatibility.COMPATIBLE : Compatibility.INCOMPATIBLE);
    }

    /**
     * Compares two dotted version strings numerically, segment by segment.
     *
     * <p>Deliberately simple — {@code 1.10.0} is newer than {@code 1.9.0}, which a string compare gets wrong —
     * and tolerant of non-numeric or missing segments, which sort as zero rather than throwing, because a
     * plugin version this cannot parse should degrade to "treat as equal" rather than fail an import.
     */
    static int compareVersions(String a, String b) {
        String[] left = (a == null ? "" : a).split("[.+-]");
        String[] right = (b == null ? "" : b).split("[.+-]");
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int l = numeric(index < left.length ? left[index] : "0");
            int r = numeric(index < right.length ? right[index] : "0");
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int numeric(String segment) {
        try {
            return Integer.parseInt(segment.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
