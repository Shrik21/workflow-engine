package com.orchpilot.workflow.service;

import com.orchpilot.workflow.dto.WorkflowRequest;
import com.orchpilot.workflow.model.DecisionCondition;
import com.orchpilot.workflow.model.ErrorPolicy;
import com.orchpilot.workflow.model.RetryPolicy;
import com.orchpilot.workflow.model.TriggerType;
import com.orchpilot.workflow.model.WorkflowConnection;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.model.WorkflowTrigger;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Converts request DTOs into persistence models.
 *
 * <p>A separate mapper rather than accepting the model at the controller keeps three things out of a client's
 * reach: {@code status}, {@code version} and the optimistic-locking field. Without it, a caller could publish a
 * workflow by putting {@code "status": "PUBLISHED"} in a PUT body.
 *
 * <p>Unknown enum values are rejected with a message naming the valid ones, because "no enum constant" is not a
 * useful thing to return to an API client.
 */
@Component
public class WorkflowDtoMapper {

    /**
     * @param requests node DTOs, may be {@code null}
     * @return persistence models
     */
    public List<WorkflowNode> toNodes(List<WorkflowRequest.NodeRequest> requests) {
        List<WorkflowNode> nodes = new ArrayList<>();
        if (requests == null) {
            return nodes;
        }
        for (WorkflowRequest.NodeRequest request : requests) {
            WorkflowNode node = new WorkflowNode();
            node.setId(request.id());
            node.setType(request.type() == null ? null : request.type().trim());
            node.setName(request.name() == null ? request.id() : request.name());
            node.setDescription(request.description());
            node.setPluginId(request.pluginId());
            node.setPluginVersion(request.pluginVersion());
            node.setConfiguration(request.configuration() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(request.configuration()));
            node.setInputMapping(request.inputMapping() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(request.inputMapping()));
            node.setOutputMapping(request.outputMapping() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(request.outputMapping()));
            node.setConditions(toConditions(request.conditions()));
            node.setDefaultBranch(request.defaultBranch());
            node.setFormId(request.formId());
            node.setFormVersion(request.formVersion());
            node.setWaitForInput(request.waitForInput() == null || request.waitForInput());
            node.setOutputs(request.outputs() == null ? new ArrayList<>() : new ArrayList<>(request.outputs()));
            node.setRetry(toRetryPolicy(request.retry()));
            node.setErrorPolicy(toErrorPolicy(request.errorPolicy()));
            node.setCompensationNodeId(request.compensationNodeId());
            node.setTimeoutMillis(request.timeoutMillis());
            node.setPresentation(request.presentation() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(request.presentation()));
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * @param requests connection DTOs, may be {@code null}
     * @return persistence models
     */
    public List<WorkflowConnection> toConnections(List<WorkflowRequest.ConnectionRequest> requests) {
        List<WorkflowConnection> connections = new ArrayList<>();
        if (requests == null) {
            return connections;
        }
        for (WorkflowRequest.ConnectionRequest request : requests) {
            WorkflowConnection connection = new WorkflowConnection();
            connection.setId(request.id());
            connection.setSource(request.source());
            connection.setSourcePort(request.sourcePort());
            connection.setTarget(request.target());
            connection.setLabel(request.label());
            connection.setCondition(request.condition());
            connections.add(connection);
        }
        return connections;
    }

    /**
     * @param requests trigger DTOs, may be {@code null}
     * @return persistence models
     */
    public List<WorkflowTrigger> toTriggers(List<WorkflowRequest.TriggerRequest> requests) {
        List<WorkflowTrigger> triggers = new ArrayList<>();
        if (requests == null) {
            return triggers;
        }
        for (WorkflowRequest.TriggerRequest request : requests) {
            WorkflowTrigger trigger = new WorkflowTrigger();
            trigger.setId(request.id());
            trigger.setType(parseEnum(TriggerType.class, request.type(), TriggerType.MANUAL, "trigger type"));
            trigger.setEnabled(request.enabled() == null || request.enabled());
            trigger.setCron(request.cron());
            trigger.setTimezone(request.timezone());
            trigger.setSchedule(request.schedule());
            trigger.setEventName(request.eventName());
            trigger.setDefaultInput(request.defaultInput() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(request.defaultInput()));
            triggers.add(trigger);
        }
        return triggers;
    }

    private List<DecisionCondition> toConditions(List<WorkflowRequest.ConditionRequest> requests) {
        List<DecisionCondition> conditions = new ArrayList<>();
        if (requests == null) {
            return conditions;
        }
        for (WorkflowRequest.ConditionRequest request : requests) {
            DecisionCondition condition = new DecisionCondition(request.branch(), request.expression());
            condition.setDescription(request.description());
            conditions.add(condition);
        }
        return conditions;
    }

    private RetryPolicy toRetryPolicy(WorkflowRequest.RetryRequest request) {
        if (request == null) {
            return null;
        }
        RetryPolicy policy = new RetryPolicy();
        policy.setEnabled(request.enabled() == null || request.enabled());
        if (request.maxAttempts() != null) {
            policy.setMaxAttempts(request.maxAttempts());
        }
        if (request.backoffMillis() != null) {
            policy.setBackoffMillis(request.backoffMillis());
        }
        if (request.backoffMultiplier() != null) {
            policy.setBackoffMultiplier(request.backoffMultiplier());
        }
        if (request.maxBackoffMillis() != null) {
            policy.setMaxBackoffMillis(request.maxBackoffMillis());
        }
        return policy;
    }

    private ErrorPolicy toErrorPolicy(String value) {
        return parseEnum(ErrorPolicy.class, value, ErrorPolicy.FAIL_WORKFLOW, "error policy");
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback, String what) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown " + what + " '" + value + "'. Valid values: "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }
}
