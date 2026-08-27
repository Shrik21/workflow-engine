package com.orchpilot.workflow.ai.model;

import java.util.Map;

/**
 * A tool as offered to the model on a request: a name, a description, and a JSON-schema of its parameters.
 *
 * <p>This is the provider-independent form the {@link AIRequest} carries. It is intentionally the same trio a
 * {@code ToolSchema} exposes, kept as its own record in the model package so the request DTO stays independent of
 * the tool-runtime types — the executor maps a live tool's schema onto this before calling a provider. The model
 * is shown exactly this and nothing more: never a credential, an endpoint, or an internal id.
 *
 * @param name        the tool's stable, model-facing name
 * @param description what the tool does, for the model to decide when to use it
 * @param parameters  a JSON-schema object describing the tool's arguments
 */
public record AIToolSpec(String name, String description, Map<String, Object> parameters) {

    public AIToolSpec {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
