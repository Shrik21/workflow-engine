package com.orchpilot.pluginserver.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a misconfiguration into a readable startup failure.
 *
 * <p>Without this, refusing to start over a missing verification key produces two wrapped
 * {@code BeanCreationException}s and forty lines of framework stack trace, with the sentence that matters in the
 * middle. Boot's failure analysis exists to print a Description and an Action instead, and this platform already
 * uses the same pattern in the workflow service.
 *
 * <p>Only claims a failure it recognises. Returning null for anything else leaves other analyzers, and the
 * default reporting, alone.
 */
public class PluginServerConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<IllegalStateException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, IllegalStateException cause) {
        String message = cause.getMessage();
        if (message == null || !message.contains("plugin-server.security")) {
            return null;
        }

        // The exception's own message is written for exactly this: what is wrong, then what to do.
        int split = message.indexOf("\n\n");
        String description = split > 0 ? message.substring(0, split) : message;
        String action = split > 0 ? message.substring(split + 2) : """
                Set PLUGIN_SERVER_JWT_SECRET or PLUGIN_SERVER_JWKS_URI. Generate a secret with:
                  openssl rand -base64 48""";

        return new FailureAnalysis(description, action, cause);
    }
}
