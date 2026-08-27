package com.orchpilot.workflow.ai.provider;

import com.orchpilot.workflow.ai.AIProviderType;
import com.orchpilot.workflow.ai.model.AIModel;
import org.springframework.stereotype.Component;

import java.util.List;

/** OpenAI, over Chat Completions. The reference OpenAI-compatible provider. */
@Component
public class OpenAIProvider extends OpenAICompatibleProvider {

    @Override
    public AIProviderType getProviderType() {
        return AIProviderType.OPENAI;
    }

    @Override
    protected String defaultEndpoint() {
        return "https://api.openai.com/v1";
    }

    @Override
    public boolean supportsVision() {
        return true;
    }

    @Override
    protected List<AIModel> fallbackModels() {
        return List.of(
                new AIModel("gpt-4o", "GPT-4o", true, true, true),
                new AIModel("gpt-4o-mini", "GPT-4o mini", true, true, true),
                new AIModel("gpt-4.1", "GPT-4.1", true, true, true));
    }
}
