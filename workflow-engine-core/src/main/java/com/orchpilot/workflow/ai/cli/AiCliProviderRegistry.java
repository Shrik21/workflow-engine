package com.orchpilot.workflow.ai.cli;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves an {@link AiCliProvider} for a CLI type.
 *
 * <p>The same contribution model as {@code AIProviderFactory}: an adapter registers itself by being a Spring
 * bean, so adding {@code GeminiCliProvider} never edits this class. Deliberately a separate registry rather
 * than an extension of the HTTP one — the two abstractions answer different questions, and a single map holding
 * both would have to be keyed by something that means "HTTP provider or CLI", which is not a real category.
 */
@Component
public class AiCliProviderRegistry {

    private final Map<String, AiCliProvider> providers = new LinkedHashMap<>();

    public AiCliProviderRegistry(List<AiCliProvider> discovered) {
        for (AiCliProvider provider : discovered) {
            providers.put(provider.type(), provider);
        }
    }

    /**
     * @param type an {@link AiCliType} constant
     * @return its adapter
     * @throws AiCliException when none is installed
     */
    public AiCliProvider forType(String type) {
        AiCliProvider provider = providers.get(type);
        if (provider == null) {
            throw new AiCliException("AI_CLI_TYPE_NOT_SUPPORTED",
                    "No adapter is installed for AI CLI type '" + type + "'.");
        }
        return provider;
    }

    public boolean supports(String type) {
        return providers.containsKey(type);
    }

    /** @return every installed provider, for the settings page's type dropdown */
    public List<AiCliProvider> all() {
        return List.copyOf(providers.values());
    }

    /** @return the provider's display name, or the raw type when nothing is installed for it */
    public String displayName(String type) {
        AiCliProvider provider = providers.get(type);
        return provider == null ? type : provider.displayName();
    }
}
