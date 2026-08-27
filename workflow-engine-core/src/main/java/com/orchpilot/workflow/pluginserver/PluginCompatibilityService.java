package com.orchpilot.workflow.pluginserver;

import com.orchpilot.workflow.sdk.plugin.PluginApi;
import com.orchpilot.workflow.sdk.version.SemanticVersion;
import com.orchpilot.workflow.sdk.version.VersionRange;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether this engine can run a given plugin version.
 *
 * <h2>Two kinds of check, and only one of them is authoritative</h2>
 *
 * <p>The SDK's integer {@link PluginApi#VERSION} is the real gate, because it is what the class loader and the
 * plugin contract actually depend on: a plugin built against API 2 may call methods this engine does not have, and
 * that fails at link time with a {@code NoSuchMethodError} rather than politely. The semantic {@code sdkVersion} and
 * the declared engine range are advisory in comparison, being strings a plugin author wrote.
 *
 * <p>So the checks are ordered by how much they can be trusted, and the result says which one refused. "Requires SDK
 * 2.x, this engine provides 1.x" is actionable; "incompatible" is not.
 *
 * <h2>Checked before install and again before load</h2>
 *
 * <p>The catalogue can say a plugin is compatible and be out of date by the time somebody clicks install, and an
 * engine can be downgraded under a plugin that was installed when it was compatible. Both paths call this.
 */
@Service
public class PluginCompatibilityService {

    /**
     * This engine's version, for a plugin's declared {@code engineCompatibility} range.
     *
     * <p>A constant rather than the jar's {@code Implementation-Version}, which is absent when running from
     * classes in an IDE and would make compatibility depend on how the engine was started.
     */
    public static final String ENGINE_VERSION = "1.0.0";

    /** The SDK line this engine implements, as a semantic version for range checks. */
    public static final String SDK_VERSION = "1.0.0";

    /**
     * The verdict.
     *
     * @param compatible whether this engine can run the version
     * @param reasons    why not, empty when it can; written to be shown to a user
     */
    public record Compatibility(boolean compatible, List<String> reasons) {

        public Compatibility {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        static Compatibility ok() {
            return new Compatibility(true, List.of());
        }

        static Compatibility refused(List<String> reasons) {
            return new Compatibility(false, reasons);
        }

        /** @return the reasons as one sentence, for a log line or a tooltip */
        public String summary() {
            return String.join(" ", reasons);
        }
    }

    /**
     * Checks a catalogue entry's latest version.
     *
     * @param entry the plugin
     * @return the verdict
     */
    public Compatibility check(CatalogRecords.CatalogEntry entry) {
        return check(entry.sdkVersion(), entry.javaVersion(), entry.engineCompatibility());
    }

    /**
     * Checks one version.
     *
     * @param sdkVersion          SDK the plugin declares
     * @param javaVersion         Java version it needs
     * @param engineCompatibility engine range it declares, or null
     * @return the verdict
     */
    public Compatibility check(String sdkVersion, String javaVersion, String engineCompatibility) {
        List<String> reasons = new ArrayList<>();

        checkSdk(sdkVersion, reasons);
        checkJava(javaVersion, reasons);
        checkEngineRange(engineCompatibility, reasons);

        return reasons.isEmpty() ? Compatibility.ok() : Compatibility.refused(reasons);
    }

    /**
     * The SDK line.
     *
     * <p>Only the major component is compared. Within a major line the SDK promises to stay compatible, so a plugin
     * built against 1.0.0 runs on an engine providing 1.4.0; across one it promises nothing. A plugin declaring a
     * <em>newer minor</em> than this engine provides is allowed with no warning, which is a deliberate choice: it
     * may use a method that is missing, and refusing every such plugin would make every SDK addition a breaking
     * change for the whole catalogue.
     */
    private void checkSdk(String sdkVersion, List<String> reasons) {
        if (sdkVersion == null || sdkVersion.isBlank()) {
            reasons.add("The plugin declares no SDK version, so this engine cannot tell whether it is "
                    + "compatible.");
            return;
        }
        SemanticVersion required = SemanticVersion.tryParse(sdkVersion).orElse(null);
        if (required == null) {
            reasons.add("The plugin declares SDK version '" + sdkVersion + "', which is not a version.");
            return;
        }
        SemanticVersion provided = SemanticVersion.parse(SDK_VERSION);
        if (required.major() != provided.major()) {
            reasons.add("The plugin requires SDK " + required.major() + ".x and this engine provides "
                    + provided.major() + ".x.");
        }
    }

    /**
     * The Java version.
     *
     * <p>A plugin needing a newer Java than the running JVM fails at class load with an
     * {@code UnsupportedClassVersionError}, which is a stack trace rather than an explanation. Comparing the feature
     * number here turns that into a sentence.
     */
    private void checkJava(String javaVersion, List<String> reasons) {
        if (javaVersion == null || javaVersion.isBlank()) {
            return;
        }
        try {
            int required = Integer.parseInt(javaVersion.trim().split("\\.")[0]);
            int running = Runtime.version().feature();
            if (required > running) {
                reasons.add("The plugin needs Java " + required + " and this engine runs Java " + running
                        + ".");
            }
        } catch (NumberFormatException ex) {
            reasons.add("The plugin declares Java version '" + javaVersion + "', which is not a number.");
        }
    }

    private void checkEngineRange(String engineCompatibility, List<String> reasons) {
        if (engineCompatibility == null || engineCompatibility.isBlank()) {
            return;
        }
        if (VersionRange.tryParse(engineCompatibility).isEmpty()) {
            // An unreadable range is refused rather than ignored: the author made a claim about compatibility
            // and nobody can evaluate it, which is not the same as making no claim.
            reasons.add("The plugin declares engine compatibility '" + engineCompatibility
                    + "', which cannot be read. Use a range such as '>=1.0.0 <2.0.0'.");
            return;
        }
        if (!VersionRange.satisfies(engineCompatibility, ENGINE_VERSION)) {
            reasons.add("The plugin requires an engine matching '" + engineCompatibility
                    + "' and this engine is " + ENGINE_VERSION + ".");
        }
    }

    /** @return the API version this engine implements, which is the check that actually gates loading */
    public int engineApiVersion() {
        return PluginApi.VERSION;
    }
}
