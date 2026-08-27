package com.orchpilot.workflow.task;

/**
 * Tells people about their tasks.
 *
 * <p>An interface with one logging implementation, and that is the honest state of it: this platform has no mail
 * transport configured and inventing one here would be a second, unrequested feature. What matters is that the
 * call sites exist and are correct, so wiring a real channel is one bean.
 *
 * <p>The obvious real implementation is the SendGrid plugin the platform already loads. Note the shape that
 * makes possible: a notification becomes a workflow, and the engine gains an outbound channel per plugin rather
 * than per hard-coded integration.
 */
public interface TaskNotifier {

    /**
     * A task now needs somebody's attention: newly created, reassigned, or released back to a pool.
     *
     * @param task the task, already persisted
     * @param why  short reason, for the message subject
     */
    void notifyAssigned(HumanTask task, String why);

    /**
     * A task has passed its advisory deadline.
     *
     * @param task the overdue task
     */
    void notifyOverdue(HumanTask task);

    /**
     * A task expired without being submitted, so the execution failed.
     *
     * @param task the expired task
     */
    void notifyExpired(HumanTask task);
}
