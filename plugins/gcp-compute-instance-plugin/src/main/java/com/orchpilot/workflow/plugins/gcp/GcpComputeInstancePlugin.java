package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.exception.PluginException;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;
import com.orchpilot.workflow.sdk.schema.SchemaBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Manage Google Cloud Compute Engine VM instances from OrchPilot workflows and AI Agents.
 *
 * <h2>How this stays inside OrchPilot's plugin architecture</h2>
 *
 * Every GCP concern lives here, in the plugin — the engine gains no Google code. The plugin reaches Google only
 * through the SDK: the engine's allow-listed {@link com.orchpilot.workflow.sdk.context.PluginHttpClient} for the
 * Compute REST API (no Google SDK dependency, which is also what lets it build offline), the
 * {@link com.orchpilot.workflow.sdk.context.SecretProvider} for the service-account key (never workflow config,
 * never output, never the model), and the {@link com.orchpilot.workflow.sdk.context.PluginDataStore} for an audit
 * trail. Configuration is already variable-resolved by the engine, so {@code ${instanceName}} and friends arrive
 * substituted through OrchPilot's own resolver.
 *
 * <h2>One node per operation</h2>
 *
 * Each Compute operation is its own node type ({@link GcpOperation}), which is what lets the AI Agent see ten
 * distinct tools with per-operation risk: Delete is marked {@code destructive} so a supervised agent must have it
 * approved, while Get and List are read-only. The node's {@code supportsAI} flag makes it selectable as an agent
 * tool; selection is always explicit and authorization is always the engine's, never bypassed here.
 *
 * <p>Thread-safe: the only fields are the context and the stateless auth/token cache, written once at initialise.
 */
public class GcpComputeInstancePlugin implements WorkflowNodePlugin {

    private static final String PLUGIN_ID = "gcp-compute-instance";
    private static final String PLUGIN_VERSION = "1.0.1";
    private static final String CATEGORY = "GCP Compute";

    private final GcpAuth auth = new GcpAuth();
    private volatile PluginContext context;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "GCP Compute Instance";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "Create, inspect and manage Google Cloud Compute Engine VM instances.";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) throws PluginException {
        this.context = pluginContext;
        pluginContext.logger().info("GCP Compute Instance plugin initialised");
    }

    @Override
    public void destroy() {
        if (context != null) {
            context.logger().info("GCP Compute Instance plugin destroyed");
        }
    }

    // ------------------------------------------------------------------ node catalogue

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        List<NodeDefinition> definitions = new ArrayList<>();
        for (GcpOperation operation : GcpOperation.values()) {
            definitions.add(NodeDefinition.builder(operation.nodeType())
                    .displayName(operation.displayName())
                    .description(operation.description())
                    .category(CATEGORY)
                    .icon("cloud")
                    .configurationSchema(schemaFor(operation))
                    .outputVariables("success", "operation", "projectId", "zone", "instanceId", "instanceName",
                            "status", "selfLink", "operationId")
                    // A create/start/stop/delete is not repeatable, so the engine guards retries and resumes.
                    .idempotent(operation == GcpOperation.GET || operation == GcpOperation.LIST)
                    .supportsRetry(true)
                    .supportsAI(true)
                    .destructive(operation.destructive())
                    .build());
        }
        return definitions;
    }

    private Map<String, Object> schemaFor(GcpOperation operation) {
        SchemaBuilder schema = SchemaBuilder.object()
                .secretRef("credentialsSecret", "GCP credentials secret name", true)
                .withDescription("credentialsSecret",
                        "The NAME of a secret holding the service-account JSON key (prefix gcp.). Never the key.")
                .string("projectId", "Project ID", true);

        if (operation == GcpOperation.LIST) {
            schema.string("zone", "Zone (optional; omit to list all zones)", false)
                    .string("filter", "Filter (optional, GCP filter syntax)", false);
            return schema.build();
        }

        schema.string("zone", "Zone", true);
        if (operation != GcpOperation.CREATE) {
            schema.string("instanceName", "Instance name", true);
        }

        switch (operation) {
            case CREATE -> createFields(schema);
            case DELETE -> {
                schema.bool("requireConfirmation", "Require confirmation", false).withDefault("requireConfirmation",
                        true);
                schema.bool("confirmed", "Confirmed", false)
                        .withDescription("confirmed",
                                "Must be true to delete when confirmation is required. Set it from an upstream "
                                        + "approval / human-task node, or leave the AI Agent's supervised approval to gate it.");
                waitFields(schema);
            }
            case GET -> { /* project, zone, instanceName only */ }
            default -> waitFields(schema); // START/STOP/RESTART/RESET/SUSPEND/RESUME
        }
        return schema.build();
    }

    private void createFields(SchemaBuilder schema) {
        schema.string("instanceName", "Instance name", true)
                .string("machineType", "Machine type", false).withDefault("machineType", "e2-medium")
                .string("image", "Boot image (full path/self-link)", false)
                .string("imageProject", "Image project", false)
                .withDescription("imageProject", "e.g. ubuntu-os-cloud, debian-cloud, windows-cloud")
                .string("imageFamily", "Image family", false)
                .withDescription("imageFamily", "e.g. ubuntu-2404-lts-amd64, debian-12")
                .string("imageName", "Image name (exact)", false)
                .integer("diskSizeGb", "Boot disk size (GB)", false).withDefault("diskSizeGb", 30)
                .select("diskType", "Boot disk type",
                        List.of("pd-standard", "pd-balanced", "pd-ssd", "hyperdisk-balanced"), false)
                .withDefault("diskType", "pd-balanced")
                .bool("autoDeleteBootDisk", "Auto-delete boot disk", false).withDefault("autoDeleteBootDisk", true)
                .string("network", "VPC network", false).withDefault("network", "default")
                .string("subnet", "Subnetwork (optional)", false)
                .select("externalIp", "External IP", List.of("EPHEMERAL", "NONE", "STATIC"), false)
                .withDefault("externalIp", "EPHEMERAL")
                .string("staticExternalIp", "Static external IP (when External IP = STATIC)", false)
                .text("startupScript", "Startup script (optional)", false)
                .map("labels", "Labels", false)
                .text("tags", "Network tags (comma or space separated)", false)
                .map("metadata", "Additional metadata (optional)", false)
                .string("serviceAccount", "Service account email (optional)", false)
                .bool("deletionProtection", "Deletion protection", false).withDefault("deletionProtection", false)
                .select("ifExists", "If instance exists", List.of("FAIL", "USE_EXISTING"), false)
                .withDefault("ifExists", "FAIL");
        waitFields(schema);
    }

    private void waitFields(SchemaBuilder schema) {
        schema.bool("waitForCompletion", "Wait for completion", false).withDefault("waitForCompletion", true)
                .integer("timeoutSeconds", "Timeout (seconds)", false).withDefault("timeoutSeconds", 600)
                .integer("pollIntervalSeconds", "Polling interval (seconds)", false)
                .withDefault("pollIntervalSeconds", 5);
    }

    // ------------------------------------------------------------------ execution

    @Override
    public NodeExecutionResult execute(NodeExecutionContext executionContext) {
        GcpOperation operation = GcpOperation.forNodeType(executionContext.nodeType());
        if (operation == null) {
            return NodeExecutionResult.failure("GCP_UNKNOWN_OPERATION",
                    "Unknown GCP node type: " + executionContext.nodeType());
        }
        NodeConfiguration cfg = executionContext.configuration();
        String project = cfg.requireString("projectId");

        try {
            GcpCredentials credentials = GcpCredentials.fromServiceAccountJson(
                    context.secrets().require(cfg.requireString("credentialsSecret")));
            Supplier<String> token = () -> auth.accessToken(credentials, context.http());
            GcpComputeClient client = new GcpComputeClient(context.http(), executionContext.timeoutMillis());

            NodeExecutionResult result = switch (operation) {
                case CREATE -> create(executionContext, cfg, client, token, project);
                case GET -> get(cfg, client, token, project);
                case LIST -> list(cfg, client, token, project);
                case DELETE -> delete(executionContext, cfg, client, token, project);
                case RESTART -> restart(executionContext, cfg, client, token, project);
                default -> action(executionContext, operation, cfg, client, token, project);
            };
            audit(executionContext, operation, project, cfg.getString("zone", null),
                    cfg.getString("instanceName", null), status(result), operationId(result));
            return result;
        } catch (GcpApiException ex) {
            context.logger().warn("GCP {} failed: {} ({})", operation, ex.errorCode(), ex.getMessage());
            audit(executionContext, operation, project, cfg.getString("zone", null),
                    cfg.getString("instanceName", null), "FAILED", null);
            return NodeExecutionResult.failure(ex.errorCode(), ex.getMessage(), ex.retryable());
        } catch (PluginConfigurationException ex) {
            return NodeExecutionResult.failure("GCP_MISCONFIGURED", ex.getMessage());
        }
    }

    private NodeExecutionResult create(NodeExecutionContext ctx, NodeConfiguration cfg, GcpComputeClient client,
                                       Supplier<String> token, String project) {
        String zone = cfg.requireString("zone");
        String name = cfg.requireString("instanceName");

        // Avoid an accidental duplicate VM: check first and honour the If-Exists policy.
        Map<String, Object> existing = tryGet(client, token, project, zone, name);
        if (existing != null) {
            String ifExists = cfg.getString("ifExists", "FAIL").toUpperCase(java.util.Locale.ROOT);
            if ("USE_EXISTING".equals(ifExists)) {
                context.logger().info("Instance {} already exists; using it", name);
                return NodeExecutionResult.success(instanceOutputs("CREATE", project, zone, existing, null));
            }
            return NodeExecutionResult.failure("GCP_INSTANCE_EXISTS",
                    "An instance named '" + name + "' already exists in " + zone + ".");
        }

        Map<String, Object> body = GcpInstanceBuilder.build(cfg, zone);
        Map<String, Object> op = client.insertInstance(token.get(), project, zone, body);
        return afterMutation(ctx, cfg, client, token, project, zone, name, op, "CREATE", "PROVISIONING");
    }

    private NodeExecutionResult get(NodeConfiguration cfg, GcpComputeClient client, Supplier<String> token,
                                    String project) {
        String zone = cfg.requireString("zone");
        Map<String, Object> instance = client.getInstance(token.get(), project, zone,
                cfg.requireString("instanceName"));
        Map<String, Object> outputs = instanceOutputs("GET", project, zone, instance, null);
        outputs.put("instance", instance);
        return NodeExecutionResult.success(outputs);
    }

    @SuppressWarnings("unchecked")
    private NodeExecutionResult list(NodeConfiguration cfg, GcpComputeClient client, Supplier<String> token,
                                     String project) {
        String zone = cfg.getString("zone", null);
        String filter = cfg.getString("filter", null);
        List<Map<String, Object>> instances = new ArrayList<>();
        String pageToken = null;
        do {
            Map<String, Object> page = (zone == null || zone.isBlank())
                    ? client.aggregatedList(token.get(), project, filter, pageToken)
                    : client.listInstances(token.get(), project, zone, filter, pageToken);
            collectInstances(page, zone, instances);
            pageToken = page.get("nextPageToken") instanceof String s && !s.isBlank() ? s : null;
        } while (pageToken != null && instances.size() < 1000);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("operation", "LIST");
        outputs.put("projectId", project);
        outputs.put("count", instances.size());
        outputs.put("instances", instances);
        return NodeExecutionResult.success(outputs);
    }

    private NodeExecutionResult delete(NodeExecutionContext ctx, NodeConfiguration cfg, GcpComputeClient client,
                                       Supplier<String> token, String project) {
        if (cfg.getBoolean("requireConfirmation", true) && !cfg.getBoolean("confirmed", false)) {
            return NodeExecutionResult.failure("GCP_CONFIRMATION_REQUIRED",
                    "Deleting an instance requires confirmation. Set 'confirmed' to true (via an approval/"
                            + "human-task node), or disable 'requireConfirmation'.");
        }
        String zone = cfg.requireString("zone");
        String name = cfg.requireString("instanceName");
        Map<String, Object> op = client.deleteInstance(token.get(), project, zone, name);
        return afterMutation(ctx, cfg, client, token, project, zone, name, op, "DELETE", "DELETING");
    }

    private NodeExecutionResult restart(NodeExecutionContext ctx, NodeConfiguration cfg, GcpComputeClient client,
                                        Supplier<String> token, String project) {
        String zone = cfg.requireString("zone");
        String name = cfg.requireString("instanceName");
        boolean wait = cfg.getBoolean("waitForCompletion", true);
        long timeout = cfg.getLong("timeoutSeconds", 600) * 1000;
        long interval = cfg.getLong("pollIntervalSeconds", 5) * 1000;

        Map<String, Object> stopOp = client.instanceAction(token.get(), project, zone, name, "stop");
        // A graceful restart must let the stop finish before starting, so this step always waits.
        OperationPoller.await(client, token, project, zone, operationName(stopOp), timeout, interval,
                ctx::isCancelled);
        Map<String, Object> startOp = client.instanceAction(token.get(), project, zone, name, "start");
        return afterMutationOp(ctx, wait, timeout, interval, client, token, project, zone, name, startOp,
                "RESTART", "RUNNING");
    }

    private NodeExecutionResult action(NodeExecutionContext ctx, GcpOperation operation, NodeConfiguration cfg,
                                       GcpComputeClient client, Supplier<String> token, String project) {
        String zone = cfg.requireString("zone");
        String name = cfg.requireString("instanceName");
        String verb = operation.name().toLowerCase(java.util.Locale.ROOT); // start/stop/reset/suspend/resume
        Map<String, Object> op = client.instanceAction(token.get(), project, zone, name, verb);
        return afterMutation(ctx, cfg, client, token, project, zone, name, op, operation.name(),
                pendingStatus(operation));
    }

    // ------------------------------------------------------------------ shared mutation handling

    private NodeExecutionResult afterMutation(NodeExecutionContext ctx, NodeConfiguration cfg,
                                              GcpComputeClient client, Supplier<String> token, String project,
                                              String zone, String name, Map<String, Object> op,
                                              String operationLabel, String pendingStatus) {
        boolean wait = cfg.getBoolean("waitForCompletion", true);
        long timeout = cfg.getLong("timeoutSeconds", 600) * 1000;
        long interval = cfg.getLong("pollIntervalSeconds", 5) * 1000;
        return afterMutationOp(ctx, wait, timeout, interval, client, token, project, zone, name, op,
                operationLabel, pendingStatus);
    }

    private NodeExecutionResult afterMutationOp(NodeExecutionContext ctx, boolean wait, long timeout, long interval,
                                                GcpComputeClient client, Supplier<String> token, String project,
                                                String zone, String name, Map<String, Object> op,
                                                String operationLabel, String pendingStatus) {
        String operationName = operationName(op);
        if (!wait) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("success", true);
            outputs.put("operation", operationLabel);
            outputs.put("projectId", project);
            outputs.put("zone", zone);
            outputs.put("instanceName", name);
            outputs.put("operationId", operationName);
            outputs.put("status", pendingStatus);
            outputs.put("selfLink", op.get("targetLink"));
            outputs.put("instanceId", op.get("targetId"));
            return NodeExecutionResult.success(outputs);
        }

        OperationPoller.await(client, token, project, zone, operationName, timeout, interval, ctx::isCancelled);

        // A delete leaves nothing to read back; every other mutation ends at a readable instance.
        if ("DELETE".equals(operationLabel)) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("success", true);
            outputs.put("operation", "DELETE");
            outputs.put("projectId", project);
            outputs.put("zone", zone);
            outputs.put("instanceName", name);
            outputs.put("operationId", operationName);
            outputs.put("status", "DELETED");
            return NodeExecutionResult.success(outputs);
        }
        Map<String, Object> instance = client.getInstance(token.get(), project, zone, name);
        Map<String, Object> outputs = instanceOutputs(operationLabel, project, zone, instance, operationName);
        return NodeExecutionResult.success(outputs);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> tryGet(GcpComputeClient client, Supplier<String> token, String project,
                                       String zone, String name) {
        try {
            return client.getInstance(token.get(), project, zone, name);
        } catch (GcpApiException ex) {
            if ("GCP_INSTANCE_NOT_FOUND".equals(ex.errorCode())) {
                return null;
            }
            throw ex;
        }
    }

    private static Map<String, Object> instanceOutputs(String operationLabel, String project, String zone,
                                                       Map<String, Object> instance, String operationName) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("operation", operationLabel);
        outputs.put("projectId", project);
        outputs.put("zone", zone == null ? shortZone(instance.get("zone")) : zone);
        outputs.put("instanceId", instance.get("id"));
        outputs.put("instanceName", instance.get("name"));
        outputs.put("status", instance.get("status"));
        outputs.put("selfLink", instance.get("selfLink"));
        outputs.put("operationId", operationName);
        outputs.put("machineType", shortZone(instance.get("machineType")));
        outputs.put("networkInterfaces", instance.get("networkInterfaces"));
        outputs.put("disks", instance.get("disks"));
        outputs.put("labels", instance.get("labels"));
        return outputs;
    }

    @SuppressWarnings("unchecked")
    private static void collectInstances(Map<String, Object> page, String zone, List<Map<String, Object>> out) {
        if (zone != null && !zone.isBlank()) {
            if (page.get("items") instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> map) {
                        out.add(summariseInstance((Map<String, Object>) map));
                    }
                }
            }
            return;
        }
        // Aggregated list: items is a map of "zones/<zone>" -> { instances: [...] }.
        if (page.get("items") instanceof Map<?, ?> byZone) {
            for (Object value : ((Map<String, Object>) byZone).values()) {
                if (value instanceof Map<?, ?> scoped && scoped.get("instances") instanceof List<?> instances) {
                    for (Object item : instances) {
                        if (item instanceof Map<?, ?> map) {
                            out.add(summariseInstance((Map<String, Object>) map));
                        }
                    }
                }
            }
        }
    }

    private static Map<String, Object> summariseInstance(Map<String, Object> instance) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", instance.get("name"));
        summary.put("zone", shortZone(instance.get("zone")));
        summary.put("status", instance.get("status"));
        summary.put("machineType", shortZone(instance.get("machineType")));
        return summary;
    }

    /** GCP returns fully-qualified URLs for zone/machineType; the last path segment is the useful name. */
    private static String shortZone(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        int slash = text.lastIndexOf('/');
        return slash >= 0 ? text.substring(slash + 1) : text;
    }

    private static String operationName(Map<String, Object> operation) {
        Object name = operation.get("name");
        if (name == null) {
            throw new GcpApiException("GCP_API_ERROR", "Compute returned an operation without a name.", false);
        }
        return String.valueOf(name);
    }

    private static String pendingStatus(GcpOperation operation) {
        return switch (operation) {
            case START, RESUME -> "RUNNING";
            case STOP -> "STOPPING";
            case SUSPEND -> "SUSPENDING";
            case RESET -> "RUNNING";
            default -> "REQUESTED";
        };
    }

    private static String status(NodeExecutionResult result) {
        Object status = result.outputs().get("status");
        return status == null ? (result.isSuccess() ? "OK" : "FAILED") : String.valueOf(status);
    }

    private static String operationId(NodeExecutionResult result) {
        Object id = result.outputs().get("operationId");
        return id == null ? null : String.valueOf(id);
    }

    /** Writes a metadata-only audit record; never a credential. Best-effort, so it cannot fail the node. */
    private void audit(NodeExecutionContext ctx, GcpOperation operation, String project, String zone,
                       String instanceName, String status, String operationId) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("pluginId", PLUGIN_ID);
            record.put("operation", operation.name());
            record.put("riskLevel", operation.risk().name());
            record.put("workflowId", ctx.workflowId());
            record.put("workflowExecutionId", ctx.executionId());
            record.put("nodeId", ctx.nodeId());
            ctx.currentUser().ifPresent(user -> {
                record.put("userId", user.userId());
                record.put("username", user.username());
            });
            record.put("projectId", project);
            record.put("zone", zone);
            record.put("instanceName", instanceName);
            record.put("status", status);
            record.put("operationId", operationId);
            record.put("timestamp", java.time.Instant.now().toString());
            String key = ctx.executionId() + ":" + ctx.nodeId() + ":" + ctx.attempt();
            context.dataStore().put("audit", key, record);
        } catch (RuntimeException ex) {
            context.logger().warn("Could not write GCP audit record: {}", ex.getMessage());
        }
    }
}
