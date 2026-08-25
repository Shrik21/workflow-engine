package com.orchpilot.workflow.task;

import com.orchpilot.workflow.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The task cascade: pausing, resuming and terminating an instance's tasks in place, without ever touching a
 * finished task or a task on another instance. Covers the spec's rules 4 and 5 and cases 12 and 14.
 */
class TaskLifecycleServiceTest {

    private final List<HumanTask> store = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private HumanTaskRepository repository;
    private HumanTaskService tasks;
    private TaskLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        repository = mock(HumanTaskRepository.class);
        tasks = mock(HumanTaskService.class);
        AuditService audit = mock(AuditService.class);

        when(repository.findByWorkflowExecutionIdAndStatusIn(anyString(), any())).thenAnswer(call -> {
            String executionId = call.getArgument(0);
            Collection<TaskStatus> statuses = call.getArgument(1);
            return store.stream()
                    .filter(task -> executionId.equals(task.getWorkflowExecutionId()))
                    .filter(task -> statuses.contains(task.getStatus()))
                    .toList();
        });
        // saveWithHistory just persists the (already-mutated) task for these tests.
        when(tasks.saveWithHistory(any(), any(), anyString(), any(), any(), any()))
                .thenAnswer(call -> call.getArgument(0));

        lifecycle = new TaskLifecycleService(tasks, repository, audit);
    }

    private HumanTask task(String executionId, TaskStatus status) {
        HumanTask task = new HumanTask();
        task.setId("task-" + ids.incrementAndGet());
        task.setWorkflowExecutionId(executionId);
        task.setNodeId("node-a");
        task.setStatus(status);
        store.add(task);
        return task;
    }

    @Test
    @DisplayName("pause holds active tasks and remembers where each came from")
    void pauseRemembersPreviousStatus() {
        HumanTask open = task("inst-1", TaskStatus.OPEN);
        HumanTask assigned = task("inst-1", TaskStatus.ASSIGNED);
        HumanTask done = task("inst-1", TaskStatus.COMPLETED);

        int paused = lifecycle.pauseTasksFor("inst-1", "admin");

        assertThat(paused).isEqualTo(2);
        assertThat(open.getStatus()).isEqualTo(TaskStatus.PAUSED);
        assertThat(open.getPreviousStatus()).isEqualTo(TaskStatus.OPEN);
        assertThat(assigned.getStatus()).isEqualTo(TaskStatus.PAUSED);
        assertThat(assigned.getPreviousStatus()).isEqualTo(TaskStatus.ASSIGNED);
        // A completed task is never touched.
        assertThat(done.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("resume returns each paused task to exactly the status it held before")
    void resumeRestoresPreviousStatus() {
        HumanTask wasOpen = task("inst-1", TaskStatus.PAUSED);
        wasOpen.setPreviousStatus(TaskStatus.OPEN);
        HumanTask wasAssigned = task("inst-1", TaskStatus.PAUSED);
        wasAssigned.setPreviousStatus(TaskStatus.ASSIGNED);

        int restored = lifecycle.resumeTasksFor("inst-1", "admin");

        assertThat(restored).isEqualTo(2);
        assertThat(wasOpen.getStatus()).isEqualTo(TaskStatus.OPEN);
        assertThat(wasOpen.getPreviousStatus()).isNull();
        assertThat(wasAssigned.getStatus()).isEqualTo(TaskStatus.ASSIGNED);
    }

    @Test
    @DisplayName("terminate ends active and paused tasks, leaves completed ones, and records a reason")
    void terminateEndsLiveTasksOnly() {
        HumanTask assigned = task("inst-1", TaskStatus.ASSIGNED);
        HumanTask paused = task("inst-1", TaskStatus.PAUSED);
        HumanTask done = task("inst-1", TaskStatus.COMPLETED);

        int terminated = lifecycle.terminateTasksFor("inst-1", "admin", "fraud check failed");

        assertThat(terminated).isEqualTo(2);
        assertThat(assigned.getStatus()).isEqualTo(TaskStatus.TERMINATED);
        assertThat(assigned.getCancelReason()).isEqualTo("fraud check failed");
        assertThat(paused.getStatus()).isEqualTo(TaskStatus.TERMINATED);
        assertThat(done.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("terminating one instance never touches another instance's tasks")
    void terminateIsScopedToOneInstance() {
        HumanTask mine = task("inst-1", TaskStatus.ASSIGNED);
        HumanTask other = task("inst-2", TaskStatus.ASSIGNED);

        int terminated = lifecycle.terminateTasksFor("inst-1", "admin", "done with A");

        assertThat(terminated).isEqualTo(1);
        assertThat(mine.getStatus()).isEqualTo(TaskStatus.TERMINATED);
        assertThat(other.getStatus()).isEqualTo(TaskStatus.ASSIGNED);
    }
}
