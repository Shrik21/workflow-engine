package com.orchpilot.workflow.task;

import com.orchpilot.workflow.access.GroupMembershipRepository;
import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.UserRepository;
import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.exception.WorkflowInstanceStateException;
import com.orchpilot.workflow.forms.FormNodeBinding;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Form actions gated on the instance's state, enforced on the server.
 *
 * <p>The heart of the spec's rules 10–13: a submit is refused with the right 409 error code when the instance
 * is paused or terminated, and permitted when it is live — while saving a draft stays available throughout, so
 * a person never loses their input. These are backend rules, not disabled-button rules; the tests reach the
 * service directly, as a stale or hostile client would.
 */
class InstanceFormActionsTest {

    private final ConcurrentHashMap<String, HumanTask> store = new ConcurrentHashMap<>();
    private HumanTaskRepository repository;
    private HumanTaskService service;
    private TaskCompletionService completion;
    private com.orchpilot.workflow.service.ExecutionService executions;
    private AuditService audit;
    private AuthPrincipal approver;

    @BeforeEach
    void setUp() {
        repository = mock(HumanTaskRepository.class);
        TaskHistoryRepository historyRepository = mock(TaskHistoryRepository.class);
        GroupMembershipRepository memberships = mock(GroupMembershipRepository.class);
        UserRepository users = mock(UserRepository.class);
        executions = mock(com.orchpilot.workflow.service.ExecutionService.class);
        FormNodeBinding binding = mock(FormNodeBinding.class);
        audit = mock(AuditService.class);

        when(repository.findById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(store.get(call.getArgument(0, String.class))));
        when(repository.save(any())).thenAnswer(call -> {
            HumanTask task = call.getArgument(0);
            store.put(task.getId(), task);
            return task;
        });
        when(memberships.findByUserId(anyString())).thenReturn(List.of());

        TaskAuthorizationService authorization = new TaskAuthorizationService(memberships);
        service = new HumanTaskService(repository, historyRepository, authorization, users,
                new LoggingTaskNotifier(), mock(AuditService.class), mock(ApplicationEventPublisher.class));
        completion = new TaskCompletionService(service, repository, authorization, binding, executions,
                new LoggingTaskNotifier(), audit);

        User user = new User();
        user.setId("user-approver");
        user.setUsername("approver");
        user.setEmail("approver@example.test");
        user.setRoles(java.util.Set.of(Role.USER));
        approver = AuthPrincipal.of(user);
    }

    private HumanTask assignedTask(String executionId) {
        HumanTask task = new HumanTask();
        task.setId("task-1");
        task.setWorkflowExecutionId(executionId);
        task.setNodeId("form-node");
        task.setStatus(TaskStatus.ASSIGNED);
        task.setAssigneeUserId("user-approver");
        task.setAssigneeUsername("approver");
        store.put(task.getId(), task);
        return task;
    }

    private void instanceIs(String executionId, ExecutionStatus status) {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(executionId);
        execution.setStatus(status);
        when(executions.get(executionId)).thenReturn(execution);
    }

    @Test
    @DisplayName("submit is refused with 409 WORKFLOW_INSTANCE_PAUSED while the instance is paused")
    void submitRefusedWhenPaused() {
        assignedTask("inst-1");
        instanceIs("inst-1", ExecutionStatus.PAUSED);

        assertThatThrownBy(() -> completion.complete("task-1", Map.of("approved", true), approver))
                .isInstanceOf(WorkflowInstanceStateException.class)
                .extracting(ex -> ((WorkflowInstanceStateException) ex).getErrorCode())
                .isEqualTo("WORKFLOW_INSTANCE_PAUSED");

        verify(audit).record(eq("approver"), eq("FORM_SUBMIT_REJECTED"), eq("TASK"), eq("task-1"),
                eq("DENIED"), any());
        // The task is not advanced.
        assertThat(store.get("task-1").getStatus()).isEqualTo(TaskStatus.ASSIGNED);
        verify(executions, never()).submitSignal(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("submit is refused with 409 WORKFLOW_INSTANCE_TERMINATED after the instance is terminated")
    void submitRefusedWhenTerminated() {
        assignedTask("inst-1");
        instanceIs("inst-1", ExecutionStatus.TERMINATED);

        assertThatThrownBy(() -> completion.complete("task-1", Map.of("approved", true), approver))
                .isInstanceOf(WorkflowInstanceStateException.class)
                .extracting(ex -> ((WorkflowInstanceStateException) ex).getErrorCode())
                .isEqualTo("WORKFLOW_INSTANCE_TERMINATED");

        verify(audit).record(eq("approver"), eq("FORM_SUBMIT_REJECTED"), eq("TASK"), eq("task-1"),
                eq("DENIED"), any());
        assertThat(store.get("task-1").getStatus()).isEqualTo(TaskStatus.ASSIGNED);
    }

    @Test
    @DisplayName("submit succeeds and resumes the workflow while the instance is live")
    void submitSucceedsWhenRunning() {
        assignedTask("inst-1");
        instanceIs("inst-1", ExecutionStatus.WAITING); // an instance parked on a form is WAITING, i.e. live

        HumanTask completed = completion.complete("task-1", Map.of("approved", true), approver);

        assertThat(completed.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(executions).submitSignal(eq("inst-1"), any(), eq("approver"));
    }

    @Test
    @DisplayName("save draft succeeds while the instance is paused")
    void saveDraftSucceedsWhenPaused() {
        HumanTask task = assignedTask("inst-1");
        task.setStatus(TaskStatus.PAUSED);
        task.setPreviousStatus(TaskStatus.ASSIGNED);

        HumanTask saved = service.saveDraft("task-1", Map.of("name", "John"), approver);

        assertThat(saved.getDraftData()).containsEntry("name", "John");
        // The task stays paused; saving a draft advances nothing.
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.PAUSED);
    }

    @Test
    @DisplayName("save draft succeeds even after the instance is terminated")
    void saveDraftSucceedsWhenTerminated() {
        HumanTask task = assignedTask("inst-1");
        task.setStatus(TaskStatus.TERMINATED);

        HumanTask saved = service.saveDraft("task-1", Map.of("email", "john@example.com"), approver);

        assertThat(saved.getDraftData()).containsEntry("email", "john@example.com");
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.TERMINATED);
    }
}
