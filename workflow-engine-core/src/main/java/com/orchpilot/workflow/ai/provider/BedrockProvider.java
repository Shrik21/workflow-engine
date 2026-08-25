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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS Bedrock, calling the {@code InvokeModel} runtime endpoint directly with SigV4-signed HTTP.
 *
 * <p>The AWS SDK cannot be added in this offline build, so the request is signed by {@link SigV4Signer} and the
 * body is shaped for the Anthropic-on-Bedrock family (the same {@code messages}/{@code system} contract the
 * {@link ClaudeProvider} uses, wrapped in Bedrock's {@code anthropic_version} envelope). Credentials come from the
 * connection: the key holds {@code accessKeyId:secretAccessKey}, and the region (and optional session token) come
 * from the connection settings. Nothing about AWS leaks into the engine — it still sees only an
 * {@link com.orchpilot.workflow.ai.AIModelProvider}.
 */
@Component
public class BedrockProvider extends AbstractHttpProvider {

    private static final String BEDROCK_ANTHROPIC_VERSION = "bedrock-2023-05-31";

    private static final List<AIModel> FALLBACK = List.of(
            new AIModel("anthropic.claude-3-5-sonnet-20241022-v2:0", "Claude 3.5 Sonnet (Bedrock)", true, true, true),
            new AIModel("anthropic.claude-3-5-haiku-20241022-v1:0", "Claude 3.5 Haiku (Bedrock)", true, true, false),
            new AIModel("anthropic.claude-3-opus-20240229-v1:0", "Claude 3 Opus (Bedrock)", true, true, true));

    @Override
    public AIProviderType getProviderType() {
        return AIProviderType.AWS_BEDROCK;
    }

    @Override
    public List<AIModel> getAvailableModels(AIProviderConfiguration configuration) {
        // Listing foundation models is a separate signed control-plane call; the curated set is enough to run.
        return FALLBACK;
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
        // A minimal generation proves both the credentials and the region are usable.
        AIRequest probe = new AIRequest(FALLBACK.get(0).id(),
                List.of(AIMessage.user("ping")), 0.0, 1, null);
        call(probe, configuration, false);
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

    private AIResponse call(AIRequest request, AIProviderConfiguration configuration, boolean json) {
        String region = configuration.setting("region", "us-east-1");
        Credentials credentials = credentials(configuration);

        String host = "bedrock-runtime." + region + ".amazonaws.com";
        String path = "/model/" + request.model() + "/invoke";
        String url = "https://" + host + path;

        Map<String, Object> payload = body(request, json);
        byte[] bytes;
        String bodyString;
        try {
            bodyString = mapper.writeValueAsString(payload);
            bytes = bodyString.getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw AIException.badResponse("Could not encode the Bedrock request body.");
        }

        Map<String, String> headers = new LinkedHashMap<>(SigV4Signer.sign(host, path, region, "bedrock", bytes,
                credentials.accessKey(), credentials.secretKey(), credentials.sessionToken()));
        headers.put("Accept", "application/json");

        Map<String, Object> response = postJson(client(120), url, headers, bodyString);
        String text = firstText(response);
        if (text == null) {
            throw AIException.badResponse("Bedrock returned no content.");
        }
        AIUsage usage = AIUsage.of(asLong(dig(response, "usage", "input_tokens")),
                asLong(dig(response, "usage", "output_tokens")));
        return json ? AIResponse.structured(text, parse(text), request.model(), usage)
                : AIResponse.text(text, request.model(), usage);
    }

    private static Map<String, Object> body(AIRequest request, boolean json) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("anthropic_version", BEDROCK_ANTHROPIC_VERSION);
        payload.put("max_tokens", request.maxTokens() == null ? 1024 : request.maxTokens());
        if (request.temperature() != null) {
            payload.put("temperature", request.temperature());
        }

        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (AIMessage message : request.messages()) {
            if (message.role() == AIMessage.Role.SYSTEM) {
                if (system.length() > 0) {
                    system.append("\n\n");
                }
                system.append(message.content());
                continue;
            }
            String role = message.role() == AIMessage.Role.ASSISTANT ? "assistant" : "user";
            messages.add(Map.of("role", role, "content", message.content()));
        }
        if (messages.isEmpty()) {
            messages.add(Map.of("role", "user", "content", ""));
        }
        String prompt = system.toString();
        if (json) {
            prompt = (prompt.isEmpty() ? "" : prompt + "\n\n")
                    + "Respond with a single valid JSON value and nothing else.";
        }
        if (!prompt.isEmpty()) {
            payload.put("system", prompt);
        }
        payload.put("messages", messages);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static String firstText(Map<String, Object> body) {
        if (body.get("content") instanceof List<?> content && !content.isEmpty()
                && content.get(0) instanceof Map<?, ?> block && block.get("text") != null) {
            return String.valueOf(((Map<String, Object>) block).get("text"));
        }
        return null;
    }

    private static Credentials credentials(AIProviderConfiguration configuration) {
        String key = configuration.apiKey();
        if (key == null || !key.contains(":")) {
            throw AIException.unauthorized(
                    "Bedrock needs credentials as accessKeyId:secretAccessKey in the connection key.");
        }
        int split = key.indexOf(':');
        String accessKey = key.substring(0, split).trim();
        String secretKey = key.substring(split + 1).trim();
        if (accessKey.isEmpty() || secretKey.isEmpty()) {
            throw AIException.unauthorized("Bedrock access key or secret key is missing.");
        }
        String sessionToken = configuration.setting("sessionToken", null);
        return new Credentials(accessKey, secretKey, sessionToken);
    }

    private record Credentials(String accessKey, String secretKey, String sessionToken) {
    }
}
