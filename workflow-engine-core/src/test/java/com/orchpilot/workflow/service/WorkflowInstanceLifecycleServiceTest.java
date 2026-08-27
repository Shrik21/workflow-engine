package com.orchpilot.workflow.service;

import com.orchpilot.workflow.event.WorkflowEventPublisher;
import com.orchpilot.workflow.exception.InvalidWorkflowStateException;
import com.orchpilot.workflow.execution.ExecutionStateStore;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.task.TaskLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The instance lifecycle rules: which transitions are legal, which are idempotent, which are refused, and that
 * every one cascades to the instance's tasks. Drives the spec's state-machine test cases (1–6, 19–25) directly.
 */
class WorkflowInstanceLifecycleServiceTest {

    private ExecutionStateStore stateStore;
    private ExecutionService executions;
    private TaskLifecycleService taskLifecycle;
    private AuditService audit;
    private WorkflowEventPublisher eventPublisher;
    private WorkflowInstanceLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        stateStore = mock(ExecutionStateStore.class);
        executions = mock(ExecutionService.class);
        taskLifecycle = mock(TaskLifecycleService.class);
        audit = mock(AuditService.class);
        eventPublisher = mock(WorkflowEventPublisher.class);
        lifecycle = new WorkflowInstanceLifecycleService(stateStore, executions, taskLifecycle, audit,
                eventPublisher);
    }

    private static WorkflowExecution instance(ExecutionStatus status) {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId("inst-1");
        execution.setWorkflowId("wf-1");
        execution.setWorkflowVersion(2);
        execution.setCurrentNodeId("node-a");
        execution.setStatus(status);
        return execution;
    }

    @Nested
    @DisplayName("Pause")
    class Pause {

        @Test
        @DisplayName("RUNNING → PAUSED cascades to tasks and records history")
        void runningToPaused() {
            when(stateStore.require("inst-1"))
                    .thenReturn(instance(ExecutionStatus.RUNNING), instance(ExecutionStatus.PAUSED));
            when(stateStore.transitionStatus(eq("inst-1"), any(), eq(ExecutionStatus.PAUSED), any()))
                    .thenReturn(true);
            when(taskLifecycle.pauseTasksFor("inst-1", "admin")).thenReturn(2);

            WorkflowExecution result = lifecycle.pause("inst-1", "waiting for customer", "admin");

            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.PAUSED);
            ArgumentCaptor<Set<ExecutionStatus>> from = ArgumentCaptor.forClass(Set.class);
            verify(stateStore).transitionStatus(eq("inst-1"), from.capture(), eq(ExecutionStatus.PAUSED), any());
            assertThat(from.getValue()).containsExactly(ExecutionStatus.RUNNING);
            verify(taskLifecycle).pauseTasksFor("inst-1", "admin");
            verify(audit).record(eq("admin"), eq("INSTANCE_PAUSED"), eq("WORKFLOW_INSTANCE"), eq("inst-1"),
                    eq("OK"), any());
        }

        @Test
        @DisplayName("pausing an already-paused instance is idempotent and touches nothing")
        void doublePauseIsIdempotent() {
            when(stateStore.require("inst-1")).thenReturn(instance(ExecutionStatus.PAUSED));

            WorkflowExecution result = lifecycle.pause("inst-1", null, "admin");

            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.PAUSED);
            verify(stateStore, never()).transitionStatus(anyString(), any(), any(), any());
            verify(taskLifecycle, never()).pauseTasksFor(anyString(), anyString());
        }

        @Test
        @DisplayName("a completed instance cannot be paused")
        void completedCannotBePaused() {
            when(stateStore.require("inst-1")).thenReturn(instance(ExecutionStatus.COMPLETED));

            assertThatThrownBy(() -> lifecycle.pause("inst-1", null, "admin"))
                    .isInstanceOf(InvalidWorkflowStateException.class);
            verify(taskLifecycle, never()).pauseTasksFor(anyString(), anyString());
        }

        @Test
        @DisplayName("a failed instance cannot be paused")
        void failedCannotBePaused() {
            when(stateStore.require("inst-1")).thenReturn(instance(ExecutionStatus.FAILED));
            assertThatThrownBy(() -> lifecycle.pause("inst-1", null, "admin"))
                    .isInstanceOf(InvalidWorkflowStateException.class);
        }
    }

    @Nested
    @DisplayName("Resume")
    class Resume {

        @Test
        @DisplayName("PAUSED → RUNNING re-enters the engine when it was mid node-loop")
        void resumeToRunning() {
            WorkflowExecution paused = instance(ExecutionStatus.PAUSED);
            paused.setStatusBeforePause(ExecutionStatus.RUNNING);
            when(stateStore.require("inst-1")).thenReturn(paused);
            when(taskLifecycle.resumeTasksFor("inst-1", "admin")).thenReturn(1);
            when(executions.resume("inst-1", true, "admin")).thenReturn(instance(ExecutionStatus.RUNNING));

            WorkflowExecution result = lifecycle.resume("inst-1", "admin");

            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
            verify(taskLifecycle).resumeTasksFor("inst-1", "admin");
            verify(executions).resume("inst-1", true, "admin");
        }

        @Test
        @DisplayName("PAUSED → WAITING restores a form-parked instance without re-entering the engine")
        void resumeToWaiting() {
            WorkflowExecution paused = instance(ExecutionStatus.PAUSED);
            paused.setStatusBeforePause(ExecutionStatus.WAITING);
            when(stateStore.require("inst-1"))
                    .thenReturn(paused, instance(ExecutionStatus.WAITING));
            when(stateStore.transitionStatus(eq("inst-1"), eq(Set.of(ExecutionStatus.PAUSED)),
                    eq(ExecutionStatus.WAITING), any())).thenReturn(true);
            when(taskLifecycle.resumeTasksFor("inst-1", "admin")).thenReturn(1);

            WorkflowExecution result = lifecycle.resume("inst-1", "admin");

            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.WAITING);
            verify(executions, never()).resume(anyString(), anyBoolean(), anyString());
            verify(taskLifecycle).resumeTasksFor("inst-1", "admin");
        }



        @Test
        @DisplayName("resume is refused from any status other than PAUSED")
        void resumeOnlyFromPaused() {
            when(stateStore.require("inst-1")).thenReturn(instance(ExecutionStatus.RUNNING));
            assertThatThrownBy(() -> lifecycle.resume("inst-1", "admin"))
                    .isInstanceOf(InvalidWorkflowStateException.class);
        }

        @Test
        @DisplayName("a terminated instance can never be resumed")
        void terminatedCannotBeResumed() {
            when(stateStore.require("inst-1")).thenReturn(instance(ExecutionStatus.TERMINATED));
            assertThatThrownBy(() -> lifecycle.resume("inst-1", "admin"))
                    .isInstanceOf(InvalidWorkflowStateException.class);
            verify(taskLifecycle, never()).resumeTasksFor(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Terminate")
    class Terminate {

        @Test
        @DisplayName("RUNNING → TERMINATED ends tasks and announces the stop")
        void terminateFromRunning() {
            when(stateStore.require("inst-1"))
                    .thenReturn(instance(ExecutionStatus.RUNNING), instance(ExecutionStatus.TERMINATED));
            when(stateStore.transitionStatus(eq("inst-1"), any(), eq(ExecutionStatus.TERMINATED), any()))
                    .thenReturn(true);
            when(taskLifecycle.terminateTasksFor("inst-1", "admin", "fraud")).thenReturn(3);

            WorkflowExecution result = lifecycle.terminate("inst-1", "fraud", "admin");

            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.TERMINATED);
            verify(taskLifecycle).terminateTasksFor("inst-1", "admin", "fraud");
            verify(eventPublisher).publishExecutionEvent(any());
            verify(audit).record(eq("admin"), eq("INSTANCE_TERMINATED"), eq("WORKFLOW_INSTANCE"),
                    eq("inst-1"), eq("OK"), any());
        }

        @Test
        @DisplayName("PAUSED → TERMINATED is legal")
        void terminateFromPaused() {
            when(stateStore.require("inst-1"))
                    .thenReturn(instance(ExecutionStatus.PAUSED), instance(ExecutionStatus.TERMINATED));
            when(stateStore.transitionStatus(eq("inst-1"), any(), eq(ExecutionStatus.TERMINATED), any()))
                    .thenReturn(true);

            WorkflowExecution result = lifecycle.terminate("inst-1", null, "admin");

            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.TERMINATED);
            ArgumentCaptor<Set<ExecutionStatus>> from = ArgumentCaptor.forClass(Set.class);
            verify(stateStore).transitionStatus(eq("inst-1"), from.capture(),
                    eq(ExecutionStatus.TERMINATED), any());
            assertThat(from.getValue()).contains(ExecutionStatus.RUNNING, ExecutionStatus.PAUSED,
                    ExecutionStatus.WAITING, ExecutionStatus.PENDING);
        }

        @Test
        @DisplayName("terminating an already-terminated instance is idempotent")
        void doubleTerminateIsIdempotent() {
            when(stateStore.require("inst-1")).thenReturn(instance(ExecutionStatus.TERMINATED));

            WorkflowExecution result = lifecycle.terminate("inst-1", null, "admin");

            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.TERMINATED);
            verify(stateStore, never()).transitionStatus(anyString(), any(), any(), any());
            verify(taskLifecycle, never()).terminateTasksFor(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a completed instance cannot be terminated")
        void completedCannotBeTerminated() {
            when(stateStore.require("inst-1")).thenReturn(instance(ExecutionStatus.COMPLETED));
            assertThatThrownBy(() -> lifecycle.terminate("inst-1", null, "admin"))
                    .isInstanceOf(InvalidWorkflowStateException.class);
            verify(taskLifecycle, never()).terminateTasksFor(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a lost race — the instance completed first — is reported, not forced")
        void lostRaceIsReported() {
            // require reads RUNNING, but the conditional write matches nothing because it completed underneath,
            // and the re-read shows COMPLETED.
            when(stateStore.require("inst-1"))
                    .thenReturn(instance(ExecutionStatus.RUNNING), instance(ExecutionStatus.COMPLETED));
            when(stateStore.transitionStatus(eq("inst-1"), any(), eq(ExecutionStatus.TERMINATED), any()))
                    .thenReturn(false);

            assertThatThrownBy(() -> lifecycle.terminate("inst-1", null, "admin"))
                    .isInstanceOf(InvalidWorkflowStateException.class);
            verify(taskLifecycle, never()).terminateTasksFor(anyString(), anyString(), any());
        }
    }
}
