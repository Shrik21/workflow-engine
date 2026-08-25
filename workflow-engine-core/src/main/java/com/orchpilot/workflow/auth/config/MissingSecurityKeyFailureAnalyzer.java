package com.orchpilot.workflow.auth.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a missing cryptographic key into a readable startup failure.
 *
 * <p>Without this, forgetting an environment variable produces a wall of nested
 * {@code UnsatisfiedDependencyException} frames, and the one line that matters is buried a hundred lines
 * down. Spring Boot's failure-analyzer mechanism exists for exactly this, and a startup error an operator
 * hits on their first run is worth the twenty lines it takes to make it clear.
 *
 * <p>Registered through {@code META-INF/spring.factories}, which is still how failure analyzers are
 * discovered. They run before the application context exists, so they cannot be beans.
 */
public class MissingSecurityKeyFailureAnalyzer extends AbstractFailureAnalyzer<MissingSecurityKeyException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingSecurityKeyException cause) {
        String description = cause.getReason()
                + System.lineSeparator()
                + System.lineSeparator()
                + "The application will not start without it. This is deliberate: a built-in default key "
                + "would be present in every deployment that forgot to override it, which is equivalent to "
                + "having no authentication at all.";

        // Both generators are offered because neither is universally available: openssl is absent from a
        // stock Windows install, and the PowerShell form is meaningless on Linux. Printing only the one
        // that happens to suit the author's machine is how a clear error message becomes a dead end.
        String action = String.join(System.lineSeparator(),
                "Generate a value.",
                "",
                "  Linux, macOS, or Windows with Git installed:",
                "",
                "    " + cause.getGenerateCommand(),
                "",
                "  Windows PowerShell, no extra tools needed:",
                "",
                "    " + powerShellGenerator(cause),
                "",
                "Then supply it as the environment variable " + cause.getEnvironmentVariable()
                        + ", or as the",
                "property " + cause.getProperty() + ".",
                "",
                "  PowerShell, for the current session:",
                "",
                "    $env:" + cause.getEnvironmentVariable() + " = \"<generated value>\"",
                "",
                "  Or use the launcher, which generates and reuses keys in .env.local:",
                "",
                "    ./run-local.ps1",
                "",
                "For local development only, the engine can generate an ephemeral key on each start:",
                "",
                "    --spring.profiles.active=dev",
                "",
                "Tokens then stop working across restarts and across instances, so never use that in",
                "production or for anything shared.");

        return new FailureAnalysis(description, action, cause);
    }

    /**
     * A PowerShell equivalent of the generator command, so the message is usable on a Windows machine with
     * no openssl.
     *
     * <p>The byte count is read from the openssl command rather than hardcoded, so the two suggestions cannot
     * drift apart when a key size changes.
     */
    private static String powerShellGenerator(MissingSecurityKeyException cause) {
        int bytes = parseByteCount(cause.getGenerateCommand());
        return "[Convert]::ToBase64String((1.." + bytes
                + " | ForEach-Object { Get-Random -Max 256 } | ForEach-Object { [byte]$_ }))";
    }

    /** Reads the byte count out of a command such as {@code openssl rand -base64 48}, defaulting to 48. */
    private static int parseByteCount(String command) {
        if (command == null) {
            return 48;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(command.trim());
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // Fall through to the default.
            }
        }
        return 48;
    }
}
