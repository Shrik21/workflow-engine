package com.orchpilot.workflow.ai;

import com.orchpilot.workflow.ai.model.AIModel;
import com.orchpilot.workflow.ai.model.AIProviderConfiguration;
import com.orchpilot.workflow.ai.model.AIRequest;
import com.orchpilot.workflow.ai.model.AIResponse;
import com.orchpilot.workflow.ai.model.AIUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The router's retry-then-fallback behaviour, with stub providers so nothing touches a network. Covers the
 * fallback-provider and retry cases the specification calls out.
 */
class AIModelRouterTest {

    /** A stub provider that fails a set number of times (with a chosen retryability), then succeeds. */
    private static class StubProvider implements AIModelProvider {
        private final AIProviderType type;
        private final int failCount;
        private final boolean retryable;
        final AtomicInteger calls = new AtomicInteger();

        StubProvider(AIProviderType type, int failCount, boolean retryable) {
            this.type = type;
            this.failCount = failCount;
            this.retryable = retryable;
        }

        @Override
        public AIProviderType getProviderType() {
            return type;
        }

        @Override
        public List<AIModel> getAvailableModels(AIProviderConfiguration configuration) {
            return List.of();
        }

        @Override
        public AIResponse generate(AIRequest request, AIProviderConfiguration configuration) {
            if (calls.getAndIncrement() < failCount) {
                throw new AIException("STUB_FAIL", "stub failure", retryable);
            }
            return AIResponse.text("from " + type, request.model(), AIUsage.none());
        }

        @Override
        public boolean validateConnection(AIProviderConfiguration configuration) {
            return true;
        }
    }

    private static AIModelRouter.Attempt attempt(AIProviderType type) {
        return new AIModelRouter.Attempt(new AIRequest("m", List.of(), null, null, null),
                new AIProviderConfiguration(type, null, null, null));
    }

    @Test
    @DisplayName("retries a transient failure on the same provider before giving up")
    void retriesTransient() {
        StubProvider openai = new StubProvider(AIProviderType.OPENAI, 2, true);
        AIModelRouter router = new AIModelRouter(new AIProviderFactory(List.of(openai)));

        AIResponse response = router.execute(attempt(AIProviderType.OPENAI), List.of(),
                AIModelRouter.RetryPolicy.of(3, 0), false);

        assertThat(response.text()).isEqualTo("from OPENAI");
        assertThat(openai.calls.get()).isEqualTo(3); // two failures, then success
    }

    @Test
    @DisplayName("falls back to the next provider when the primary is exhausted")
    void fallsBack() {
        StubProvider openai = new StubProvider(AIProviderType.OPENAI, 5, true);
        StubProvider claude = new StubProvider(AIProviderType.ANTHROPIC, 0, true);
        AIModelRouter router = new AIModelRouter(new AIProviderFactory(List.of(openai, claude)));

        AIResponse response = router.execute(attempt(AIProviderType.OPENAI),
                List.of(attempt(AIProviderType.ANTHROPIC)), AIModelRouter.RetryPolicy.of(1, 0), false);

        assertThat(response.text()).isEqualTo("from ANTHROPIC");
    }

    @Test
    @DisplayName("a non-retryable failure is not retried on the same provider")
    void doesNotRetryPermanent() {
        StubProvider openai = new StubProvider(AIProviderType.OPENAI, 1, false);
        StubProvider claude = new StubProvider(AIProviderType.ANTHROPIC, 0, true);
        AIModelRouter router = new AIModelRouter(new AIProviderFactory(List.of(openai, claude)));

        AIResponse response = router.execute(attempt(AIProviderType.OPENAI),
                List.of(attempt(AIProviderType.ANTHROPIC)), AIModelRouter.RetryPolicy.of(5, 0), false);

        assertThat(response.text()).isEqualTo("from ANTHROPIC");
        assertThat(openai.calls.get()).isEqualTo(1); // failed once, not retried, moved on
    }

    @Test
    @DisplayName("when every provider fails, the last error is raised")
    void allFail() {
        StubProvider openai = new StubProvider(AIProviderType.OPENAI, 9, true);
        AIModelRouter router = new AIModelRouter(new AIProviderFactory(List.of(openai)));

        assertThatThrownBy(() -> router.execute(attempt(AIProviderType.OPENAI), List.of(),
                AIModelRouter.RetryPolicy.of(1, 0), false)).isInstanceOf(AIException.class);
    }

    @Test
    @DisplayName("the factory refuses an unregistered provider")
    void factoryUnknown() {
        AIProviderFactory factory = new AIProviderFactory(List.of());
        assertThat(factory.supports(AIProviderType.OPENAI)).isFalse();
        assertThatThrownBy(() -> factory.forType(AIProviderType.OPENAI)).isInstanceOf(AIException.class);
    }
}
