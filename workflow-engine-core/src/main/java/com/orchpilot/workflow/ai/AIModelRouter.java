package com.orchpilot.workflow.ai;

import com.orchpilot.workflow.ai.model.AIProviderConfiguration;
import com.orchpilot.workflow.ai.model.AIRequest;
import com.orchpilot.workflow.ai.model.AIResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes an AI request to a provider, retrying transient failures and falling back to alternate providers.
 *
 * <h2>Retry, then fall back — and never blindly</h2>
 *
 * Within one provider a transient failure (a timeout, a rate limit) is retried up to the configured count with
 * a delay; a permanent one (bad credentials, an unsupported model) is not, because repeating it only wastes
 * time and, for a call with side effects, could be dangerous. When a provider is exhausted, the router moves to
 * the next configured fallback — OpenAI → Claude → Ollama, say — so a single provider outage does not fail the
 * workflow. The order is data the caller supplies; the router hardcodes none of it.
 */
@Component
public class AIModelRouter {

    private static final Logger log = LoggerFactory.getLogger(AIModelRouter.class);

    private final AIProviderFactory factory;

    public AIModelRouter(AIProviderFactory factory) {
        this.factory = factory;
    }

    /** One provider option: the request to send and the connection to send it through. */
    public record Attempt(AIRequest request, AIProviderConfiguration configuration) {
    }

    /** Retry behaviour for a single provider. */
    public record RetryPolicy(int maxAttempts, long delayMillis) {
        public static RetryPolicy of(int retries, long delayMillis) {
            return new RetryPolicy(Math.max(1, retries + 1), Math.max(0, delayMillis));
        }
    }

    /**
     * Executes against the primary and, on exhaustion, each fallback in turn.
     *
     * @param primary   the first provider to try
     * @param fallbacks ordered alternates; may be empty
     * @param retry     per-provider retry behaviour
     * @param structured whether to request schema-constrained output
     * @return the first successful response
     * @throws AIException when every provider and every retry has failed
     */
    public AIResponse execute(Attempt primary, List<Attempt> fallbacks, RetryPolicy retry, boolean structured) {
        List<Attempt> chain = new ArrayList<>();
        chain.add(primary);
        if (fallbacks != null) {
            chain.addAll(fallbacks);
        }

        AIException last = null;
        for (Attempt attempt : chain) {
            AIModelProvider provider = factory.forType(attempt.configuration().providerType());
            for (int tries = 0; tries < retry.maxAttempts(); tries++) {
                try {
                    return structured
                            ? provider.generateStructured(attempt.request(), attempt.configuration())
                            : provider.generate(attempt.request(), attempt.configuration());
                } catch (AIException ex) {
                    last = ex;
                    if (!ex.isRetryable() || tries + 1 >= retry.maxAttempts()) {
                        log.warn("AI provider {} failed ({}), {}", attempt.configuration().providerType(),
                                ex.getCode(), ex.isRetryable() ? "moving to the next provider" : "not retryable");
                        break;
                    }
                    log.info("AI provider {} failed ({}); retry {}/{}", attempt.configuration().providerType(),
                            ex.getCode(), tries + 1, retry.maxAttempts() - 1);
                    sleep(retry.delayMillis());
                }
            }
        }
        throw last != null ? last
                : new AIException("PROVIDER_EXHAUSTED", "No AI provider produced a response", false);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
