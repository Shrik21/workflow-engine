package com.orchpilot.workflow.ai.analysis;

/**
 * The facts about a failed node that are sent for analysis.
 *
 * <h2>An allowlist, not a snapshot</h2>
 *
 * This record is the whole of what leaves the engine. It is a fixed, curated set of identifiers and the error
 * itself — deliberately not the node's configuration, its inputs, its outputs, the workflow document, or the
 * plugin's request body. Those are exactly where a credential, a customer's data or a file's contents would be,
 * and none of it is needed to explain a permission error.
 *
 * <p>Being a record with named components rather than a {@code Map} is the point: adding a field is a visible,
 * reviewable change, whereas a map invites "just put the execution record in".
 *
 * @param tenantId            owning tenant
 * @param workflowId          the workflow
 * @param workflowVersion     the version that ran
 * @param workflowInstanceId  the execution
 * @param nodeId              the node that failed
 * @param nodeType            its type
 * @param pluginId            the plugin that raised the error
 * @param pluginVersion       its version
 * @param operationId         the plugin capability, e.g. {@code gcp.network.create}
 * @param cloudProvider       e.g. {@code GCP}
 * @param project             the cloud project
 * @param resource            the resource being acted on
 * @param errorCode           the plugin's stable error code
 * @param errorMessage        the error text, already scrubbed
 */
public record ErrorAnalysisContext(String tenantId, String workflowId, Integer workflowVersion,
                                   String workflowInstanceId, String nodeId, String nodeType,
                                   String pluginId, String pluginVersion, String operationId,
                                   String cloudProvider, String project, String resource,
                                   String errorCode, String errorMessage) {
}
