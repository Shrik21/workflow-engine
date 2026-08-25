package com.orchpilot.workflow.ai.model;

import java.util.Map;

/**
 * Everything a provider adapter needs to make a call, assembled at execution time from a stored connection.
 *
 * <h2>The API key is resolved here, and lives no longer than it must</h2>
 *
 * The key is not stored on the connection document — it is a reference to a secret, resolved through the secret
 * store only when a call is about to be made, and it never travels into a workflow definition, a log, or the
 * model itself. This object is short-lived: it is built for one request and discarded. {@code endpoint} carries
 * the base URL for the self-hosted and OpenAI-compatible providers; {@code settings} carries the rest
 * (a region, a deployment name) without the engine having to know what any of them mean.
 *
 * @param providerType the provider this configures
 * @param endpoint     the base URL, for self-hosted / OpenAI-compatible providers; null for a provider's default
 * @param apiKey       the resolved key, or null when the provider needs none (e.g. a local Ollama)
 * @param settings     provider-specific non-secret settings
 */
public record AIProviderConfiguration(com.orchpilot.workflow.ai.AIProviderType providerType, String endpoint,
                                      String apiKey, Map<String, Object> settings) {

    public AIProviderConfiguration {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    public String setting(String key, String fallback) {
        Object value = settings.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
