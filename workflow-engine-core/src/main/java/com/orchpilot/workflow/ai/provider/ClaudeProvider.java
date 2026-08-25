package com.orchpilot.workflow.ai.provider;

import com.orchpilot.workflow.ai.AIException;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude, over the Messages API.
 *
 * <p>Claude's shape differs from OpenAI's in two ways this adapter absorbs so the engine never sees them: the
 * system instructions are a top-level {@code system} field rather than a message with a system role, and the
 * {@code messages} array carries only user/assistant turns. Structured output is requested by instruction and
 * the JSON is parsed from the reply, since the Messages API has no native JSON mode.
 */
@Component
public class ClaudeProvider extends AbstractHttpProvider {

    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final List<AIModel> FALLBACK = List.of(
            new AIModel("claude-sonnet-4-5", "Claude Sonnet 4.5", true, true, true),
            new AIModel("claude-opus-4-1", "Claude Opus 4.1", true, true, true),
            new AIModel("claude-haiku-4-5", "Claude Haiku 4.5", true, true, false));

    @Override
    public AIProviderType getProviderType() {
        return AIProviderType.ANTHROPIC;
    }

    @Override
    public List<AIModel> getAvailableModels(AIProviderConfiguration configuration) {
        try {
            Map<String, Object> body = getJson(client(15), base(configuration) + "/v1/models",
                    headers(configuration));
            List<AIModel> models = new ArrayList<>();
            if (body.get("data") instanceof List<?> data) {
                for (Object entry : data) {
                    if (entry instanceof Map<?, ?> model && model.get("id") != null) {
                        String id = String.valueOf(model.get("id"));
                        String name = model.get("display_name") == null ? id
                                : String.valueOf(model.get("display_name"));
                        models.add(new AIModel(id, name, true, true, true));
                    }
                }
            }
            return models.isEmpty() ? FALLBACK : models;
        } catch (AIException ex) {
            return FALLBACK;
        }
    }

    @Override
    public AIResponse generate(AIRequest request, AIProviderConfiguration configuration) {
        return call(request, configuration);
    }

    @Override
    public boolean validateConnection(AIProviderConfiguration configuration) {
        // A tiny generation is the reliable check; models listing is not available on every account tier.
        AIRequest ping = new AIRequest(FALLBACK.get(2).id(),
                List.of(AIMessage.user("ping")), null, 8, null);
        call(ping, configuration);
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

    @Override
    public boolean supportsVision() {
        return true;
    }

    private AIResponse call(AIRequest request, AIProviderConfiguration configuration) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("max_tokens", request.maxTokens() == null ? 1024 : request.maxTokens());
        if (request.temperature() != null) {
            payload.put("temperature", request.temperature());
        }
        String system = system(request);
        if (system != null) {
            payload.put("system", system);
        }
        payload.put("messages", messages(request));
        if (request.hasTools()) {
            payload.put("tools", tools(request.tools()));
        }

        Map<String, Object> body = postJson(client(120), base(configuration) + "/v1/messages",
                headers(configuration), payload);
        AIUsage usage = AIUsage.of(asLong(dig(body, "usage", "input_tokens")),
                asLong(dig(body, "usage", "output_tokens")));

        List<AIToolCall> toolCalls = extractToolCalls(body);
        if (!toolCalls.isEmpty()) {
            return AIResponse.toolCalls(firstText(body), toolCalls, request.model(), usage);
        }

        String text = firstText(body);
        if (text == null) {
            throw AIException.badResponse("Claude returned no text content.");
        }
        if (request.isStructured()) {
            return AIResponse.structured(text, parse(text), request.model(), usage);
        }
        return AIResponse.text(text, request.model(), usage);
    }

    /** Each tool as Claude declares it: {@code {name, description, input_schema}}. */
    private static List<Map<String, Object>> tools(List<AIToolSpec> specs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AIToolSpec spec : specs) {
            out.add(Map.of("name", spec.name(),
                    "description", spec.description() == null ? "" : spec.description(),
                    "input_schema", spec.parameters()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<AIToolCall> extractToolCalls(Map<String, Object> body) {
        List<AIToolCall> calls = new ArrayList<>();
        if (body.get("content") instanceof List<?> content) {
            for (Object block : content) {
                if (block instanceof Map<?, ?> b && "tool_use".equals(b.get("type")) && b.get("name") != null) {
                    Map<String, Object> map = (Map<String, Object>) b;
                    String id = String.valueOf(map.getOrDefault("id", ""));
                    Object input = map.get("input");
                    Map<String, Object> args = input instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                    calls.add(new AIToolCall(id, String.valueOf(map.get("name")), args));
                }
            }
        }
        return calls;
    }

    private static String system(AIRequest request) {
        StringBuilder system = new StringBuilder();
        for (AIMessage message : request.messages()) {
            if (message.role() == AIMessage.Role.SYSTEM) {
                if (system.length() > 0) {
                    system.append("\n\n");
                }
                system.append(message.content());
            }
        }
        return system.length() == 0 ? null : system.toString();
    }

    /**
     * User/assistant turns only; Claude carries the system prompt separately. Tool traffic uses content blocks:
     * an assistant {@code tool_use} for a call, and a {@code tool_result} in a <em>user</em> turn for the answer —
     * and Anthropic requires every result for one assistant turn to sit in a single following user message, so
     * consecutive tool results are merged into one turn here.
     */
    private static List<Map<String, Object>> messages(AIRequest request) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> pendingToolResults = new ArrayList<>();

        for (AIMessage message : request.messages()) {
            if (message.role() == AIMessage.Role.SYSTEM) {
                continue;
            }
            if (message.role() == AIMessage.Role.TOOL) {
                pendingToolResults.add(Map.of("type", "tool_result",
                        "tool_use_id", message.toolCallId() == null ? "" : message.toolCallId(),
                        "content", message.content() == null ? "" : message.content()));
                continue;
            }
            // Any non-tool message closes an open run of tool results into one user turn.
            flushToolResults(out, pendingToolResults);

            if (message.role() == AIMessage.Role.ASSISTANT && message.hasToolCalls()) {
                out.add(Map.of("role", "assistant", "content", toolUseBlocks(message)));
            } else {
                String role = message.role() == AIMessage.Role.ASSISTANT ? "assistant" : "user";
                out.add(Map.of("role", role, "content", message.content() == null ? "" : message.content()));
            }
        }
        flushToolResults(out, pendingToolResults);

        if (out.isEmpty()) {
            out.add(Map.of("role", "user", "content", ""));
        }
        return out;
    }

    private static void flushToolResults(List<Map<String, Object>> out, List<Map<String, Object>> pending) {
        if (!pending.isEmpty()) {
            out.add(Map.of("role", "user", "content", new ArrayList<>(pending)));
            pending.clear();
        }
    }

    /** The assistant turn's content blocks: any accompanying text, then a {@code tool_use} block per call. */
    private static List<Map<String, Object>> toolUseBlocks(AIMessage message) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (message.content() != null && !message.content().isBlank()) {
            blocks.add(Map.of("type", "text", "text", message.content()));
        }
        for (AIToolCall call : message.toolCalls()) {
            blocks.add(Map.of("type", "tool_use",
                    "id", call.id() == null ? "" : call.id(),
                    "name", call.name(),
                    "input", call.arguments()));
        }
        return blocks;
    }

    @SuppressWarnings("unchecked")
    private static String firstText(Map<String, Object> body) {
        if (body.get("content") instanceof List<?> content) {
            for (Object block : content) {
                if (block instanceof Map<?, ?> b && "text".equals(b.get("type")) && b.get("text") != null) {
                    return String.valueOf(b.get("text"));
                }
            }
        }
        return null;
    }

    private static Map<String, String> headers(AIProviderConfiguration configuration) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        if (configuration.apiKey() != null) {
            headers.put("x-api-key", configuration.apiKey());
        }
        return headers;
    }

    private static String base(AIProviderConfiguration configuration) {
        String endpoint = configuration.endpoint();
        String base = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
