package com.orchpilot.workflow.service;

import com.orchpilot.workflow.config.EngineInstance;
import com.orchpilot.workflow.event.ExecutionLifecycleEvent;
import com.orchpilot.workflow.event.WorkflowEventPublisher;
import com.orchpilot.workflow.execution.ExecutionStateStore;
import com.orchpilot.workflow.execution.WorkflowExecutionEngine;
import com.orchpilot.workflow.forms.FormNodeBinding;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.repository.ExecutionLogRepository;
import com.orchpilot.workflow.repository.WorkflowExecutionRepository;
import com.orchpilot.workflow.task.TaskCompletionService;
import com.orchpilot.workflow.task.TaskExecutionListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An execution that ends outside the engine loop still has to say so.
 *
 * <p>Written because it did not, and nothing noticed until a human task was left in an inbox for a workflow that
 * had been cancelled an hour earlier. The engine publishes {@code CANCELLED} from inside its loop, which covers a
 * running execution; an execution parked on a form has no loop, so the transition was silent and every listener —
 * the task cleaner, and anything added later for metrics or notifications — simply never saw it.
 *
 * <p>The two tests are the two halves of that path: the service publishes, and the listener acts.
 */
class ExecutionTerminalEventTest {

    @Test
    @DisplayName("cancelling a parked execution publishes CANCELLED, so listeners learn about it")
    void cancellingAParkedExecutionPublishes() {
        WorkflowExecutionRepository executions = mock(WorkflowExecutionRepository.class);
        ExecutionStateStore stateStore = mock(ExecutionStateStore.class);
        WorkflowExecutionEngine engine = mock(WorkflowExecutionEngine.class);
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        EngineInstance instance = mock(EngineInstance.class);

        WorkflowExecution parked = new WorkflowExecution();
        parked.setId("exec-1");
        parked.setWorkflowId("wf-1");
        parked.setWorkflowVersion(3);
        parked.setCurrentNodeId("approve");
        // WAITING, not RUNNING: this is the case the engine's own publication does not cover.
        parked.setStatus(ExecutionStatus.WAITING);

        when(stateStore.require("exec-1")).thenReturn(parked);
        when(stateStore.save(any())).thenAnswer(call -> call.getArgument(0));
        when(engine.requestCancellation("exec-1")).thenReturn(false);

        DefaultExecutionService service = new DefaultExecutionService(mock(WorkflowService.class), engine,
                executions, mock(ExecutionLogRepository.class), stateStore,
                mock(ThreadPoolTaskExecutor.class), instance, mock(AuditService.class),
                mock(FormNodeBinding.class), publisher);

        service.cancel("exec-1", "admin");

        ArgumentCaptor<ExecutionLifecycleEvent> published =
                ArgumentCaptor.forClass(ExecutionLifecycleEvent.class);
        verify(publisher).publishExecutionEvent(published.capture());
        assertEquals(ExecutionStatus.CANCELLED, published.getValue().status());
        assertEquals("exec-1", published.getValue().executionId());
        assertEquals("wf-1", published.getValue().workflowId());
    }

    @Test
    @DisplayName("a terminal execution event closes the tasks it left behind, and a parked one does not")
    void listenerClosesTasksOnlyOnTerminalStatuses() {
        TaskCompletionService completion = mock(TaskCompletionService.class);
        TaskExecutionListener listener = new TaskExecutionListener(completion);

        listener.onExecutionEvent(ExecutionLifecycleEvent.of("exec-1", "wf-1", 3,
                ExecutionStatus.WAITING, "approve"));
        verify(completion, never()).cancelTasksFor(anyString(), anyString());

        listener.onExecutionEvent(ExecutionLifecycleEvent.of("exec-1", "wf-1", 3,
                ExecutionStatus.CANCELLED, "approve"));
        verify(completion).cancelTasksFor(eq("exec-1"), eq("The execution was cancelled"));
    }

    @Test
    @DisplayName("a listener that throws does not fail the transition that triggered it")
    void listenerSwallowsFailures() {
        TaskCompletionService completion = mock(TaskCompletionService.class);
        when(completion.cancelTasksFor(anyString(), anyString()))
                .thenThrow(new IllegalStateException("MongoDB is down"));

        // No exception escapes: an execution must still be able to reach a terminal state when the task
        // collection is unavailable.
        new TaskExecutionListener(completion).onExecutionEvent(ExecutionLifecycleEvent.of("exec-1", "wf-1", 3,
                ExecutionStatus.FAILED, "approve"));

        // It was attempted and it failed; what matters is that the caller was not told.
        verify(completion).cancelTasksFor(eq("exec-1"), eq("The execution failed"));
    }
}
