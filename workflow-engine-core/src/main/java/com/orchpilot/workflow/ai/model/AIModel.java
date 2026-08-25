package com.orchpilot.workflow.ai.model;

/**
 * A model a provider offers, discovered dynamically rather than hardcoded.
 *
 * @param id           the id the provider expects in a request, e.g. {@code gpt-4o} or {@code llama3.1}
 * @param displayName  a friendly label for the dropdown
 * @param supportsTools      whether the model can call tools
 * @param supportsStructured whether the model can be asked for schema-constrained JSON
 * @param supportsVision     whether the model accepts images
 */
public record AIModel(String id, String displayName, boolean supportsTools, boolean supportsStructured,
                      boolean supportsVision) {

    /** A model with only its id known, everything else assumed false — the safe default for discovery. */
    public static AIModel of(String id) {
        return new AIModel(id, id, false, false, false);
    }

    public static AIModel of(String id, String displayName) {
        return new AIModel(id, displayName, false, false, false);
    }
}
