package com.orchpilot.plugin.gcp.network;

import com.orchpilot.plugin.gcp.network.client.ComputeClient;
import com.orchpilot.plugin.gcp.network.client.GoogleCredentials;
import com.orchpilot.plugin.gcp.network.client.GoogleTokenSource;
import com.orchpilot.plugin.gcp.network.exception.GcpNetworkException;
import com.orchpilot.plugin.gcp.network.model.NetworkOperation;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.exception.PluginException;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * VPCs, subnets, firewall rules, routes, Cloud Routers, Cloud NAT and peering as OrchPilot workflow nodes.
 *
 * <h2>One node in the designer</h2>
 *
 * The palette shows a single <em>GCP Network</em> row and the property panel offers the thirty-two operations
 * as a searchable dropdown. Underneath, each is its own node type — see {@link NetworkOperation} for why that
 * is what makes per-operation risk and per-operation AI tools possible at all.
 *
 * <h2>No Google SDK</h2>
 *
 * Everything goes over the Compute Engine v1 REST API through the engine's allow-listed HTTP client, with the
 * service-account JWT-bearer exchange signed by the JDK. Same approach as the GCP Compute Instance and GCP
 * Kubernetes plugins already in this repository — and the only one available here, since the Google Cloud Java
 * libraries are not resolvable in this build environment.
 *
 * <h2>How this stays inside the existing platform</h2>
 *
 * There is no second AI agent, plugin server, registry, security system or workflow engine. Configuration
 * arrives already variable-resolved by the engine's own resolver, so {@code ${gcpProject}} is substituted
 * before this code sees it, and every authorization decision stays the engine's. Credentials are read by name
 * through the audited secret provider and never enter the workflow, its output, the logs or the agent's
 * context.
 *
 * <p>Thread-safe: the only mutable state is the token cache, which is concurrent, and each execution builds
 * its own {@link NetworkOperations}.
 */
public class GcpNetworkPlugin implements WorkflowNodePlugin {

    static final String PLUGIN_ID = "orchpilot-gcp-network";
    private static final String PLUGIN_VERSION = "1.0.1";
    private static final String CATEGORY = "GCP Network";

    /** Shared across nodes so a workflow's many calls mint one token rather than one per node. */
    private final GoogleTokenSource tokens = new GoogleTokenSource();

    private volatile PluginContext context;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "GCP Network";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "Manage GCP VPCs, subnets, firewall rules, routes, Cloud Routers, Cloud NAT and VPC peering.";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) throws PluginException {
        this.context = pluginContext;
        pluginContext.logger().info("GCP Network plugin initialised with {} operations",
                NetworkOperation.values().length);
    }

    @Override
    public void destroy() {
        if (context != null) {
            context.logger().info("GCP Network plugin destroyed");
        }
    }

    // ------------------------------------------------------------------ node catalogue

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        List<NodeDefinition> definitions = new ArrayList<>(NetworkOperation.values().length);
        for (NetworkOperation operation : NetworkOperation.values()) {
            definitions.add(NodeDefinition.builder(operation.nodeType())
                    .displayName(operation.displayName())
                    .description(operation.description() + " [capability: " + operation.capability()
                            + ", risk: " + operation.risk() + ", permission: " + operation.permission() + "]")
                    .category(CATEGORY)
                    .icon("cloud")
                    .configurationSchema(NodeSchemas.forOperation(operation))
                    .outputVariables("success", "pluginId", "operationId", "operation", "data", "count",
                            "items", "name", "vpcName", "subnetName", "firewallName", "routeName",
                            "routerName", "natName", "peeringName", "securityFindings")
                    // A read is safely repeatable; a create or delete is not, so the engine guards resumes.
                    .idempotent(operation.readOnly())
                    .supportsRetry(true)
                    .supportsAI(true)
                    .destructive(operation.destructive())
                    .build());
        }
        return definitions;
    }

    // ------------------------------------------------------------------ execution

    @Override
    public NodeExecutionResult execute(NodeExecutionContext executionContext) {
        NetworkOperation operation = NetworkOperation.forNodeType(executionContext.nodeType());
        if (operation == null) {
            return NodeExecutionResult.failure("GCP_OPERATION_FAILED",
                    "Unknown GCP Network node type: " + executionContext.nodeType());
        }
        NodeConfiguration cfg = executionContext.configuration();
        long startedAt = System.currentTimeMillis();

        try {
            GoogleCredentials credentials = GoogleCredentials.fromServiceAccountJson(
                    context.secrets().require(cfg.requireString("connection")));
            Supplier<String> token = () -> tokens.accessToken(credentials, context.http());

            ComputeClient compute = new ComputeClient(context.http(), token,
                    executionContext.timeoutMillis());

            NodeExecutionResult result = new NetworkOperations(operation, cfg, compute,
                    executionContext::isCancelled).run();

            audit(executionContext, operation, cfg, result.isSuccess() ? "SUCCESS" : "FAILED", startedAt,
                    null);
            return result;
        } catch (GcpNetworkException ex) {
            context.logger().warn("GCP Network {} failed: {} ({})", operation, ex.errorCode(),
                    ex.getMessage());
            audit(executionContext, operation, cfg, "FAILED", startedAt, ex.errorCode());
            return NodeExecutionResult.failure(ex.errorCode(), ex.getMessage(), ex.retryable());
        } catch (PluginConfigurationException ex) {
            audit(executionContext, operation, cfg, "FAILED", startedAt, "GCP_INVALID_ARGUMENT");
            return NodeExecutionResult.failure("GCP_INVALID_ARGUMENT", ex.getMessage());
        }
    }

    /**
     * Records what happened through the plugin's own data store.
     *
     * <p>Metadata only: who, which workflow, which operation, which project and resource, and the outcome.
     * Never a credential, never a token, and never the body of a request — an audit trail holding a copy of
     * what it audits is a second place for the same data to leak from.
     *
     * <p>Best-effort: a failed audit write must not fail an operation that already changed a network.
     */
    private void audit(NodeExecutionContext ctx, NetworkOperation operation, NodeConfiguration cfg,
                       String outcome, long startedAt, String errorCode) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("pluginId", PLUGIN_ID);
            record.put("pluginVersion", PLUGIN_VERSION);
            record.put("operation", operation.name());
            record.put("operationId", operation.capability());
            record.put("riskLevel", operation.risk().name());
            record.put("permission", operation.permission());
            record.put("destructive", operation.destructive());
            record.put("workflowId", ctx.workflowId());
            record.put("workflowVersion", ctx.workflowVersion());
            record.put("workflowInstanceId", ctx.executionId());
            record.put("nodeId", ctx.nodeId());
            ctx.currentUser().ifPresent(user -> {
                record.put("userId", user.userId());
                record.put("username", user.username());
            });
            record.put("gcpProject", cfg.getString("project", null));
            record.put("resource", resourceName(cfg));
            record.put("status", outcome);
            record.put("errorCode", errorCode);
            record.put("executionTimeMs", System.currentTimeMillis() - startedAt);
            record.put("timestamp", Instant.now().toString());
            context.dataStore().put("audit",
                    ctx.executionId() + ":" + ctx.nodeId() + ":" + ctx.attempt(), record);
        } catch (RuntimeException ex) {
            context.logger().warn("Could not write a GCP Network audit record: {}", ex.getMessage());
        }
    }

    /** Whichever resource name the operation was given, so the audit line says what was touched. */
    private static String resourceName(NodeConfiguration cfg) {
        for (String key : List.of("vpcName", "subnetName", "firewallName", "routeName", "routerName",
                "natName", "peeringName", "network")) {
            String value = cfg.getString(key, null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
