package com.orchpilot.workflow.ai.provider;

import com.orchpilot.workflow.ai.AIProviderType;
import org.springframework.stereotype.Component;

/**
 * A self-hosted vLLM server, which exposes an OpenAI-compatible API. The endpoint (e.g.
 * {@code http://server:8000/v1}) is configured on the connection; the key is optional.
 */
@Component
public class VllmProvider extends OpenAICompatibleProvider {

    @Override
    public AIProviderType getProviderType() {
        return AIProviderType.VLLM;
    }

    @Override
    protected String defaultEndpoint() {
        return "http://localhost:8000/v1";
    }
}
