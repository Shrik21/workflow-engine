package com.orchpilot.workflow.task;

import com.orchpilot.workflow.auth.service.OperationNotAllowedException;
import com.orchpilot.workflow.forms.FormNodeBinding;
import com.orchpilot.workflow.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The external completion and draft primitives: token-authorised, principal-free, but converging on exactly the
 * same task write and engine resume the internal path uses. Covers "submit valid", "submit twice" and
 * "save draft" for the public channel.
 */
class ExternalFormCompletionTest {

    private final ConcurrentHashMap<String, HumanTask> store = new ConcurrentHashMap<>();
    private HumanTaskRepository repository;
    private HumanTaskService service;
    private TaskCompletionService completion;
    private com.orchpilot.workflow.service.ExecutionService executions;

    @BeforeEach
    void setUp() {
        repository = mock(HumanTaskRepository.class);
        TaskHistoryRepository historyRepository = mock(TaskHistoryRepository.class);
        com.orchpilot.workflow.access.GroupMembershipRepository memberships =
                mock(com.orchpilot.workflow.access.GroupMembershipRepository.class);
        com.orchpilot.workflow.auth.repository.UserRepository users =
                mock(com.orchpilot.workflow.auth.repository.UserRepository.class);
        executions = mock(com.orchpilot.workflow.service.ExecutionService.class);
        FormNodeBinding binding = mock(FormNodeBinding.class);
        AuditService audit = mock(AuditService.class);

        when(repository.findById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(store.get(call.getArgument(0, String.class))));
        when(repository.save(any())).thenAnswer(call -> {
            HumanTask task = call.getArgument(0);
            store.put(task.getId(), task);
            return task;
        });
        // The task references no managed form (formDefinitionId is null), so the binding is never consulted.

        TaskAuthorizationService authorization = new TaskAuthorizationService(memberships);
        service = new HumanTaskService(repository, historyRepository, authorization, users,
                new LoggingTaskNotifier(), mock(AuditService.class), mock(ApplicationEventPublisher.class));
        completion = new TaskCompletionService(service, repository, authorization, binding, executions,
                new LoggingTaskNotifier(), audit);
    }

    private HumanTask externalTask() {
        HumanTask task = new HumanTask();
        task.setId("task-1");
        task.setWorkflowExecutionId("inst-1");
        task.setNodeId("form-node");
        task.setExternal(true);
        task.setStatus(TaskStatus.ASSIGNED);
        task.setFormDefinitionId(null);
        store.put(task.getId(), task);
        return task;
    }

    @Test
    @DisplayName("external submit completes the task and resumes the workflow through submitSignal")
    void submitCompletesAndResumes() {
        externalTask();

        HumanTask completed = completion.completeExternally("task-1", Map.of("name", "John"), "external");

        assertThat(completed.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(completed.getCompletedByUsername()).isEqualTo("external");
        verify(executions).submitSignal(eq("inst-1"), any(), eq("external"));
    }

    @Test
    @DisplayName("a second external submit is refused because the task is no longer actionable")
    void secondSubmitIsRefused() {
        externalTask();
        completion.completeExternally("task-1", Map.of("name", "John"), "external");

        assertThatThrownBy(() -> completion.completeExternally("task-1", Map.of("name", "Jane"), "external"))
                .isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    @DisplayName("external draft saves partial input and advances nothing")
    void draftSavesWithoutResuming() {
        externalTask();

        HumanTask saved = service.saveDraftExternally("task-1", Map.of("name", "John"), "external");

        assertThat(saved.getDraftData()).containsEntry("name", "John");
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.ASSIGNED);
        // No engine resume from a draft.
        org.mockito.Mockito.verifyNoInteractions(executions);
    }

    @Test
    @DisplayName("external draft still saves while the task is held by a paused instance")
    void draftSavesWhilePaused() {
        HumanTask task = externalTask();
        task.setStatus(TaskStatus.PAUSED);

        HumanTask saved = service.saveDraftExternally("task-1", Map.of("email", "j@example.com"), "external");

        assertThat(saved.getDraftData()).containsEntry("email", "j@example.com");
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.PAUSED);
    }
}
