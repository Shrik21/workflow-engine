package com.orchpilot.workflow.externalform;

import com.orchpilot.workflow.execution.ExecutionStateStore;
import com.orchpilot.workflow.forms.FormNodeBinding;
import com.orchpilot.workflow.forms.FormVersion;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.task.HumanTask;
import com.orchpilot.workflow.task.HumanTaskRepository;
import com.orchpilot.workflow.task.HumanTaskService;
import com.orchpilot.workflow.task.TaskCompletionService;
import com.orchpilot.workflow.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * The public form flow: what a link shows, and the instance-state gate on submitting it — enforced on the
 * server, resolved entirely from the token.
 */
class ExternalFormServiceTest {

    private ExternalFormTokenService tokenService;
    private HumanTaskRepository taskRepository;
    private ExecutionStateStore executionStore;
    private FormNodeBinding binding;
    private TaskCompletionService completion;
    private HumanTaskService taskService;
    private ExternalFormService service;

    @BeforeEach
    void setUp() {
        tokenService = mock(ExternalFormTokenService.class);
        taskRepository = mock(HumanTaskRepository.class);
        executionStore = mock(ExecutionStateStore.class);
        binding = mock(FormNodeBinding.class);
        completion = mock(TaskCompletionService.class);
        taskService = mock(HumanTaskService.class);
        AuditService audit = mock(AuditService.class);
        service = new ExternalFormService(tokenService, taskRepository, executionStore, binding, completion,
                taskService, audit);
    }

    private ExternalFormAccessToken activeToken() {
        ExternalFormAccessToken token = new ExternalFormAccessToken();
        token.setId("tok-1");
        token.setTaskId("task-1");
        token.setWorkflowInstanceId("inst-1");
        token.setTenantId("tenant-A");
        token.setStatus(ExternalFormTokenStatus.ACTIVE);
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        token.setAllowSubmit(true);
        token.setAllowDraft(true);
        when(tokenService.resolve("raw")).thenReturn(token);
        return token;
    }

    private HumanTask externalTask(TaskStatus status) {
        HumanTask task = new HumanTask();
        task.setId("task-1");
        task.setWorkflowExecutionId("inst-1");
        task.setExternal(true);
        task.setStatus(status);
        task.setFormDefinitionId("form-1");
        task.setFormVersion(1);
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(task));
        return task;
    }

    private void instance(ExecutionStatus status) {
        when(executionStore.currentStatus("inst-1")).thenReturn(Optional.ofNullable(status));
    }

    private void formResolves() {
        FormVersion form = mock(FormVersion.class);
        when(form.getFields()).thenReturn(List.of());
        when(form.getTitle()).thenReturn("Customer Information");
        when(binding.resolve(eq("form-1"), any())).thenReturn(Optional.of(form));
    }

    @Test
    @DisplayName("open on a running instance is fillable and submittable")
    void openWhenRunning() {
        activeToken();
        externalTask(TaskStatus.ASSIGNED);
        instance(ExecutionStatus.RUNNING);
        formResolves();

        PublicFormView view = service.open("raw", "1.2.3.4", "agent");

        assertThat(view.state()).isEqualTo(ExternalFormState.OPEN);
        assertThat(view.formTitle()).isEqualTo("Customer Information");
        assertThat(view.allowSubmit()).isTrue();
        assertThat(view.allowDraft()).isTrue();
    }

    @Test
    @DisplayName("open on a paused instance allows drafting but not submitting")
    void openWhenPaused() {
        activeToken();
        externalTask(TaskStatus.PAUSED);
        instance(ExecutionStatus.PAUSED);
        formResolves();

        PublicFormView view = service.open("raw", null, null);

        assertThat(view.state()).isEqualTo(ExternalFormState.WORKFLOW_PAUSED);
        assertThat(view.allowSubmit()).isFalse();
        assertThat(view.allowDraft()).isTrue();
    }

    @Test
    @DisplayName("open on a terminated instance allows drafting but not submitting")
    void openWhenTerminated() {
        activeToken();
        externalTask(TaskStatus.TERMINATED);
        instance(ExecutionStatus.TERMINATED);
        formResolves();

        PublicFormView view = service.open("raw", null, null);

        assertThat(view.state()).isEqualTo(ExternalFormState.WORKFLOW_TERMINATED);
        assertThat(view.allowSubmit()).isFalse();
        assertThat(view.allowDraft()).isTrue();
    }

    @Test
    @DisplayName("open on a completed task shows already submitted, with no form")
    void openWhenAlreadySubmitted() {
        activeToken();
        externalTask(TaskStatus.COMPLETED);
        instance(ExecutionStatus.WAITING);

        PublicFormView view = service.open("raw", null, null);

        assertThat(view.state()).isEqualTo(ExternalFormState.ALREADY_SUBMITTED);
        assertThat(view.fields()).isEmpty();
    }

    @Test
    @DisplayName("submit on a running instance completes the task and records the submission")
    void submitWhenRunning() {
        ExternalFormAccessToken token = activeToken();
        externalTask(TaskStatus.ASSIGNED);
        instance(ExecutionStatus.RUNNING);
        when(completion.completeExternally(eq("task-1"), any(), anyString())).thenReturn(new HumanTask());

        ExternalFormService.SubmitResult result = service.submit("raw", Map.of("name", "John"), "1.2.3.4", "a");

        assertThat(result.referenceNumber()).startsWith("OP-");
        verify(completion).completeExternally(eq("task-1"), any(), eq("external"));
        verify(tokenService).recordSubmission(token);
    }

    @Test
    @DisplayName("submit on a paused instance is refused with the paused state and nothing is completed")
    void submitWhenPausedIsRefused() {
        activeToken();
        externalTask(TaskStatus.PAUSED);
        instance(ExecutionStatus.PAUSED);

        assertThatThrownBy(() -> service.submit("raw", Map.of(), null, null))
                .isInstanceOf(ExternalFormException.class)
                .extracting(ex -> ((ExternalFormException) ex).state())
                .isEqualTo(ExternalFormState.WORKFLOW_PAUSED);
        verify(completion, never()).completeExternally(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("submit on a terminated instance is refused")
    void submitWhenTerminatedIsRefused() {
        activeToken();
        externalTask(TaskStatus.TERMINATED);
        instance(ExecutionStatus.TERMINATED);

        assertThatThrownBy(() -> service.submit("raw", Map.of(), null, null))
                .isInstanceOf(ExternalFormException.class)
                .extracting(ex -> ((ExternalFormException) ex).state())
                .isEqualTo(ExternalFormState.WORKFLOW_TERMINATED);
        verify(tokenService, never()).recordSubmission(any());
    }

    @Test
    @DisplayName("submit on an already-completed task is refused as already submitted")
    void submitWhenAlreadySubmitted() {
        activeToken();
        externalTask(TaskStatus.COMPLETED);
        instance(ExecutionStatus.WAITING);

        assertThatThrownBy(() -> service.submit("raw", Map.of(), null, null))
                .isInstanceOf(ExternalFormException.class)
                .extracting(ex -> ((ExternalFormException) ex).state())
                .isEqualTo(ExternalFormState.ALREADY_SUBMITTED);
    }

    @Test
    @DisplayName("a draft saves while the instance is paused, and advances nothing")
    void draftSavesWhilePaused() {
        activeToken();
        externalTask(TaskStatus.PAUSED);
        when(taskService.saveDraftExternally(eq("task-1"), any(), anyString())).thenReturn(new HumanTask());

        service.saveDraft("raw", Map.of("name", "John"), null, null);

        verify(taskService).saveDraftExternally(eq("task-1"), any(), eq("external"));
        verify(completion, never()).completeExternally(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("everything is resolved from the token: the task read is the token's task, never a request value")
    void resolvesTaskFromTokenOnly() {
        activeToken();
        externalTask(TaskStatus.ASSIGNED);
        instance(ExecutionStatus.RUNNING);
        when(completion.completeExternally(anyString(), any(), anyString())).thenReturn(new HumanTask());

        service.submit("raw", Map.of(), null, null);

        // The only task ever loaded is the one the token names.
        verify(taskRepository).findById("task-1");
    }
}
