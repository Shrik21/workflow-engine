package com.orchpilot.workflow.ai.provider;

import com.orchpilot.workflow.ai.AIException;
import com.orchpilot.workflow.ai.AIProviderType;
import com.orchpilot.workflow.ai.model.AIModel;
import com.orchpilot.workflow.ai.model.AIProviderConfiguration;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Azure OpenAI: the OpenAI dialect, routed through a deployment.
 *
 * <p>Two differences the base absorbs through its hooks: the key travels in an {@code api-key} header rather than
 * a Bearer token, and the URL names a <em>deployment</em> and an API version instead of a model — so the "model"
 * a node picks is the deployment name. The endpoint (the resource URL) and the {@code apiVersion} setting come
 * from the connection.
 */
@Component
public class AzureOpenAIProvider extends OpenAICompatibleProvider {

    @Override
    public AIProviderType getProviderType() {
        return AIProviderType.AZURE_OPENAI;
    }

    @Override
    protected String defaultEndpoint() {
        // Azure has no global default; the resource URL must be configured on the connection.
        return "";
    }

    @Override
    protected Map<String, String> authHeaders(AIProviderConfiguration configuration) {
        return configuration.apiKey() == null ? Map.of() : Map.of("api-key", configuration.apiKey());
    }

    @Override
    protected String chatUrl(AIProviderConfiguration configuration, String model) {
        if (base(configuration).isBlank()) {
            throw new AIException("AZURE_ENDPOINT_MISSING",
                    "Azure OpenAI needs the resource endpoint configured on the connection.", false);
        }
        String apiVersion = configuration.setting("apiVersion", "2024-06-01");
        return base(configuration) + "/openai/deployments/" + model + "/chat/completions?api-version="
                + apiVersion;
    }

    @Override
    protected String modelsUrl(AIProviderConfiguration configuration) {
        // Azure lists deployments, not models, through a different management API; skip discovery.
        return null;
    }

    @Override
    protected List<AIModel> fallbackModels() {
        return List.of(new AIModel("gpt-4o", "gpt-4o (deployment name)", true, true, true));
    }
}
