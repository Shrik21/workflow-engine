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
 * Google Gemini, over the Generative Language API.
 *
 * <p>Gemini's shape is its own — {@code contents} with {@code parts}, a separate {@code systemInstruction}, and
 * roles of {@code user}/{@code model} — which this adapter maps to and from the engine's provider-independent
 * request and response, so the engine never learns any of it. Structured output is requested with
 * {@code responseMimeType: application/json}, which Gemini supports natively.
 */
@Component
public class GeminiProvider extends AbstractHttpProvider {

    private static final String DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta";

    private static final List<AIModel> FALLBACK = List.of(
            new AIModel("gemini-2.0-flash", "Gemini 2.0 Flash", true, true, true),
            new AIModel("gemini-2.5-pro", "Gemini 2.5 Pro", true, true, true),
            new AIModel("gemini-2.5-flash", "Gemini 2.5 Flash", true, true, true));

    @Override
    public AIProviderType getProviderType() {
        return AIProviderType.GEMINI;
    }

    @Override
    public List<AIModel> getAvailableModels(AIProviderConfiguration configuration) {
        try {
            Map<String, Object> body = getJson(client(15),
                    base(configuration) + "/models?key=" + key(configuration), Map.of());
            List<AIModel> models = new ArrayList<>();
            if (body.get("models") instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> model && model.get("name") != null) {
                        String name = String.valueOf(model.get("name")).replaceFirst("^models/", "");
                        models.add(new AIModel(name, name, true, true, true));
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
        return call(request, configuration, false);
    }

    @Override
    public AIResponse generateStructured(AIRequest request, AIProviderConfiguration configuration) {
        return call(request, configuration, true);
    }

    @Override
    public boolean validateConnection(AIProviderConfiguration configuration) {
        getJson(client(10), base(configuration) + "/models?key=" + key(configuration), Map.of());
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

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    private AIResponse call(AIRequest request, AIProviderConfiguration configuration, boolean json) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contents", contents(request));
        String system = system(request);
        if (system != null) {
            payload.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
        }
        Map<String, Object> generation = new LinkedHashMap<>();
        if (request.temperature() != null) {
            generation.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            generation.put("maxOutputTokens", request.maxTokens());
        }
        if (json) {
            generation.put("responseMimeType", "application/json");
        }
        if (!generation.isEmpty()) {
            payload.put("generationConfig", generation);
        }
        if (request.hasTools()) {
            payload.put("tools", List.of(Map.of("functionDeclarations", functionDeclarations(request.tools()))));
        }

        String url = base(configuration) + "/models/" + request.model() + ":generateContent?key="
                + key(configuration);
        Map<String, Object> body = postJson(client(120), url, Map.of(), payload);
        AIUsage usage = AIUsage.of(asLong(dig(body, "usageMetadata", "promptTokenCount")),
                asLong(dig(body, "usageMetadata", "candidatesTokenCount")));

        List<AIToolCall> toolCalls = extractToolCalls(body);
        if (!toolCalls.isEmpty()) {
            return AIResponse.toolCalls(firstText(body), toolCalls, request.model(), usage);
        }

        String text = firstText(body);
        if (text == null) {
            throw AIException.badResponse("Gemini returned no content.");
        }
        return json ? AIResponse.structured(text, parse(text), request.model(), usage)
                : AIResponse.text(text, request.model(), usage);
    }

    /** Each tool as Gemini declares it, under a single {@code tools[].functionDeclarations} entry. */
    private static List<Map<String, Object>> functionDeclarations(List<AIToolSpec> specs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AIToolSpec spec : specs) {
            out.add(Map.of("name", spec.name(),
                    "description", spec.description() == null ? "" : spec.description(),
                    "parameters", spec.parameters()));
        }
        return out;
    }

    /** Gemini has no call id, so each function call is correlated back to its result by tool name. */
    @SuppressWarnings("unchecked")
    private static List<AIToolCall> extractToolCalls(Map<String, Object> body) {
        List<AIToolCall> calls = new ArrayList<>();
        for (Map<String, Object> part : parts(body)) {
            if (part.get("functionCall") instanceof Map<?, ?> fn && fn.get("name") != null) {
                String name = String.valueOf(((Map<String, Object>) fn).get("name"));
                Object args = ((Map<String, Object>) fn).get("args");
                Map<String, Object> arguments = args instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                calls.add(new AIToolCall(name, name, arguments));
            }
        }
        return calls;
    }

    /**
     * user/model turns; Gemini calls the assistant role "model" and carries the system prompt separately. Tool
     * traffic uses parts: a {@code functionCall} part on a model turn for a call, a {@code functionResponse} part
     * on a user turn for the answer, correlated by tool name since Gemini issues no call id.
     */
    private static List<Map<String, Object>> contents(AIRequest request) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AIMessage message : request.messages()) {
            if (message.role() == AIMessage.Role.SYSTEM) {
                continue;
            }
            if (message.role() == AIMessage.Role.TOOL) {
                out.add(Map.of("role", "user", "parts", List.of(Map.of("functionResponse", Map.of(
                        "name", message.toolCallId() == null ? "" : message.toolCallId(),
                        "response", Map.of("result", message.content() == null ? "" : message.content()))))));
            } else if (message.role() == AIMessage.Role.ASSISTANT && message.hasToolCalls()) {
                List<Map<String, Object>> parts = new ArrayList<>();
                if (message.content() != null && !message.content().isBlank()) {
                    parts.add(Map.of("text", message.content()));
                }
                for (AIToolCall call : message.toolCalls()) {
                    parts.add(Map.of("functionCall", Map.of("name", call.name(), "args", call.arguments())));
                }
                out.add(Map.of("role", "model", "parts", parts));
            } else {
                String role = message.role() == AIMessage.Role.ASSISTANT ? "model" : "user";
                out.add(Map.of("role", role,
                        "parts", List.of(Map.of("text", message.content() == null ? "" : message.content()))));
            }
        }
        if (out.isEmpty()) {
            out.add(Map.of("role", "user", "parts", List.of(Map.of("text", ""))));
        }
        return out;
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

    private static String firstText(Map<String, Object> body) {
        for (Map<String, Object> part : parts(body)) {
            if (part.get("text") != null) {
                return String.valueOf(part.get("text"));
            }
        }
        return null;
    }

    /** The parts of the first candidate's content, or an empty list when the response has none. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parts(Map<String, Object> body) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (body.get("candidates") instanceof List<?> candidates && !candidates.isEmpty()
                && candidates.get(0) instanceof Map<?, ?> candidate) {
            Object content = ((Map<String, Object>) candidate).get("content");
            if (content instanceof Map<?, ?> c && ((Map<String, Object>) c).get("parts") instanceof List<?> parts) {
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> p) {
                        out.add((Map<String, Object>) p);
                    }
                }
            }
        }
        return out;
    }

    private static String key(AIProviderConfiguration configuration) {
        if (configuration.apiKey() == null || configuration.apiKey().isBlank()) {
            throw AIException.unauthorized("Gemini requires an API key.");
        }
        return configuration.apiKey();
    }

    private static String base(AIProviderConfiguration configuration) {
        String endpoint = configuration.endpoint();
        String base = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
