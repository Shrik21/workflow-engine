package com.orchpilot.workflow.externalform;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.repository.WorkflowRepository;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.task.HumanTask;
import com.orchpilot.workflow.task.HumanTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Minting, resolving, revoking and regenerating tokens — the security-critical states of a link. */
class ExternalFormTokenServiceTest {

    private final ConcurrentHashMap<String, ExternalFormAccessToken> store = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final SecureTokenGenerator generator = new SecureTokenGenerator();
    private ExternalFormAccessTokenRepository tokens;
    private ExternalFormTokenService service;

    @BeforeEach
    void setUp() {
        tokens = mock(ExternalFormAccessTokenRepository.class);
        HumanTaskRepository taskRepository = mock(HumanTaskRepository.class);
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        AuditService audit = mock(AuditService.class);
        WorkflowEngineProperties properties = new WorkflowEngineProperties();

        when(tokens.save(any())).thenAnswer(call -> {
            ExternalFormAccessToken token = call.getArgument(0);
            if (token.getId() == null) {
                token.setId("tok-" + ids.incrementAndGet());
            }
            store.put(token.getId(), token);
            return token;
        });
        when(tokens.findByTokenHash(anyString())).thenAnswer(call -> store.values().stream()
                .filter(token -> call.getArgument(0).equals(token.getTokenHash())).findFirst());
        when(tokens.findByTaskIdAndStatus(anyString(), any())).thenAnswer(call -> store.values().stream()
                .filter(token -> call.getArgument(0).equals(token.getTaskId()))
                .filter(token -> token.getStatus() == call.getArgument(1))
                .toList());
        when(tokens.findByTaskIdOrderByCreatedAtDesc(anyString())).thenAnswer(call ->
                new ArrayList<>(store.values().stream()
                        .filter(token -> call.getArgument(0).equals(token.getTaskId())).toList()));

        HumanTask task = new HumanTask();
        task.setId("task-1");
        task.setWorkflowExecutionId("inst-1");
        task.setWorkflowId("wf-1");
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(task));
        Workflow workflow = new Workflow();
        workflow.setId("wf-1");
        workflow.setTenantId("tenant-A");
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));

        service = new ExternalFormTokenService(generator, tokens, taskRepository, workflowRepository, audit,
                properties);
    }

    private ExternalFormTokenService.CreateOptions options() {
        return ExternalFormTokenService.CreateOptions.defaults(Duration.ofHours(24));
    }

    @Test
    @DisplayName("create stores the hash, never the raw token, and binds task/instance/tenant")
    void createStoresHashNotRawToken() {
        ExternalFormTokenService.GeneratedLink link = service.create("task-1", options(), "admin");

        assertThat(link.rawToken()).isNotBlank();
        ExternalFormAccessToken token = link.token();
        assertThat(token.getTokenHash()).isEqualTo(generator.hash(link.rawToken()));
        assertThat(token.getTokenHash()).isNotEqualTo(link.rawToken());
        assertThat(token.getTaskId()).isEqualTo("task-1");
        assertThat(token.getWorkflowInstanceId()).isEqualTo("inst-1");
        assertThat(token.getTenantId()).isEqualTo("tenant-A");
        assertThat(token.getStatus()).isEqualTo(ExternalFormTokenStatus.ACTIVE);
        // Nothing in the store equals the raw token.
        assertThat(store.values()).noneMatch(t -> link.rawToken().equals(t.getTokenHash()));
    }

    @Test
    @DisplayName("a valid token resolves; an unknown one is INVALID")
    void resolvesValidRejectsUnknown() {
        ExternalFormTokenService.GeneratedLink link = service.create("task-1", options(), "admin");
        assertThat(service.resolve(link.rawToken()).getId()).isEqualTo(link.token().getId());

        assertThatThrownBy(() -> service.resolve("not-a-real-token"))
                .isInstanceOf(ExternalFormException.class)
                .extracting(ex -> ((ExternalFormException) ex).state())
                .isEqualTo(ExternalFormState.INVALID);
    }

    @Test
    @DisplayName("an expired token is refused and flipped to EXPIRED")
    void expiredIsRefused() {
        ExternalFormTokenService.GeneratedLink link = service.create("task-1", options(), "admin");
        link.token().setExpiresAt(Instant.now().minusSeconds(10));

        assertThatThrownBy(() -> service.resolve(link.rawToken()))
                .isInstanceOf(ExternalFormException.class)
                .extracting(ex -> ((ExternalFormException) ex).state())
                .isEqualTo(ExternalFormState.EXPIRED);
        assertThat(link.token().getStatus()).isEqualTo(ExternalFormTokenStatus.EXPIRED);
    }

    @Test
    @DisplayName("a revoked token is refused")
    void revokedIsRefused() {
        ExternalFormTokenService.GeneratedLink link = service.create("task-1", options(), "admin");
        service.revoke("task-1", "admin");

        assertThat(link.token().getStatus()).isEqualTo(ExternalFormTokenStatus.REVOKED);
        assertThatThrownBy(() -> service.resolve(link.rawToken()))
                .isInstanceOf(ExternalFormException.class)
                .extracting(ex -> ((ExternalFormException) ex).state())
                .isEqualTo(ExternalFormState.REVOKED);
    }

    @Test
    @DisplayName("a used token reports already submitted")
    void usedReportsAlreadySubmitted() {
        ExternalFormTokenService.GeneratedLink link = service.create("task-1", options(), "admin");
        service.recordSubmission(link.token()); // single-use → USED

        assertThat(link.token().getStatus()).isEqualTo(ExternalFormTokenStatus.USED);
        assertThatThrownBy(() -> service.resolve(link.rawToken()))
                .isInstanceOf(ExternalFormException.class)
                .extracting(ex -> ((ExternalFormException) ex).state())
                .isEqualTo(ExternalFormState.ALREADY_SUBMITTED);
    }

    @Test
    @DisplayName("regenerate revokes the old link and the old token stops working")
    void regenerateRevokesOld() {
        ExternalFormTokenService.GeneratedLink first = service.create("task-1", options(), "admin");
        ExternalFormTokenService.GeneratedLink second = service.regenerate("task-1", options(), "admin");

        assertThat(second.rawToken()).isNotEqualTo(first.rawToken());
        assertThat(first.token().getStatus()).isEqualTo(ExternalFormTokenStatus.REVOKED);
        assertThat(service.resolve(second.rawToken()).getId()).isEqualTo(second.token().getId());
        assertThatThrownBy(() -> service.resolve(first.rawToken()))
                .isInstanceOf(ExternalFormException.class);
    }

    @Test
    @DisplayName("a multi-use token stays active until its allowance is spent")
    void multiUseStaysActiveUntilSpent() {
        ExternalFormTokenService.GeneratedLink link = service.create("task-1",
                new ExternalFormTokenService.CreateOptions(Duration.ofHours(24), 2, true, true, null, null,
                        null), "admin");

        service.recordSubmission(link.token());
        assertThat(link.token().getStatus()).isEqualTo(ExternalFormTokenStatus.ACTIVE);
        service.recordSubmission(link.token());
        assertThat(link.token().getStatus()).isEqualTo(ExternalFormTokenStatus.USED);
    }
}
