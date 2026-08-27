package com.orchpilot.workflow.ai;

import com.orchpilot.workflow.ai.model.AIModel;
import com.orchpilot.workflow.ai.model.AIProviderConfiguration;
import com.orchpilot.workflow.ai.model.AIRequest;
import com.orchpilot.workflow.ai.model.AIResponse;

import java.util.List;

/**
 * The single abstraction the workflow engine knows about AI.
 *
 * <h2>Why the engine only ever sees this</h2>
 *
 * The specification is emphatic: no OpenAI, Claude, Gemini or Ollama code may live in the workflow engine, and
 * a new provider must be addable without touching it. This interface is how that holds. The
 * {@code AIAgentNodeExecutor} and the {@link AIModelRouter} depend on this type and the provider-independent
 * request/response records — nothing else. Each concrete provider is a Spring bean discovered by
 * {@link AIProviderFactory#forType}, so contributing {@code GeminiProvider} tomorrow is one class and one enum
 * constant, with zero changes to the engine.
 */
public interface AIModelProvider {

    /** @return which provider this adapter is */
    AIProviderType getProviderType();

    /**
     * Discovers the models this provider offers, dynamically where the provider's API allows it.
     *
     * @param configuration the resolved connection (endpoint, key)
     * @return the available models; may be a curated fallback list when discovery is not possible
     */
    List<AIModel> getAvailableModels(AIProviderConfiguration configuration);

    /**
     * Generates a free-text completion.
     *
     * @param request       the provider-independent request
     * @param configuration the resolved connection
     * @return the completion
     * @throws AIException on any provider failure
     */
    AIResponse generate(AIRequest request, AIProviderConfiguration configuration);

    /**
     * Generates a completion constrained to the request's JSON schema.
     *
     * <p>Default: delegate to {@link #generate} — a provider without native structured output still returns
     * text, and the node validates and parses it, retrying per configuration. A provider that supports
     * schema-constrained output overrides this to ask for it natively.
     */
    default AIResponse generateStructured(AIRequest request, AIProviderConfiguration configuration) {
        return generate(request, configuration);
    }

    /**
     * Checks the connection works, for the "Test" button.
     *
     * @param configuration the resolved connection
     * @return true when a minimal call succeeds
     */
    boolean validateConnection(AIProviderConfiguration configuration);

    default boolean supportsToolCalling() {
        return false;
    }

    default boolean supportsStreaming() {
        return false;
    }

    default boolean supportsVision() {
        return false;
    }

    default boolean supportsStructuredOutput() {
        return false;
    }
}
