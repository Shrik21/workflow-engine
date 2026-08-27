package com.orchpilot.workflow.service;

import com.orchpilot.workflow.event.ExecutionLifecycleEvent;
import com.orchpilot.workflow.model.ExecutionStatus;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Records an instance's natural terminal outcomes in its lifecycle history.
 *
 * <p>The pause, resume and terminate events are written where they happen, by the lifecycle service. The two an
 * administrator does not initiate — an instance completing on its own, or failing — are published by the engine
 * and captured here, so the history endpoint shows the whole arc rather than only the manual steps.
 *
 * <p>{@code TERMINATED} is deliberately not handled here: the lifecycle service already records
 * {@code INSTANCE_TERMINATED} with its actor and reason before it publishes the event, and recording it again
 * would double the entry. {@code CANCELLED} is the engine's own housekeeping and is audited on the execution,
 * not as an instance-lifecycle event.
 */
@Component
public class InstanceHistoryListener {

    private final AuditService audit;

    public InstanceHistoryListener(AuditService audit) {
        this.audit = audit;
    }

    @EventListener
    public void onExecutionEvent(ExecutionLifecycleEvent event) {
        if (event == null || event.status() == null) {
            return;
        }
        String action = switch (event.status()) {
            case COMPLETED -> "INSTANCE_COMPLETED";
            case FAILED -> "INSTANCE_FAILED";
            default -> null;
        };
        if (action == null) {
            return;
        }
        audit.record("system", action, "WORKFLOW_INSTANCE", event.executionId(), "OK",
                Map.of("workflowTemplateId", String.valueOf(event.workflowId()),
                        "newStatus", event.status().name()));
    }
}
