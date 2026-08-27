package com.orchpilot.workflow.ai;

/**
 * The AI model providers the platform can talk to.
 *
 * <p>An enum, not free-form strings, so the router, the factory and the designer agree on the exact set — but
 * the <em>set</em> is the only thing the engine hardcodes. Which models a provider offers, and how it is called,
 * live entirely behind {@link AIModelProvider}, so adding a provider is adding one adapter and one constant, and
 * never a change to the workflow engine. {@link #MOCK} exists for tests and offline development: it needs no
 * network and no key, so the node, the router and the mapping can all be exercised deterministically.
 */
public enum AIProviderType {

    OPENAI,
    ANTHROPIC,
    GEMINI,
    AZURE_OPENAI,
    AWS_BEDROCK,
    VERTEX_AI,
    OLLAMA,
    NVIDIA_NIM,
    VLLM,
    MOCK
}
