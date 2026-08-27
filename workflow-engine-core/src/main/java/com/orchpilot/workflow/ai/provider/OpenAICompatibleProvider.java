package com.orchpilot.workflow.ai.provider;

import com.orchpilot.workflow.ai.AIException;
import com.orchpilot.workflow.ai.model.AIMessage;
import com.orchpilot.workflow.ai.model.AIModel;
import com.orchpilot.workflow.ai.model.AIProviderConfiguration;
import com.orchpilot.workflow.ai.model.AIRequest;
import com.orchpilot.workflow.ai.model.AIResponse;
import com.orchpilot.workflow.ai.model.AIToolCall;
import com.orchpilot.workflow.ai.model.AIToolSpec;
import com.orchpilot.workflow.ai.model.AIUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shared behaviour for every provider that speaks the OpenAI Chat Completions dialect.
 *
 * <p>OpenAI itself, Azure OpenAI, vLLM, NVIDIA NIM and Vertex AI's OpenAI-compatible surface differ only in
 * their base URL, their auth header and their model-discovery endpoint — the request and response JSON are the
 * same. So the whole call lives here once, with a handful of hooks a subclass overrides: {@link #defaultEndpoint},
 * {@link #authHeaders}, {@link #chatUrl}, {@link #modelsUrl} and {@link #fallbackModels}. Adding one of these
 * providers is therefore a few lines, and none of it touches the workflow engine.
 */
abstract class OpenAICompatibleProvider extends AbstractHttpProvider {

    /** The base URL to use when a connection specifies none. */
    protected abstract String defaultEndpoint();

    /** Auth headers for a call; Bearer by default, overridden by e.g. Azure's {@code api-key}. */
    protected Map<String, String> authHeaders(AIProviderConfiguration configuration) {
        return configuration.apiKey() == null ? Map.of()
                : Map.of("Authorization", "Bearer " + configuration.apiKey());
    }

    /** The chat-completions URL; Azure overrides to route through a deployment. */
    protected String chatUrl(AIProviderConfiguration configuration, String model) {
        return base(configuration) + "/chat/completions";
    }

    /** The models-listing URL, or null when the provider has no standard listing. */
    protected String modelsUrl(AIProviderConfiguration configuration) {
        return base(configuration) + "/models";
    }

    /** A curated fallback shown when live discovery is unavailable — never an authoritative master list. */
    protected List<AIModel> fallbackModels() {
        return List.of();
    }

    protected final String base(AIProviderConfiguration configuration) {
        String endpoint = configuration.endpoint();
        String base = endpoint == null || endpoint.isBlank() ? defaultEndpoint() : endpoint.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public List<AIModel> getAvailableModels(AIProviderConfiguration configuration) {
        String url = modelsUrl(configuration);
        if (url == null) {
            return fallbackModels();
        }
        try {
            Map<String, Object> body = getJson(client(15), url, authHeaders(configuration));
            List<AIModel> models = new ArrayList<>();
            if (body.get("data") instanceof List<?> data) {
                for (Object entry : data) {
                    if (entry instanceof Map<?, ?> model && model.get("id") != null) {
                        String id = String.valueOf(model.get("id"));
                        models.add(new AIModel(id, id, true, true, false));
                    }
                }
            }
            return models.isEmpty() ? fallbackModels() : models;
        } catch (AIException ex) {
            return fallbackModels();
        }
    }

    @Override
    public AIResponse generate(AIRequest request, AIProviderConfiguration configuration) {
        return call(request, configuration, false);
    }

    @Override
    public AIResponse generateStructured(AIRequest request, AIProviderConfiguration configuration) {
        return call(request, configuration, true);
    }

    @Override
    public boolean validateConnection(AIProviderConfiguration configuration) {
        String url = modelsUrl(configuration);
        if (url != null) {
            getJson(client(10), url, authHeaders(configuration));
            return true;
        }
        // No listing endpoint: a minimal generation is the reliable check.
        call(new AIRequest(fallbackModel(), List.of(AIMessage.user("ping")), null, 8, null),
                configuration, false);
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

    private String fallbackModel() {
        List<AIModel> fallback = fallbackModels();
        return fallback.isEmpty() ? "gpt-4o-mini" : fallback.get(0).id();
    }

    private AIResponse call(AIRequest request, AIProviderConfiguration configuration, boolean json) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("messages", messages(request));
        if (request.temperature() != null) {
            payload.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            payload.put("max_tokens", request.maxTokens());
        }
        if (json) {
            payload.put("response_format", Map.of("type", "json_object"));
        }
        if (request.hasTools()) {
            payload.put("tools", tools(request.tools()));
        }

        Map<String, Object> body = postJson(client(120), chatUrl(configuration, request.model()),
                authHeaders(configuration), payload);
        AIUsage usage = AIUsage.of(asLong(dig(body, "usage", "prompt_tokens")),
                asLong(dig(body, "usage", "completion_tokens")));

        List<AIToolCall> toolCalls = extractToolCalls(body);
        if (!toolCalls.isEmpty()) {
            return AIResponse.toolCalls(extractContent(body), toolCalls, request.model(), usage);
        }

        String text = extractContent(body);
        if (text == null) {
            throw AIException.badResponse(getProviderType() + " returned no message content.");
        }
        return json ? AIResponse.structured(text, parse(text), request.model(), usage)
                : AIResponse.text(text, request.model(), usage);
    }

    /** Each tool as OpenAI declares it: {@code {type:function, function:{name, description, parameters}}}. */
    private static List<Map<String, Object>> tools(List<AIToolSpec> specs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AIToolSpec spec : specs) {
            out.add(Map.of("type", "function", "function", Map.of(
                    "name", spec.name(),
                    "description", spec.description() == null ? "" : spec.description(),
                    "parameters", spec.parameters())));
        }
        return out;
    }

    private static String extractContent(Map<String, Object> body) {
        Object choices = body.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> choice) {
            Object message = choice.get("message");
            if (message instanceof Map<?, ?> m && m.get("content") != null) {
                return String.valueOf(m.get("content"));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<AIToolCall> extractToolCalls(Map<String, Object> body) {
        List<AIToolCall> calls = new ArrayList<>();
        Object choices = body.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> choice)) {
            return calls;
        }
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> m) || !(m.get("tool_calls") instanceof List<?> rawCalls)) {
            return calls;
        }
        for (Object raw : rawCalls) {
            if (!(raw instanceof Map<?, ?> call)) {
                continue;
            }
            String id = String.valueOf(((Map<String, Object>) call).getOrDefault("id", ""));
            Object function = ((Map<String, Object>) call).get("function");
            if (function instanceof Map<?, ?> fn && fn.get("name") != null) {
                String name = String.valueOf(fn.get("name"));
                Map<String, Object> args = parseArguments(fn.get("arguments"));
                calls.add(new AIToolCall(id, name, args));
            }
        }
        return calls;
    }

    /** OpenAI sends tool arguments as a JSON <em>string</em>; parse it back to a map, tolerating a blank one. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(Object arguments) {
        if (arguments == null) {
            return Map.of();
        }
        if (arguments instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        String json = String.valueOf(arguments);
        if (json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> messages(AIRequest request) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AIMessage message : request.messages()) {
            String role = message.role().name().toLowerCase(Locale.ROOT);
            if (message.role() == AIMessage.Role.TOOL) {
                // A tool result correlates to its call by id, and its content is data, never an instruction.
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("role", "tool");
                tool.put("tool_call_id", message.toolCallId() == null ? "" : message.toolCallId());
                tool.put("content", message.content() == null ? "" : message.content());
                out.add(tool);
            } else if (message.hasToolCalls()) {
                // The assistant turn that requested tools, rendered back so the model sees its own decision.
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", message.content());
                assistant.put("tool_calls", renderToolCalls(message.toolCalls()));
                out.add(assistant);
            } else {
                Map<String, Object> plain = new LinkedHashMap<>();
                plain.put("role", role);
                plain.put("content", message.content());
                out.add(plain);
            }
        }
        return out;
    }

    private List<Map<String, Object>> renderToolCalls(List<AIToolCall> toolCalls) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AIToolCall call : toolCalls) {
            String args;
            try {
                args = mapper.writeValueAsString(call.arguments());
            } catch (Exception ex) {
                args = "{}";
            }
            out.add(Map.of("id", call.id() == null ? "" : call.id(), "type", "function",
                    "function", Map.of("name", call.name(), "arguments", args)));
        }
        return out;
    }
}
