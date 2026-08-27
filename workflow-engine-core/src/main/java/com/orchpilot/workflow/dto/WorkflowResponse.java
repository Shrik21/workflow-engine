package com.orchpilot.workflow.dto;

import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowConnection;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.model.WorkflowTrigger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A workflow definition as returned by the API.
 *
 * @param id               workflow id
 * @param name             display name
 * @param description      what it does
 * @param status           {@code DRAFT}, {@code PUBLISHED} or {@code ARCHIVED}
 * @param version          version the next publish will produce
 * @param publishedVersion version currently executable, or {@code null}
 * @param nodes            the graph's nodes
 * @param connections      the graph's edges
 * @param variables        declared workflow variables
 * @param triggers         ways it can be started
 * @param metadata         free-form labels
 * @param createdAt        creation time
 * @param updatedAt        last modification time
 * @param publishedAt      last publish time
 * @param createdBy        who created it
 * @param updatedBy        who last changed it
 */
public record WorkflowResponse(String id, String name, String description, String status, int version,
                               Integer publishedVersion, List<NodeView> nodes,
                               List<ConnectionView> connections, Map<String, Object> variables,
                               List<TriggerView> triggers, Map<String, Object> metadata, Instant createdAt,
                               Instant updatedAt, Instant publishedAt, String createdBy, String updatedBy) {

    /**
     * @param workflow persistence model
     * @return the API representation
     */
    public static WorkflowResponse from(Workflow workflow) {
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getName(),
                workflow.getDescription(),
                String.valueOf(workflow.getStatus()),
                workflow.getVersion(),
                workflow.getPublishedVersion(),
                workflow.getNodes().stream().map(NodeView::from).toList(),
                workflow.getConnections().stream().map(ConnectionView::from).toList(),
                workflow.getVariables(),
                workflow.getTriggers().stream().map(TriggerView::from).toList(),
                workflow.getMetadata(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt(),
                workflow.getPublishedAt(),
                workflow.getCreatedBy(),
                workflow.getUpdatedBy());
    }

    /**
     * A node as returned by the API.
     *
     * @param id            node id
     * @param type          node type
     * @param name          display name
     * @param description   what it does
     * @param pluginId      plugin providing the behaviour
     * @param pluginVersion pinned plugin version
     * @param configuration node configuration
     * @param inputMapping  input mappings
     * @param outputMapping output mappings
     * @param conditions    decision branches
     * @param defaultBranch fallback branch
     * @param formId        form identifier
     * @param formVersion   pinned published form version, or null to follow the newest
     * @param waitForInput  whether a form node parks rather than failing when no submission is present
     * @param outputs       result variable paths
     * @param errorPolicy   failure policy
     * @param timeoutMillis execution budget
     * @param presentation  designer coordinates
     */
    public record NodeView(String id, String type, String name, String description, String pluginId,
                           String pluginVersion, Map<String, Object> configuration,
                           Map<String, String> inputMapping, Map<String, String> outputMapping,
                           List<ConditionView> conditions, String defaultBranch, String formId,
                           Integer formVersion, boolean waitForInput,
                           List<String> outputs, String errorPolicy, Long timeoutMillis,
                           Map<String, Object> presentation) {

        /*
         * formVersion and waitForInput are returned as well as accepted.
         *
         * They were previously write-only: the request carried them, the engine honoured them, and the
         * response omitted them. Unchecking "wait for input" therefore saved correctly and came back checked
         * on the next load, and a pinned form version was invisible to the designer that pinned it. A field
         * the API accepts but will not tell you about is a field the user cannot trust.
         */
        static NodeView from(WorkflowNode node) {
            return new NodeView(node.getId(), node.getType(), node.getName(), node.getDescription(),
                    node.getPluginId(), node.getPluginVersion(), node.getConfiguration(),
                    node.getInputMapping(), node.getOutputMapping(),
                    node.getConditions().stream()
                            .map(condition -> new ConditionView(condition.getBranch(),
                                    condition.getExpression(), condition.getDescription()))
                            .toList(),
                    node.getDefaultBranch(), node.getFormId(), node.getFormVersion(),
                    node.isWaitForInput(), node.getOutputs(),
                    String.valueOf(node.effectiveErrorPolicy()), node.getTimeoutMillis(),
                    node.getPresentation());
        }
    }

    /**
     * A decision branch as returned by the API.
     *
     * @param branch      port name
     * @param expression  boolean expression
     * @param description what it means
     */
    public record ConditionView(String branch, String expression, String description) {
    }

    /**
     * An edge as returned by the API.
     *
     * @param id         edge id
     * @param source     source node id
     * @param sourcePort branch name
     * @param target     target node id
     * @param label      display label
     * @param condition  guard expression
     */
    public record ConnectionView(String id, String source, String sourcePort, String target, String label,
                                 String condition) {

        static ConnectionView from(WorkflowConnection connection) {
            return new ConnectionView(connection.getId(), connection.getSource(), connection.getSourcePort(),
                    connection.getTarget(), connection.getLabel(), connection.getCondition());
        }
    }

    /**
     * A trigger as returned by the API.
     *
     * @param id           trigger id
     * @param type         trigger type
     * @param enabled      whether it is live
     * @param cron         cron expression
     * @param timezone     cron timezone
     * @param eventName    subscribed event
     * @param defaultInput fixed input
     */
    public record TriggerView(String id, String type, boolean enabled, String cron, String timezone,
                              String eventName, Map<String, Object> defaultInput,
                              com.orchpilot.workflow.scheduler.ScheduleConfig schedule) {

        static TriggerView from(WorkflowTrigger trigger) {
            return new TriggerView(trigger.getId(), String.valueOf(trigger.getType()), trigger.isEnabled(),
                    trigger.getCron(), trigger.getTimezone(), trigger.getEventName(),
                    trigger.getDefaultInput(), trigger.getSchedule());
        }
    }
}
