package com.orchpilot.workflow.exception;

/**
 * A form action was refused because the owning workflow instance is not in a state that allows it.
 *
 * <p>The two cases the specification names — a paused instance and a terminated one — carry different error
 * codes so a client can tell them apart and show the right message, and both answer <strong>409 Conflict</strong>:
 * the request was well formed and authorized, but the instance's current state forbids it. Enforced on the
 * server, not merely by a disabled button, so an out-of-date or hostile client cannot submit anyway.
 */
public class WorkflowInstanceStateException extends WorkflowEngineException {

    private WorkflowInstanceStateException(String errorCode, String message) {
        super(errorCode, message);
    }

    /** The instance is paused; submitting is refused until it is resumed. */
    public static WorkflowInstanceStateException paused() {
        return new WorkflowInstanceStateException("WORKFLOW_INSTANCE_PAUSED",
                "Form cannot be submitted because the workflow instance is currently paused.");
    }

    /** The instance is terminated; submitting is refused permanently. */
    public static WorkflowInstanceStateException terminated() {
        return new WorkflowInstanceStateException("WORKFLOW_INSTANCE_TERMINATED",
                "Form cannot be submitted because the workflow instance has been terminated.");
    }
}
