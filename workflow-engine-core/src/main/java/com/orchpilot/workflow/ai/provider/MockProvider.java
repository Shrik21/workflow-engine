package com.orchpilot.workflow.ai.provider;

import com.orchpilot.workflow.ai.AIModelProvider;
import com.orchpilot.workflow.ai.AIProviderType;
import com.orchpilot.workflow.ai.model.AIMessage;
import com.orchpilot.workflow.ai.model.AIModel;
import com.orchpilot.workflow.ai.model.AIProviderConfiguration;
import com.orchpilot.workflow.ai.model.AIRequest;
import com.orchpilot.workflow.ai.model.AIResponse;
import com.orchpilot.workflow.ai.model.AIToolCall;
import com.orchpilot.workflow.ai.model.AIToolSpec;
import com.orchpilot.workflow.ai.model.AIUsage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deterministic, network-free provider for tests and offline development.
 *
 * <p>It exists so the whole AI path — the node executor, the router with its retries and fallback, variable
 * resolution, output mapping and structured-output validation — can be exercised with no API key and no
 * internet, which is exactly what the specification requires of automated tests. It echoes the last user
 * message back as text, and for a structured request fills the requested schema's top-level properties with
 * type-appropriate placeholder values, so downstream mapping and validation have something real to work on.
 */
@Component
public class MockProvider implements AIModelProvider {

    @Override
    public AIProviderType getProviderType() {
        return AIProviderType.MOCK;
    }

    @Override
    public List<AIModel> getAvailableModels(AIProviderConfiguration configuration) {
        return List.of(new AIModel("mock-small", "Mock (small)", true, true, false),
                new AIModel("mock-large", "Mock (large)", true, true, true));
    }

    @Override
    public AIResponse generate(AIRequest request, AIProviderConfiguration configuration) {
        String prompt = lastUserMessage(request);

        // Tool-aware, deterministically: when tools are offered and none have been called yet, ask to call the
        // first one; once a tool result is on the conversation, answer with it. This lets the agent loop — the
        // model→tool→model round-trip, and its limits — be exercised entirely offline.
        if (request.hasTools() && !hasToolResult(request)) {
            AIToolSpec tool = request.tools().get(0);
            AIToolCall call = new AIToolCall("mock-call-1", tool.name(), Map.of("input", prompt));
            return AIResponse.toolCalls(null, List.of(call), request.model(), usageFor(prompt, tool.name()));
        }

        String toolResult = lastToolResult(request);
        String text = toolResult != null
                ? "Mock final answer using tool result: " + toolResult
                : "Mock response to: " + prompt;
        return AIResponse.text(text, request.model(), usageFor(prompt, text));
    }

    @Override
    public AIResponse generateStructured(AIRequest request, AIProviderConfiguration configuration) {
        Map<String, Object> structured = fillSchema(request.jsonSchema());
        String prompt = lastUserMessage(request);
        return AIResponse.structured(String.valueOf(structured), structured, request.model(),
                usageFor(prompt, String.valueOf(structured)));
    }

    @Override
    public boolean validateConnection(AIProviderConfiguration configuration) {
        return true;
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    @Override
    public boolean supportsStructuredOutput() {
        return true;
    }

    private static String lastUserMessage(AIRequest request) {
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            AIMessage message = request.messages().get(i);
            if (message.role() == AIMessage.Role.USER) {
                return message.content();
            }
        }
        return "";
    }

    private static boolean hasToolResult(AIRequest request) {
        return request.messages().stream().anyMatch(m -> m.role() == AIMessage.Role.TOOL);
    }

    private static String lastToolResult(AIRequest request) {
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            AIMessage message = request.messages().get(i);
            if (message.role() == AIMessage.Role.TOOL) {
                return message.content();
            }
        }
        return null;
    }

    /** Fills the schema's declared top-level properties with placeholder values matching their types. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fillSchema(Map<String, Object> schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (schema == null) {
            return result;
        }
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> entry : props.entrySet()) {
                String name = String.valueOf(entry.getKey());
                String type = entry.getValue() instanceof Map<?, ?> def
                        ? String.valueOf(def.get("type")) : "string";
                result.put(name, switch (type) {
                    case "boolean" -> Boolean.TRUE;
                    case "integer", "number" -> 0;
                    case "array" -> List.of();
                    case "object" -> Map.of();
                    default -> "mock-" + name;
                });
            }
        }
        return result;
    }

    private static AIUsage usageFor(String prompt, String output) {
        return AIUsage.of(estimate(prompt), estimate(output));
    }

    private static long estimate(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
