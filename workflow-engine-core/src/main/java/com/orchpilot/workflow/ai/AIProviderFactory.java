package com.orchpilot.workflow.ai;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a {@link AIModelProvider} for a provider type.
 *
 * <p>Every adapter registers itself simply by being a Spring bean; this collects them by
 * {@link AIModelProvider#getProviderType()} at startup. Adding a provider therefore never edits this class —
 * the whole point of the factory pattern the specification asks for — and the engine asks only "give me the
 * provider for OPENAI", never "new OpenAIProvider()".
 */
@Component
public class AIProviderFactory {

    private final Map<AIProviderType, AIModelProvider> providers = new EnumMap<>(AIProviderType.class);

    public AIProviderFactory(List<AIModelProvider> discovered) {
        for (AIModelProvider provider : discovered) {
            providers.put(provider.getProviderType(), provider);
        }
    }

    /**
     * @param type the provider
     * @return its adapter
     * @throws AIException when no adapter is registered for the type
     */
    public AIModelProvider forType(AIProviderType type) {
        AIModelProvider provider = providers.get(type);
        if (provider == null) {
            throw new AIException("PROVIDER_NOT_SUPPORTED",
                    "No adapter is installed for AI provider " + type, false);
        }
        return provider;
    }

    /** @return whether an adapter is registered for the type */
    public boolean supports(AIProviderType type) {
        return providers.containsKey(type);
    }
}
