package com.orchpilot.workflow.model;

import com.orchpilot.workflow.task.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status semantics the whole lifecycle rests on: what is terminal, what may be re-entered, and where a
 * draft is still allowed. These are the invariants the engine's node-boundary check and the form guards read.
 */
class InstanceStatusSemanticsTest {

    @Test
    @DisplayName("TERMINATED is terminal and can never be re-entered")
    void terminatedIsFinal() {
        assertThat(ExecutionStatus.TERMINATED.isTerminal()).isTrue();
        assertThat(ExecutionStatus.TERMINATED.isResumable()).isFalse();
    }

    @Test
    @DisplayName("PAUSED is not terminal and can be re-entered")
    void pausedIsResumable() {
        assertThat(ExecutionStatus.PAUSED.isTerminal()).isFalse();
        assertThat(ExecutionStatus.PAUSED.isResumable()).isTrue();
    }

    @Test
    @DisplayName("CANCELLED and TERMINATED are distinct terminal states")
    void cancelledAndTerminatedAreDistinct() {
        assertThat(ExecutionStatus.CANCELLED).isNotEqualTo(ExecutionStatus.TERMINATED);
        assertThat(ExecutionStatus.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("a paused task is held: not actionable, but a draft is still allowed")
    void pausedTaskAllowsDraftNotSubmit() {
        assertThat(TaskStatus.PAUSED.isActionable()).isFalse();
        assertThat(TaskStatus.PAUSED.isTerminal()).isFalse();
        assertThat(TaskStatus.PAUSED.allowsDraft()).isTrue();
    }

    @Test
    @DisplayName("a terminated task is final: not actionable, but a draft is still allowed")
    void terminatedTaskAllowsDraftNotSubmit() {
        assertThat(TaskStatus.TERMINATED.isActionable()).isFalse();
        assertThat(TaskStatus.TERMINATED.isTerminal()).isTrue();
        assertThat(TaskStatus.TERMINATED.allowsDraft()).isTrue();
    }

    @Test
    @DisplayName("a completed task allows neither a submit nor a draft")
    void completedTaskIsClosed() {
        assertThat(TaskStatus.COMPLETED.isActionable()).isFalse();
        assertThat(TaskStatus.COMPLETED.allowsDraft()).isFalse();
    }
}
