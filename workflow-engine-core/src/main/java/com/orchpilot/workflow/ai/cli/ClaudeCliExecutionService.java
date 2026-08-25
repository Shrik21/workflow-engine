package com.orchpilot.workflow.ai.cli;

import com.orchpilot.workflow.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place an AI CLI process is created.
 *
 * <h2>The order of the gates</h2>
 *
 * Every method runs the same sequence, and the order matters:
 *
 * <ol>
 *   <li><b>Is the feature enabled on this host?</b> Checked first, so a disabled engine does no work at all and
 *       cannot be probed for whether a configuration exists.</li>
 *   <li><b>Does the caller's tenant own this configuration?</b> Resolved through the service, which refuses
 *       across tenants.</li>
 *   <li><b>Is the configuration enabled?</b> A disabled configuration is a deliberate off switch.</li>
 *   <li><b>Is the path still valid, present and executable?</b> Re-validated now rather than trusted from write
 *       time, because the allowlist may have narrowed or the file may have been replaced since.</li>
 *   <li><b>Run it</b>, bounded in time and output.</li>
 *   <li><b>Audit</b>, whatever happened.</li>
 * </ol>
 *
 * <p>Despite the name — which the specification asked for — nothing here is Claude-specific. The arguments and
 * the output parsing come from the {@link AiCliProvider} for the configuration's type, so this same service
 * drives any CLI whose adapter is installed.
 */
@Service
public class ClaudeCliExecutionService implements AiCliExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliExecutionService.class);

    private final AiCliConfigurationService configurations;
    private final AiCliProviderRegistry providers;
    private final ExecutablePathValidator pathValidator;
    private final SecureProcessRunner runner;
    private final AiCliProperties properties;
    private final AuditService audit;

    public ClaudeCliExecutionService(AiCliConfigurationService configurations,
                                     AiCliProviderRegistry providers,
                                     ExecutablePathValidator pathValidator,
                                     SecureProcessRunner runner,
                                     AiCliProperties properties,
                                     AuditService audit) {
        this.configurations = configurations;
        this.providers = providers;
        this.pathValidator = pathValidator;
        this.runner = runner;
        this.properties = properties;
        this.audit = audit;
    }

    @Override
    public AiCliTestResult testConnection(String configurationId, String actor) {
        requireEnabled();
        AiCliConfiguration configuration = configurations.get(configurationId, currentTenant());
        return testConnection(configuration, actor);
    }

    /**
     * Tests a configuration already loaded and tenant-checked.
     *
     * <p>Returns a failure rather than throwing, because "the CLI is not installed" is the answer the operator
     * asked for, not an error in asking. Only a disabled feature throws, since that is a refusal to act.
     */
    public AiCliTestResult testConnection(AiCliConfiguration configuration, String actor) {
        requireEnabled();
        long startedAt = System.currentTimeMillis();
        AiCliProvider provider = providers.forType(configuration.getType());

        AiCliTestResult result;
        try {
            requireUsable(configuration);
            Path executable = pathValidator.validateForExecution(
                    configuration.getExecutablePath(), configuration.getOperatingSystem());

            ProcessResult process = runner.run(executable, provider.versionArguments(), null,
                    properties.getTimeoutSeconds());

            if (!process.isSuccess()) {
                result = AiCliTestResult.failed(configuration.getExecutablePath(),
                        configuration.getOperatingSystem(), "AI_CLI_EXECUTION_FAILED",
                        provider.displayName() + " exited with code " + process.exitCode()
                                + ". Check that the path points at a working installation.",
                        System.currentTimeMillis() - startedAt);
            } else {
                String version = provider.parseVersion(process);
                result = version == null
                        ? AiCliTestResult.failed(configuration.getExecutablePath(),
                                configuration.getOperatingSystem(), "AI_CLI_BAD_RESPONSE",
                                provider.displayName() + " ran but did not report a recognisable version.",
                                System.currentTimeMillis() - startedAt)
                        : AiCliTestResult.ok(version, executable.toString(),
                                configuration.getOperatingSystem(), System.currentTimeMillis() - startedAt);
            }
        } catch (AiCliException ex) {
            result = AiCliTestResult.failed(configuration.getExecutablePath(),
                    configuration.getOperatingSystem(), ex.errorCode(), ex.getMessage(),
                    System.currentTimeMillis() - startedAt);
        }

        configurations.recordCheck(configuration, result);
        auditExecution(actor, "AI_CLI_TEST_CONNECTION", configuration, result.success() ? "OK" : "FAILED",
                result.durationMillis(), result.errorCode(), null);
        return result;
    }

    @Override
    public String getVersion(String configurationId, String actor) {
        AiCliTestResult result = testConnection(configurationId, actor);
        if (!result.success()) {
            throw new AiCliException(result.errorCode(), result.message());
        }
        return result.version();
    }

    @Override
    public String executePrompt(String configurationId, String prompt, boolean jsonOutput, String actor) {
        requireEnabled();
        if (prompt == null || prompt.isBlank()) {
            throw new AiCliException("AI_CLI_PROMPT_REQUIRED", "A prompt is required.");
        }
        AiCliConfiguration configuration = configurationId == null || configurationId.isBlank()
                ? configurations.requireDefault(currentTenant(), AiCliType.CLAUDE_CLI)
                : configurations.get(configurationId, currentTenant());
        return executePrompt(configuration, prompt, jsonOutput, actor);
    }

    /**
     * Sends a prompt using a configuration that has already been resolved and tenant-checked.
     *
     * @param configuration the configuration to run
     * @param prompt        the prompt, which travels on standard input and never as an argument
     * @param jsonOutput    whether to ask for machine-readable output
     * @param actor         who asked
     * @return the model's answer
     */
    public String executePrompt(AiCliConfiguration configuration, String prompt, boolean jsonOutput,
                                String actor) {
        requireEnabled();
        requireUsable(configuration);
        AiCliProvider provider = providers.forType(configuration.getType());
        Path executable = pathValidator.validateForExecution(
                configuration.getExecutablePath(), configuration.getOperatingSystem());

        long startedAt = System.currentTimeMillis();
        String errorCode = null;
        try {
            ProcessResult process = runner.run(executable, provider.promptArguments(jsonOutput), prompt,
                    properties.getTimeoutSeconds());
            return provider.parseResponse(process);
        } catch (AiCliException ex) {
            errorCode = ex.errorCode();
            throw ex;
        } finally {
            // The prompt itself is never audited. It can contain an error message from any system in the
            // estate, and an audit trail is not the place to accumulate a second copy of that.
            auditExecution(actor, "AI_CLI_EXECUTE_PROMPT", configuration,
                    errorCode == null ? "OK" : "FAILED", System.currentTimeMillis() - startedAt, errorCode,
                    Map.of("promptChars", prompt.length(), "jsonOutput", jsonOutput));
        }
    }

    // ------------------------------------------------------------------ gates

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw AiCliException.disabled();
        }
    }

    private static void requireUsable(AiCliConfiguration configuration) {
        if (!configuration.isEnabled()) {
            throw new AiCliException("AI_CLI_DISABLED_CONFIGURATION",
                    "The configuration '" + configuration.getName() + "' is disabled.");
        }
    }

    /**
     * The tenant of the caller in the current security context.
     *
     * <p>Read from the authenticated principal rather than accepted as a parameter, so a caller cannot name
     * someone else's tenant by passing a different value.
     */
    private static String currentTenant() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof
                com.orchpilot.workflow.auth.security.AuthPrincipal principal) {
            return principal.getTenantId();
        }
        throw new AiCliException("AI_CLI_NO_TENANT",
                "An AI CLI can only be run in the context of an authenticated user.");
    }

    private void auditExecution(String actor, String action, AiCliConfiguration configuration, String outcome,
                                long durationMillis, String errorCode, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("configurationName", configuration.getName());
        details.put("type", configuration.getType());
        details.put("operatingSystem", String.valueOf(configuration.getOperatingSystem()));
        details.put("executablePath", configuration.getExecutablePath());
        details.put("tenantId", configuration.getTenantId());
        details.put("executionTimeMs", durationMillis);
        if (errorCode != null) {
            details.put("errorCode", errorCode);
        }
        if (extra != null) {
            details.putAll(extra);
        }
        try {
            audit.record(actor, action, "AI_CLI_CONFIGURATION", configuration.getId(), outcome, details);
        } catch (RuntimeException ex) {
            // A failed audit write must not mask the result of the operation it was recording.
            log.warn("Could not write an AI CLI audit record: {}", ex.getMessage());
        }
    }

    /** @return whether the feature is switched on for this host, for the settings page to render honestly */
    public boolean isFeatureEnabled() {
        return properties.isEnabled();
    }

    /** @return every installed CLI adapter, for the type dropdown */
    public List<AiCliProvider> installedProviders() {
        return providers.all();
    }
}
