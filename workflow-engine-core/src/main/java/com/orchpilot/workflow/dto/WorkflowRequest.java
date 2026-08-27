package com.orchpilot.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Payload for creating or replacing a workflow definition.
 *
 * <p>A request DTO rather than the persistence model, so that a client cannot set {@code status},
 * {@code version}, {@code publishedAt} or the optimistic-locking field by including them in the JSON. Those
 * are the engine's to manage, and accepting them would let a caller publish a workflow by editing a field.
 *
 * @param name        human-readable name
 * @param description what the workflow does
 * @param nodes       the graph's nodes
 * @param connections the graph's edges
 * @param variables   declared workflow-scope variables and their initial values
 * @param triggers    ways this workflow may be started
 * @param metadata    free-form labels, owner, tags; not interpreted by the engine
 */
public record WorkflowRequest(
        @NotBlank(message = "name is required") @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @NotEmpty(message = "at least one node is required") @Valid List<NodeRequest> nodes,
        @Valid List<ConnectionRequest> connections,
        Map<String, Object> variables,
        @Valid List<TriggerRequest> triggers,
        Map<String, Object> metadata) {

    /**
     * One node of the graph.
     *
     * @param id                 unique within the workflow
     * @param type               {@code START}, {@code FORM}, {@code DECISION}, {@code END}, {@code PLUGIN}, or
     *                           a node type contributed by a plugin
     * @param name               display name
     * @param description        what the node does
     * @param pluginId           plugin providing the behaviour, for plugin nodes
     * @param pluginVersion      exact plugin version; pin it to freeze behaviour across plugin upgrades
     * @param configuration      node configuration, may contain {@code ${...}} placeholders
     * @param inputMapping       input name to template
     * @param outputMapping      output name to destination variable path
     * @param conditions         ordered branch conditions, for decision nodes
     * @param defaultBranch      branch taken when no condition matches
     * @param formId             form identifier, for form nodes
     * @param formVersion        published form version to pin, or null to follow the newest
     * @param waitForInput       whether a form node parks the execution when no submission is present
     * @param outputs            variable paths an end node copies into the execution result
     * @param retry              retry policy
     * @param errorPolicy        what to do when the node fails: {@code FAIL_WORKFLOW}, {@code SKIP},
     *                           {@code CONTINUE} or {@code COMPENSATE}
     * @param compensationNodeId node that undoes this node's work, for {@code COMPENSATE}
     * @param timeoutMillis      per-node execution budget
     * @param presentation       designer coordinates; ignored by the engine
     */
    public record NodeRequest(
            @NotBlank(message = "node id is required") String id,
            @NotBlank(message = "node type is required") String type,
            String name,
            String description,
            String pluginId,
            String pluginVersion,
            Map<String, Object> configuration,
            Map<String, String> inputMapping,
            Map<String, String> outputMapping,
            List<ConditionRequest> conditions,
            String defaultBranch,
            String formId,
            Integer formVersion,
            Boolean waitForInput,
            List<String> outputs,
            RetryRequest retry,
            String errorPolicy,
            String compensationNodeId,
            Long timeoutMillis,
            Map<String, Object> presentation) {
    }

    /**
     * A decision branch.
     *
     * @param branch      outgoing port name
     * @param expression  boolean expression over variables, e.g. {@code amount > 10000}
     * @param description what the branch means
     */
    public record ConditionRequest(@NotBlank String branch, @NotBlank String expression, String description) {
    }

    /**
     * A directed edge.
     *
     * @param id         optional edge id
     * @param source     source node id
     * @param sourcePort branch name this edge serves; omit for the default edge
     * @param target     target node id
     * @param label      display label
     * @param condition  optional guard expression
     */
    public record ConnectionRequest(String id, @NotBlank String source, String sourcePort,
                                    @NotBlank String target, String label, String condition) {
    }

    /**
     * Retry configuration.
     *
     * @param enabled           whether the node retries at all
     * @param maxAttempts       total attempts including the first
     * @param backoffMillis     delay before the second attempt
     * @param backoffMultiplier growth factor per attempt
     * @param maxBackoffMillis  ceiling on the delay
     */
    public record RetryRequest(Boolean enabled, Integer maxAttempts, Long backoffMillis,
                               Double backoffMultiplier, Long maxBackoffMillis) {
    }

    /**
     * A way the workflow can start.
     *
     * @param id           unique within the workflow
     * @param type         {@code MANUAL}, {@code API}, {@code SCHEDULE} or {@code EVENT}
     * @param enabled      whether the trigger is live
     * @param cron         six-field Spring cron expression, for {@code SCHEDULE}
     * @param timezone     zone the cron is interpreted in; defaults to UTC
     * @param eventName    event that starts the workflow, for {@code EVENT}
     * @param defaultInput input merged beneath the runtime input
     */
    public record TriggerRequest(@NotBlank String id, @NotBlank String type, Boolean enabled, String cron,
                                 String timezone, String eventName, Map<String, Object> defaultInput,
                                 com.orchpilot.workflow.scheduler.ScheduleConfig schedule) {
    }
}
