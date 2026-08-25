package com.orchpilot.workflow.ai.provider;

import com.orchpilot.workflow.ai.AIProviderType;
import org.springframework.stereotype.Component;

/**
 * NVIDIA NIM, which serves an OpenAI-compatible API behind a Bearer key. Kept as its own provider — not folded
 * into Ollama or vLLM — because its endpoint, key and catalogue are its own; only the wire format is shared.
 */
@Component
public class NvidiaNimProvider extends OpenAICompatibleProvider {

    @Override
    public AIProviderType getProviderType() {
        return AIProviderType.NVIDIA_NIM;
    }

    @Override
    protected String defaultEndpoint() {
        return "https://integrate.api.nvidia.com/v1";
    }
}
