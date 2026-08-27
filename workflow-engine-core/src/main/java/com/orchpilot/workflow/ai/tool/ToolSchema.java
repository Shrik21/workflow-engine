package com.orchpilot.workflow.ai.tool;

import java.util.Map;

/**
 * A tool as an AI model sees it: a name, a description of what it does, and a JSON-schema of its parameters.
 *
 * <p>This is the <em>only</em> thing a model is ever told about a tool — never a credential, an endpoint or an
 * internal id. For a plugin-backed tool the parameters come straight from the plugin's own configuration schema,
 * so the model is offered exactly the inputs the plugin already declares.
 *
 * @param name        a stable, model-friendly tool name
 * @param description what the tool does, for the model to decide when to use it
 * @param parameters  a JSON-schema object describing the tool's arguments
 */
public record ToolSchema(String name, String description, Map<String, Object> parameters) {

    public ToolSchema {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
