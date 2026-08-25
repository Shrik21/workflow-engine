package com.orchpilot.workflow.ai.cli;

/**
 * The outcome of testing an AI CLI configuration.
 *
 * <p>Shaped to the response the specification asks for. Note what is absent: no stdout, no stderr, no
 * environment. A failure is described by a message this engine composed, not by whatever the executable
 * happened to print — a third-party binary's output is not something to forward verbatim into a browser.
 *
 * @param success         whether the tool ran and reported a version
 * @param version         the detected version, or null
 * @param path            the executable that was run
 * @param operatingSystem the OS the configuration targets
 * @param errorCode       a stable code when unsuccessful, else null
 * @param message         a human-readable explanation, safe to display
 * @param durationMillis  how long the check took
 */
public record AiCliTestResult(boolean success, String version, String path,
                              OperatingSystemType operatingSystem, String errorCode, String message,
                              long durationMillis) {

    public static AiCliTestResult ok(String version, String path, OperatingSystemType os, long millis) {
        return new AiCliTestResult(true, version, path, os, null,
                "Connected. Detected version " + version + ".", millis);
    }

    public static AiCliTestResult failed(String path, OperatingSystemType os, String errorCode,
                                         String message, long millis) {
        return new AiCliTestResult(false, null, path, os, errorCode, message, millis);
    }
}
