package com.orchpilot.workflow.node;

import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.schema.SchemaBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Design-time descriptions of the four built-in node types.
 *
 * <p>Built-ins publish exactly the same {@link NodeDefinition} shape as plugin nodes, so
 * {@code GET /api/nodes} returns one uniform list and a front end needs no special case for them. That
 * uniformity is the point: it means a designer can render a palette entry for a node type that shipped
 * five minutes ago in a plugin JAR using the same code that renders START.
 */
@Component
public class BuiltInNodeCatalog {

    private final List<NodeDefinition> definitions = List.of(start(), form(), decision(), end(), aiAgent());

    /**
     * @return immutable definitions of the engine's own node types
     */
    public List<NodeDefinition> definitions() {
        return definitions;
    }

    private static NodeDefinition start() {
        return NodeDefinition.builder(NodeTypes.START)
                .displayName("Start")
                .category("Flow")
                .icon("play")
                .description("Entry point of the workflow. Seeds workflow variables from defaults and "
                        + "from the execution input.")
                .configurationSchema(SchemaBuilder.object()
                        .map("variables", "Default workflow variables", false)
                        .withDescription("variables", "Applied only where the caller supplied no value")
                        .build())
                .outputVariables("startedAt", "mode", "variablesInitialised")
                .idempotent(true)
                .supportsRetry(false)
                .build();
    }

    /**
     * The human step.
     *
     * <p>{@code formId} and {@code formVersion} are deliberately absent from this schema even though the node
     * reads both: they are first-class fields on the node, edited by the designer's form picker, and repeating
     * them here would render a second text box next to the dropdown, writing to a different place that
     * happens to also be honoured. The keys that remain are the ones with nowhere else to live.
     *
     * <p>{@code fields} is gone. It was how a node declared its inputs before forms were documents, and
     * leaving it advertised invites an author to fill in something the runtime no longer renders.
     */
    private static NodeDefinition form() {
        return NodeDefinition.builder(NodeTypes.FORM)
                .displayName("Form")
                .category("Human")
                .icon("form")
                .description("Raises a task for a person and parks the execution until it is submitted, "
                        + "without holding a thread.")
                .configurationSchema(SchemaBuilder.object()
                        .string("taskName", "Task name", false)
                        .withDescription("taskName",
                                "What the inbox row says. Supports ${variable} placeholders.")
                        .text("taskDescription", "Task description", false)
                        .withDescription("taskDescription", "Shown above the form when the task is opened")

                        .string("assignee", "Assign to", false)
                        .withDescription("assignee",
                                "Username or user id, or a ${variable} holding one. Leave empty to offer the "
                                        + "task to the candidate groups instead.")
                        /*
                         * Comma-separated text, not a map. These are lists of names, and the schema
                         * vocabulary has no list type: declaring them as maps renders a key-and-value editor
                         * for something that has only values, and cannot display a list already stored by an
                         * API caller. A JSON array is still accepted on the wire.
                         */
                        .string("candidateGroups", "Candidate groups", false)
                        .withDescription("candidateGroups",
                                "Group names or ids whose members may claim this task, comma-separated")
                        .string("candidateUsers", "Candidate users", false)
                        .withDescription("candidateUsers",
                                "Individual usernames who may claim it, comma-separated")

                        .select("priority", "Priority",
                                java.util.List.of("LOW", "NORMAL", "HIGH", "URGENT"), false)
                        .withDefault("priority", "NORMAL")
                        .integer("dueInSeconds", "Due after (seconds)", false)
                        .withDescription("dueInSeconds",
                                "Advisory. The task is flagged overdue and a reminder is sent; it stays "
                                        + "completable.")
                        .integer("expiresInSeconds", "Expire after (seconds)", false)
                        .withDescription("expiresInSeconds",
                                "Enforced. The task is marked EXPIRED and the execution is cancelled.")
                        .build())
                .outputVariables("submission", "formId", "submitted")
                .idempotent(true)
                .supportsRetry(false)
                .build();
    }

    private static NodeDefinition decision() {
        return NodeDefinition.builder(NodeTypes.DECISION)
                .displayName("Decision")
                .category("Flow")
                .icon("branch")
                .description("Evaluates conditions in order and follows the branch of the first match. "
                        + "Expressions may only read variables.")
                .configurationSchema(SchemaBuilder.object()
                        .map("conditions", "Branch conditions (branch, expression)", false)
                        .withDescription("conditions",
                                "Example: [{\"branch\":\"approved\",\"expression\":\"amount > 10000\"}]")
                        .string("defaultBranch", "Default branch", false)
                        .build())
                .outputVariables("selectedBranch", "usedDefaultBranch", "evaluations")
                .idempotent(true)
                .supportsRetry(false)
                .build();
    }

    private static NodeDefinition end() {
        return NodeDefinition.builder(NodeTypes.END)
                .displayName("End")
                .category("Flow")
                .icon("stop")
                .description("Completes the workflow, stores its result and publishes the completion "
                        + "event.")
                .configurationSchema(SchemaBuilder.object()
                        .map("outputs", "Result values (name to template)", false)
                        .string("resultStatus", "Business result status", false)
                        .build())
                .outputVariables("completedAt")
                .idempotent(true)
                .supportsRetry(false)
                .build();
    }

    private static NodeDefinition aiAgent() {
        return NodeDefinition.builder(NodeTypes.AI_AGENT)
                .displayName("AI Agent")
                .category("AI")
                .icon("spark")
                .description("Runs an AI model through a provider-independent runtime and publishes its output "
                        + "as workflow variables. The provider (OpenAI, Claude, Ollama, …) and model are chosen "
                        + "from a connection; credentials never enter the workflow.")
                .configurationSchema(SchemaBuilder.object()
                        .string("providerConnectionId", "AI provider connection", true)
                        .string("model", "Model", true)
                        .string("agentMode", "Agent mode (SIMPLE, TOOL_CALLING, AUTONOMOUS, SUPERVISED)", false)
                        .string("systemInstructions", "System instructions", false)
                        .string("prompt", "User prompt (supports ${variables})", true)
                        .object("output", "Output (type, variable, schema)", java.util.Map.of(), false)
                        .object("limits", "Limits (timeoutSeconds, retryCount, temperature, maxTokens)",
                                java.util.Map.of(), false)
                        .build())
                .outputVariables("aiResponse")
                .idempotent(false)
                .supportsRetry(true)
                .build();
    }
}
