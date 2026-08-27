package com.orchpilot.workflow.ai.cli;

import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuring and testing the AI CLI tools this engine may run.
 *
 * <h2>Permission tiers</h2>
 *
 * Viewing ({@code AI_CLI_VIEW}) is separate from creating, updating and deleting, and all of those are separate
 * from {@code AI_CLI_EXECUTE} — because configuring which binary the engine points at and actually running it
 * are different capabilities, and an auditor should be able to do the first kind of inspection while doing
 * neither.
 *
 * <p>No endpoint here accepts a command, an argument list, or anything else that becomes part of a command
 * line. A caller supplies a path and a name; the arguments are the engine's.
 */
@RestController
@RequestMapping("/api/ai/cli")
@Tag(name = "AI CLI", description = "AI command-line tools the engine may run for analysis and assistance")
public class AiCliController {

    private final AiCliConfigurationService configurations;
    private final ClaudeCliExecutionService execution;
    private final CliDetector detector;
    private final AiCliProviderRegistry providers;
    private final AiCliProperties properties;

    public AiCliController(AiCliConfigurationService configurations, ClaudeCliExecutionService execution,
                           CliDetector detector, AiCliProviderRegistry providers,
                           AiCliProperties properties) {
        this.configurations = configurations;
        this.execution = execution;
        this.detector = detector;
        this.providers = providers;
        this.properties = properties;
    }

    /** A configuration as returned to a client. Carries no secret and no raw executable output. */
    public record ConfigurationView(String id, String name, String type, String operatingSystem,
                                    String executablePath, boolean enabled, boolean defaultConfiguration,
                                    String status, String version, Instant lastCheckedAt, String lastError,
                                    Instant createdAt, Instant updatedAt) {

        static ConfigurationView of(AiCliConfiguration c) {
            return new ConfigurationView(c.getId(), c.getName(), c.getType(),
                    String.valueOf(c.getOperatingSystem()), c.getExecutablePath(), c.isEnabled(),
                    c.isDefaultConfiguration(), String.valueOf(c.getStatus()), c.getVersion(),
                    c.getLastCheckedAt(), c.getLastError(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    /** The create/update body. */
    public record ConfigurationRequestBody(String name, String type, String operatingSystem,
                                           String executablePath, Boolean enabled,
                                           Boolean defaultConfiguration, String secretName) {
    }

    /** An installed CLI adapter, for the type dropdown. */
    public record ProviderView(String type, String displayName, String command) {
    }

    /**
     * Whether the feature is usable at all on this host.
     *
     * <p>Its own endpoint so the settings page can say "an operator must enable this" instead of showing a form
     * whose every button fails. Readable with {@code AI_CLI_VIEW} because knowing the feature is off is not
     * itself sensitive.
     */
    public record FeatureStatus(boolean enabled, String hostOperatingSystem, int timeoutSeconds,
                                boolean directoriesRestricted, List<ProviderView> providers) {
    }

    @PreAuthorize("hasAuthority('AI_CLI_VIEW')")
    @GetMapping("/status")
    @Operation(summary = "Whether AI CLI execution is enabled on this engine")
    public FeatureStatus status() {
        List<ProviderView> views = new ArrayList<>();
        for (AiCliProvider provider : providers.all()) {
            views.add(new ProviderView(provider.type(), provider.displayName(), provider.command()));
        }
        return new FeatureStatus(properties.isEnabled(),
                OperatingSystemType.detectHost().name(),
                properties.getTimeoutSeconds(),
                !properties.getAllowedDirectories().isEmpty(),
                views);
    }

    @PreAuthorize("hasAuthority('AI_CLI_VIEW')")
    @GetMapping
    @Operation(summary = "List the AI CLI configurations for your tenant")
    public List<ConfigurationView> list() {
        return configurations.list(principal().getTenantId()).stream().map(ConfigurationView::of).toList();
    }

    @PreAuthorize("hasAuthority('AI_CLI_VIEW')")
    @GetMapping("/{id}")
    @Operation(summary = "Read one AI CLI configuration")
    public ConfigurationView get(@PathVariable String id) {
        return ConfigurationView.of(configurations.get(id, principal().getTenantId()));
    }

    @PreAuthorize("hasAuthority('AI_CLI_CREATE')")
    @PostMapping
    @Operation(summary = "Add an AI CLI configuration",
            description = "Names an executable the engine host will run. The path is validated but not "
                    + "executed; use Test Connection for that.")
    public ConfigurationView create(@RequestBody ConfigurationRequestBody body) {
        return ConfigurationView.of(
                configurations.create(toRequest(body), principal().getTenantId(), principal().getUsername()));
    }

    @PreAuthorize("hasAuthority('AI_CLI_UPDATE')")
    @PutMapping("/{id}")
    @Operation(summary = "Change an AI CLI configuration")
    public ConfigurationView update(@PathVariable String id, @RequestBody ConfigurationRequestBody body) {
        return ConfigurationView.of(
                configurations.update(id, toRequest(body), principal().getTenantId(), principal().getUsername()));
    }

    @PreAuthorize("hasAuthority('AI_CLI_DELETE')")
    @DeleteMapping("/{id}")
    @Operation(summary = "Remove an AI CLI configuration")
    public void delete(@PathVariable String id) {
        configurations.delete(id, principal().getTenantId(), principal().getUsername());
    }

    @PreAuthorize("hasAuthority('AI_CLI_EXECUTE')")
    @PostMapping("/{id}/test")
    @Operation(summary = "Run the tool's version command to confirm the configuration works",
            description = "Executes the configured program with a fixed argument list and records the outcome. "
                    + "Returns a failure result rather than an error when the tool is simply not installed.")
    public AiCliTestResult test(@PathVariable String id) {
        return execution.testConnection(id, principal().getUsername());
    }

    @PreAuthorize("hasAuthority('AI_CLI_EXECUTE')")
    @GetMapping("/{id}/version")
    @Operation(summary = "Detect the tool's version")
    public VersionView version(@PathVariable String id) {
        return new VersionView(execution.getVersion(id, principal().getUsername()));
    }

    /** The detected version. */
    public record VersionView(String version) {
    }

    /**
     * Searches the engine host for an installed CLI.
     *
     * <p>Needs {@code AI_CLI_CREATE} rather than view: the result describes where programs live on the engine
     * host, which is information about the host rather than about a configuration.
     */
    @PreAuthorize("hasAuthority('AI_CLI_CREATE')")
    @GetMapping("/detect")
    @Operation(summary = "Find an AI CLI installed on the engine host",
            description = "Searches PATH first, then common install locations. Executes nothing.")
    public DetectionView detect(@RequestParam(defaultValue = AiCliType.CLAUDE_CLI) String type) {
        AiCliProvider provider = providers.forType(type);
        List<CliDetector.Candidate> candidates = detector.detect(provider.command());
        return new DetectionView(OperatingSystemType.detectHost().name(), provider.command(), candidates);
    }

    /** What auto-detection found. */
    public record DetectionView(String hostOperatingSystem, String command,
                                List<CliDetector.Candidate> candidates) {
    }

    /**
     * The authenticated caller.
     *
     * <p>Read from the security context rather than taken as a parameter, so a request cannot name a tenant or
     * a username other than its own. Every endpoint here is behind {@code @PreAuthorize}, so an absent
     * principal means the security configuration is broken rather than that the caller is anonymous.
     */
    private static AuthPrincipal principal() {
        return CurrentUser.principal().orElseThrow(() ->
                new org.springframework.security.access.AccessDeniedException(
                        "This endpoint requires an authenticated user"));
    }

    private static AiCliConfigurationService.ConfigurationRequest toRequest(ConfigurationRequestBody body) {
        OperatingSystemType os;
        try {
            os = body.operatingSystem() == null ? null
                    : OperatingSystemType.valueOf(body.operatingSystem().trim().toUpperCase(
                            java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new AiCliException("AI_CLI_OS_INVALID",
                    "'" + body.operatingSystem() + "' is not a supported operating system. Use WINDOWS, "
                            + "UBUNTU or LINUX.");
        }
        return new AiCliConfigurationService.ConfigurationRequest(body.name(), body.type(), os,
                body.executablePath(), body.enabled(), body.defaultConfiguration(), body.secretName());
    }
}
