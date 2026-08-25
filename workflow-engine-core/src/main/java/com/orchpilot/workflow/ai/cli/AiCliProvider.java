package com.orchpilot.workflow.ai.cli;

import java.util.List;

/**
 * One AI CLI tool the engine knows how to drive.
 *
 * <h2>Why this exists next to {@code AIModelProvider} rather than inside it</h2>
 *
 * {@code AIModelProvider} is the engine's abstraction over AI <em>services</em>: an endpoint, a key, a request,
 * a response. Everything it needs is provider-independent. A CLI is a different shape of the same idea — the
 * things that vary between {@code claude} and a hypothetical {@code gemini} CLI are which arguments produce a
 * version string, which produce a completion, and how the output is parsed. None of that fits the HTTP
 * abstraction, and forcing it in would put CLI concerns into every HTTP provider.
 *
 * <p>So this interface carries only what is genuinely CLI-specific, and is deliberately small. Adding another
 * CLI is one bean implementing it — the same contribution model the rest of the AI subsystem uses, and no
 * change to the engine, the service, or the controller.
 *
 * <p>Implementations must not execute anything themselves. They describe <em>how</em> to invoke the tool and
 * how to read its output; {@link SecureProcessRunner} does the invoking, so there is exactly one place where a
 * process is created and exactly one place to audit.
 */
public interface AiCliProvider {

    /** @return the {@link AiCliType} constant this drives */
    String type();

    /** @return human-readable name for the settings UI, e.g. "Claude CLI" */
    String displayName();

    /** @return the base command name for auto-detection, e.g. {@code claude} */
    String command();

    /** @return the arguments that make the tool print its version and exit */
    List<String> versionArguments();

    /**
     * Extracts a version from what {@link #versionArguments()} produced.
     *
     * @param result the completed invocation
     * @return the version string, or null when the output does not contain one
     */
    String parseVersion(ProcessResult result);

    /**
     * @param jsonOutput whether the caller needs machine-readable output
     * @return the arguments for a single non-interactive prompt, which is supplied on standard input
     */
    List<String> promptArguments(boolean jsonOutput);

    /**
     * Extracts the assistant's text from a completed prompt invocation.
     *
     * @param result the completed invocation
     * @return the response text
     * @throws AiCliException when the output cannot be understood
     */
    String parseResponse(ProcessResult result);
}
