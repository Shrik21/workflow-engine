package com.orchpilot.workflow.ai.cli;

/**
 * The AI CLI tools the platform knows how to drive.
 *
 * <p>Constants rather than an enum so that a configuration saved by a newer build stays readable to an older
 * one — an unknown type simply has no provider registered and cannot be executed, which is the safe failure.
 * Each value corresponds to one {@link AiCliProvider} bean; adding a CLI is adding a provider and a constant.
 */
public final class AiCliType {

    /** Anthropic's Claude Code CLI. The only one fully implemented today. */
    public static final String CLAUDE_CLI = "CLAUDE_CLI";

    /** Reserved: OpenAI's CLI. No provider registered yet. */
    public static final String OPENAI_CLI = "OPENAI_CLI";

    /** Reserved: Google's Gemini CLI. No provider registered yet. */
    public static final String GEMINI_CLI = "GEMINI_CLI";

    /** Reserved: Ollama's CLI. No provider registered yet. */
    public static final String OLLAMA_CLI = "OLLAMA_CLI";

    private AiCliType() {
    }
}
