package com.orchpilot.workflow.ai.provider;

import com.orchpilot.workflow.ai.AIModelProvider;
import com.orchpilot.workflow.ai.AIProviderFactory;
import com.orchpilot.workflow.ai.AIProviderType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the provider-abstraction contract: every adapter reports the {@link AIProviderType} it claims, and the
 * factory can route to each by that type alone. This is what lets the engine add a provider by dropping in a bean
 * — if a new adapter forgets or mislabels its type, this test fails rather than the engine silently losing a
 * provider at runtime.
 */
class ProviderRegistrationTest {

    private final List<AIModelProvider> providers = List.of(
            new MockProvider(),
            new OllamaProvider(),
            new OpenAIProvider(),
            new ClaudeProvider(),
            new AzureOpenAIProvider(),
            new VllmProvider(),
            new NvidiaNimProvider(),
            new VertexAIProvider(),
            new GeminiProvider(),
            new BedrockProvider());

    @Test
    void everyProviderReportsItsType() {
        AIProviderFactory factory = new AIProviderFactory(providers);
        for (AIModelProvider provider : providers) {
            AIProviderType type = provider.getProviderType();
            assertThat(factory.supports(type)).as("factory supports %s", type).isTrue();
            assertThat(factory.forType(type)).as("factory routes %s", type).isSameAs(provider);
        }
    }

    @Test
    void phase2ProvidersAreAllRegistered() {
        AIProviderFactory factory = new AIProviderFactory(providers);
        assertThat(factory.supports(AIProviderType.GEMINI)).isTrue();
        assertThat(factory.supports(AIProviderType.AZURE_OPENAI)).isTrue();
        assertThat(factory.supports(AIProviderType.AWS_BEDROCK)).isTrue();
        assertThat(factory.supports(AIProviderType.VERTEX_AI)).isTrue();
        assertThat(factory.supports(AIProviderType.NVIDIA_NIM)).isTrue();
        assertThat(factory.supports(AIProviderType.VLLM)).isTrue();
    }

    @Test
    void openAiCompatibleProvidersAdvertiseStructuredOutput() {
        assertThat(new AzureOpenAIProvider().supportsStructuredOutput()).isTrue();
        assertThat(new GeminiProvider().supportsStructuredOutput()).isTrue();
        assertThat(new BedrockProvider().supportsStructuredOutput()).isTrue();
    }
}
