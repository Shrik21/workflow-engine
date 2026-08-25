package com.orchpilot.workflow.exception;

import java.util.List;

/**
 * An uploaded plugin archive was rejected before anything in it was loaded.
 *
 * <p>Every rejection reason is reported at once. Uploading a plugin is uploading executable code, so
 * the validator is deliberately strict and the operator deserves to see the full list.
 */
public class PluginValidationException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    private final List<String> errors;

    public PluginValidationException(List<String> errors) {
        super("PLUGIN_INVALID", "Plugin archive rejected: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public PluginValidationException(String error) {
        this(List.of(error));
    }

    /** @return every validation problem found */
    public List<String> getErrors() {
        return errors;
    }
}
