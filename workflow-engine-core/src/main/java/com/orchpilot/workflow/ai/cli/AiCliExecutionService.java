package com.orchpilot.workflow.ai.cli;

/**
 * Runs a configured AI CLI.
 *
 * <h2>Why this is an interface with the CLI-specific work behind {@link AiCliProvider}</h2>
 *
 * There is one implementation, {@link ClaudeCliExecutionService}, and it is not Claude-specific: it resolves a
 * configuration, validates the path, runs the process, audits the result and updates the cached status —
 * identically for every CLI. What differs per tool (arguments, output parsing) is behind {@link AiCliProvider}.
 *
 * <p>Splitting it this way means adding {@code GeminiCliProvider} adds no service, no controller and no branch
 * anywhere; the specification's request for {@code ClaudeCliExecutionService} is honoured by name, and the
 * extensibility it also asks for comes from the provider seam rather than from a service per tool.
 *
 * <p>Nothing in a controller may create a process. Every path to {@link SecureProcessRunner} goes through here,
 * which is what makes "is the feature enabled, is the caller permitted, was it audited" answerable in one place.
 */
public interface AiCliExecutionService {

    /**
     * Runs the tool's version command to confirm the configuration works, and caches the outcome.
     *
     * @param configurationId which configuration
     * @param actor           who asked, for the audit record
     * @return what was found; never throws for a CLI that simply is not there — that is a result, not an error
     */
    AiCliTestResult testConnection(String configurationId, String actor);

    /**
     * Reads the tool's version.
     *
     * @param configurationId which configuration
     * @param actor           who asked
     * @return the version string
     * @throws AiCliException when the tool cannot be run
     */
    String getVersion(String configurationId, String actor);

    /**
     * Sends a prompt and returns the answer.
     *
     * <p>The prompt travels on standard input, never as an argument.
     *
     * @param configurationId which configuration, or null for the tenant's default
     * @param prompt          the prompt text
     * @param jsonOutput      whether to ask the tool for machine-readable output
     * @param actor           who asked
     * @return the model's answer
     * @throws AiCliException when the tool cannot be run or its output cannot be understood
     */
    String executePrompt(String configurationId, String prompt, boolean jsonOutput, String actor);
}
