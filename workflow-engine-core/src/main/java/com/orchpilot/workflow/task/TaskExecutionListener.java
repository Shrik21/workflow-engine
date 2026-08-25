package com.orchpilot.workflow.task;

import com.orchpilot.workflow.event.ExecutionLifecycleEvent;
import com.orchpilot.workflow.model.ExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Closes outstanding tasks when their execution ends some other way.
 *
 * <p>An execution can reach a terminal state without its task being submitted: somebody cancels the run, or a
 * parallel branch fails it. Left alone, the approval stays in an inbox forever, and the person who eventually
 * opens it gets a confusing refusal from a workflow that died last Tuesday.
 *
 * <h2>Why a listener rather than a call from the execution service</h2>
 *
 * <p>Because the direct call is a cycle. {@code DefaultExecutionService.cancel} would have to invoke
 * {@link TaskCompletionService}, which needs {@code ExecutionService} to cancel an execution when a task is
 * withdrawn. Listening to the lifecycle event the engine already publishes leaves the dependency one-way.
 *
 * <p>The reverse direction is safe: withdrawing a task cancels the execution, which publishes CANCELLED, which
 * arrives back here and finds the task already closed. One extra no-op rather than a loop.
 */
@Component
public class TaskExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionListener.class);

    private final TaskCompletionService completion;

    public TaskExecutionListener(TaskCompletionService completion) {
        this.completion = completion;
    }

    /**
     * @param event the execution transition
     */
    @EventListener
    public void onExecutionEvent(ExecutionLifecycleEvent event) {
        if (event == null || event.status() == null || !event.status().isTerminal()) {
            return;
        }
        // TERMINATED is handled by the instance-lifecycle service, which terminates the tasks (recording the
        // reason and the actor) before it publishes this event. Cancelling them here would be both wrong — the
        // right terminal task status is TERMINATED, not CANCELLED — and, once the lifecycle service has run, a
        // no-op. So this listener stays out of the terminate path entirely.
        if (event.status() == ExecutionStatus.TERMINATED) {
            return;
        }
        // COMPLETED is included deliberately. A workflow can finish while a task on an abandoned branch is
        // still open, and that task is no more actionable than one on a cancelled run.
        try {
            completion.cancelTasksFor(event.executionId(), reasonFor(event.status()));
        } catch (RuntimeException ex) {
            // A listener must never fail the transition that triggered it.
            log.error("Could not close the tasks on execution {} after it reached {}: {}",
                    event.executionId(), event.status(), ex.getMessage());
        }
    }

    private static String reasonFor(ExecutionStatus status) {
        return switch (status) {
            case CANCELLED -> "The execution was cancelled";
            case FAILED -> "The execution failed";
            case COMPLETED -> "The execution finished without this task";
            default -> "The execution ended";
        };
    }
}
