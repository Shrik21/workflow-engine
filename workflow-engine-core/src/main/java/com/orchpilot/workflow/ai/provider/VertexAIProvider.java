package com.orchpilot.workflow.ai.provider;

import com.orchpilot.workflow.ai.AIException;
import com.orchpilot.workflow.ai.AIProviderType;
import com.orchpilot.workflow.ai.model.AIModel;
import com.orchpilot.workflow.ai.model.AIProviderConfiguration;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Google Vertex AI, through its OpenAI-compatible chat endpoint.
 *
 * <p>Vertex is reached with a Bearer <em>access token</em>, which the operator supplies as the connection's key
 * (produced out-of-band, e.g. {@code gcloud auth print-access-token} or a service-account token exchange). This
 * keeps the adapter pure HTTP with no Google SDK dependency; the full service-account signing flow is a later
 * refinement. The endpoint carries the region/project path, e.g.
 * {@code https://{region}-aiplatform.googleapis.com/v1/projects/{project}/locations/{region}/endpoints/openapi}.
 */
@Component
public class VertexAIProvider extends OpenAICompatibleProvider {

    @Override
    public AIProviderType getProviderType() {
        return AIProviderType.VERTEX_AI;
    }

    @Override
    protected String defaultEndpoint() {
        return "";
    }

    @Override
    protected String chatUrl(AIProviderConfiguration configuration, String model) {
        if (base(configuration).isBlank()) {
            throw new AIException("VERTEX_ENDPOINT_MISSING",
                    "Vertex AI needs its project/region endpoint configured on the connection.", false);
        }
        return base(configuration) + "/chat/completions";
    }

    @Override
    protected String modelsUrl(AIProviderConfiguration configuration) {
        return null;
    }

    @Override
    protected List<AIModel> fallbackModels() {
        return List.of(
                new AIModel("google/gemini-2.0-flash", "Gemini 2.0 Flash", true, true, true),
                new AIModel("google/gemini-2.5-pro", "Gemini 2.5 Pro", true, true, true));
    }
}
