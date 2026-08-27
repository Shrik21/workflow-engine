package com.orchpilot.workflow.ai.provider;

import com.orchpilot.workflow.ai.AIException;
import com.orchpilot.workflow.ai.AIProviderType;
import com.orchpilot.workflow.ai.model.AIMessage;
import com.orchpilot.workflow.ai.model.AIModel;
import com.orchpilot.workflow.ai.model.AIProviderConfiguration;
import com.orchpilot.workflow.ai.model.AIRequest;
import com.orchpilot.workflow.ai.model.AIResponse;
import com.orchpilot.workflow.ai.model.AIUsage;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-hosted Ollama, over its native chat API. No key; the connection carries only the server URL, so this is
 * the provider a developer can run and exercise entirely offline.
 */
@Component
public class OllamaProvider extends AbstractHttpProvider {

    private static final String DEFAULT_ENDPOINT = "http://localhost:11434";

    @Override
    public AIProviderType getProviderType() {
        return AIProviderType.OLLAMA;
    }

    @Override
    public List<AIModel> getAvailableModels(AIProviderConfiguration configuration) {
        // Ollama exposes exactly the models pulled onto the server, so they are always discovered, never guessed.
        Map<String, Object> body = getJson(client(15), base(configuration) + "/api/tags", Map.of());
        List<AIModel> models = new ArrayList<>();
        Object list = body.get("models");
        if (list instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> model && model.get("name") != null) {
                    String name = String.valueOf(model.get("name"));
                    models.add(new AIModel(name, name, true, true, false));
                }
            }
        }
        return models;
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
        getJson(client(10), base(configuration) + "/api/tags", Map.of());
        return true;
    }

    @Override
    public boolean supportsStructuredOutput() {
        return true;
    }

    private AIResponse call(AIRequest request, AIProviderConfiguration configuration, boolean json) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("stream", false);
        payload.put("messages", messages(request));
        if (json) {
            payload.put("format", "json");
        }
        Map<String, Object> options = new LinkedHashMap<>();
        if (request.temperature() != null) {
            options.put("temperature", request.temperature());
        }
        if (!options.isEmpty()) {
            payload.put("options", options);
        }

        Map<String, Object> body = postJson(client(120), base(configuration) + "/api/chat", Map.of(), payload);
        Object content = dig(body, "message", "content");
        if (content == null) {
            throw AIException.badResponse("Ollama returned no message content.");
        }
        AIUsage usage = AIUsage.of(asLong(body.get("prompt_eval_count")), asLong(body.get("eval_count")));
        String text = String.valueOf(content);
        return json ? AIResponse.structured(text, parse(text), request.model(), usage)
                : AIResponse.text(text, request.model(), usage);
    }

    private static List<Map<String, Object>> messages(AIRequest request) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AIMessage message : request.messages()) {
            out.add(Map.of("role", message.role().name().toLowerCase(java.util.Locale.ROOT),
                    "content", message.content()));
        }
        return out;
    }

    private static String base(AIProviderConfiguration configuration) {
        String endpoint = configuration.endpoint();
        String base = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
