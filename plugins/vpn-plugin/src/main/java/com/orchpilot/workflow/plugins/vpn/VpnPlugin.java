package com.orchpilot.workflow.plugins.vpn;

import com.orchpilot.workflow.plugins.vpn.provider.CloudHttp;
import com.orchpilot.workflow.plugins.vpn.provider.VpnProviderRegistry;
import com.orchpilot.workflow.plugins.vpn.spi.VpnConnectionRequest;
import com.orchpilot.workflow.plugins.vpn.spi.VpnOperationException;
import com.orchpilot.workflow.plugins.vpn.spi.VpnProvider;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionInfo;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionResult;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionStatus;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionTestResult;
import com.orchpilot.workflow.plugins.vpn.spi.VpnStatus;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages and reports secure-network (VPN) connection state, across providers, from a workflow.
 *
 * <h2>One node, a pluggable set of providers</h2>
 *
 * The engine sees a single {@code VPN} node. The node reads the chosen provider and operation, resolves the
 * connection's settings and credentials, and dispatches to a {@link VpnProvider} behind
 * {@link VpnProviderRegistry}. Adding AWS GovCloud, an SD-WAN appliance or a new cloud is a new provider class
 * and a line in the registry — no engine change, no new node type, no designer change. That is the whole
 * architectural point.
 *
 * <h2>Control plane, honestly</h2>
 *
 * This plugin manages and reports VPN state through provider control-plane APIs. It does not dial a tunnel up:
 * cloud Site-to-Site VPNs have no such operation, and host tunnels need a privileged client the engine JVM
 * must not run. So {@code Connect} converges and reports, statuses map from real provider states, a test says
 * exactly what it checked, and nothing claims connectivity it did not verify. See {@link VpnProvider}.
 *
 * <p>Thread-safe: the context and the provider registry are written once at {@code initialize}, and providers
 * are stateless.
 */
public class VpnPlugin implements WorkflowNodePlugin {

    /** The single node type this plugin contributes. */
    public static final String NODE_TYPE = "VPN";

    private static final String PLUGIN_ID = "vpn";
    private static final String PLUGIN_VERSION = "1.0.2";

    private volatile PluginContext context;
    private volatile VpnProviderRegistry providers;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "Secure Network / VPN";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "Manages and reports secure-network (VPN) connection state across cloud and generic providers";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) {
        this.context = pluginContext;
        this.providers = buildRegistry(pluginContext);
        pluginContext.logger().info("VPN plugin initialised with providers {}", providers.ids());
    }

    /**
     * Builds the provider registry.
     *
     * <p>A protected seam, not because production needs one but because a test does: it lets a fake provider
     * stand in for a live one so the node's dispatch, wait loop and output shaping can be exercised without a
     * network. Production always gets the built-in providers, wired to the engine's HTTP client so their
     * control-plane calls are bound by the plugin's allowlist.
     *
     * @param pluginContext the context, for the HTTP client
     * @return the registry
     */
    protected VpnProviderRegistry buildRegistry(PluginContext pluginContext) {
        return VpnProviderRegistry.built(new CloudHttp(pluginContext.http()));
    }

    @Override
    public void destroy() {
        this.providers = null;
    }

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        return List.of(NodeDefinition.builder(NODE_TYPE)
                .displayName("Secure Network / VPN")
                .description("Connect, disconnect, check, test and wait on a VPN connection across AWS, Azure, "
                        + "GCP and generic IPsec / OpenVPN / WireGuard. Reports real provider state; does not "
                        + "fake connectivity.")
                .category("Network")
                .icon("shield")
                .configurationSchema(schema())
                .outputVariables("vpnResult", "vpnResult.status", "vpnResult.connectionId",
                        "vpnResult.provider", "vpnResult.message", "success")
                // Reading state is idempotent; connect/disconnect are not, so the node as a whole is declared
                // non-idempotent and the engine replays a recorded result rather than repeating a change.
                .idempotent(false)
                .supportsRetry(true)
                .build());
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext execution) {
        VpnOperation operation = VpnOperation.parse(execution.configuration().getString("operation", ""))
                .orElse(null);
        if (operation == null) {
            return NodeExecutionResult.failure(VpnErrors.CONFIGURATION_INVALID,
                    "No VPN operation was chosen. Set 'operation' to one of: " + String.join(", ",
                            VpnOperation.names()), false);
        }

        String providerId = execution.configuration().getString("provider", "");
        VpnProvider provider = providers.find(providerId).orElse(null);
        if (provider == null) {
            return NodeExecutionResult.failure(VpnErrors.UNKNOWN_PROVIDER,
                    "'" + providerId + "' is not a VPN provider this plugin knows. Choose one of: "
                            + String.join(", ", providers.ids()), false);
        }

        String needed = operation == VpnOperation.WAIT_UNTIL_CONNECTED ? "STATUS" : operation.name();
        if (!provider.supportedOperations().contains(needed)) {
            return NodeExecutionResult.failure(VpnErrors.UNSUPPORTED_OPERATION,
                    provider.label() + " does not support " + operation.name() + ". It supports: "
                            + String.join(", ", provider.supportedOperations()) + ".", false);
        }

        VpnConnectionRequest request;
        try {
            request = VpnNodeConfiguration.build(operation.name(), provider, execution.configuration(),
                    execution::resolve, context.secrets());
        } catch (RuntimeException ex) {
            return NodeExecutionResult.failure(VpnErrors.CONFIGURATION_INVALID, ex.getMessage(), false);
        }

        long startedAt = System.currentTimeMillis();
        try {
            NodeExecutionResult result = dispatch(operation, provider, request, execution);
            // The operation, provider and connection; never a credential, never the settings, which can carry
            // a gateway address an operator would rather not see in every execution record.
            context.logger().info("VPN {} on {} in {}ms", operation.name(), request.describe(),
                    System.currentTimeMillis() - startedAt);
            return result;
        } catch (VpnOperationException ex) {
            context.logger().warn("VPN {} failed: {} {}", request.describe(), ex.code(), ex.getMessage());
            return NodeExecutionResult.failure(ex.code(), ex.getMessage(), ex.retryable());
        } catch (RuntimeException ex) {
            context.logger().warn("VPN {} failed: {}", request.describe(), ex.getClass().getSimpleName());
            return NodeExecutionResult.failure(VpnErrors.PROVIDER_ERROR,
                    "The VPN operation failed: " + ex.getMessage(), false);
        }
    }

    private NodeExecutionResult dispatch(VpnOperation operation, VpnProvider provider,
                                         VpnConnectionRequest request, NodeExecutionContext execution) {
        return switch (operation) {
            case CONNECT -> fromResult(request, provider.connect(request));
            case DISCONNECT -> fromResult(request, provider.disconnect(request));
            case STATUS -> fromStatus(request, provider.getStatus(request));
            case TEST_CONNECTION -> fromTest(request, provider.testConnection(request));
            case GET_INFO -> fromInfo(request, provider.getConnectionInfo(request));
            case WAIT_UNTIL_CONNECTED -> waitUntilConnected(provider, request, execution);
            // The advanced operations are a planned SPI extension; the given SPI has no create/delete/rotate.
            case CREATE, DELETE, ROTATE_CREDENTIALS, UPDATE_CONFIG -> NodeExecutionResult.failure(
                    VpnErrors.UNSUPPORTED_OPERATION,
                    operation.name() + " is not implemented: it needs a provider-specific control-plane "
                            + "extension beyond the connect/status SPI. Use CREATE/DELETE through the "
                            + "provider's own tooling for now.", false);
        };
    }

    /**
     * Polls status until CONNECTED or the timeout, honouring cancellation.
     *
     * <p>The timeout produces {@link VpnStatus#TIMEOUT} and the {@code VPN_CONNECTION_TIMEOUT} code, which is
     * the spec's contract for a wait that ran out. A FAILED status ends the wait immediately — there is no
     * point polling a connection the provider has already called down — while CONNECTING and UNKNOWN keep it
     * going.
     */
    private NodeExecutionResult waitUntilConnected(VpnProvider provider, VpnConnectionRequest request,
                                                   NodeExecutionContext execution) {
        int timeoutSeconds = execution.configuration().getInt("timeoutSeconds", 300);
        int pollSeconds = Math.max(1, execution.configuration().getInt("pollIntervalSeconds", 10));
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        VpnConnectionStatus last = null;
        int polls = 0;
        while (System.currentTimeMillis() < deadline) {
            if (execution.isCancelled()) {
                return NodeExecutionResult.failure(VpnErrors.PROVIDER_ERROR,
                        "The wait for a VPN connection was cancelled.", false);
            }
            last = provider.getStatus(request);
            polls++;
            if (last.status() == VpnStatus.CONNECTED) {
                return fromStatus(request, last, Map.of("polls", polls));
            }
            if (last.status() == VpnStatus.FAILED) {
                return outputs(request, VpnStatus.FAILED, request.connectionId(),
                        "The connection reported FAILED (" + last.providerState() + ") after " + polls
                                + " poll(s); not waiting further.", false, Map.of("polls", polls), false);
            }
            sleep(Math.min(pollSeconds * 1000L, Math.max(0, deadline - System.currentTimeMillis())));
        }

        String state = last == null ? "unknown" : last.providerState();
        return outputs(request, VpnStatus.TIMEOUT, request.connectionId(),
                "The connection did not reach CONNECTED within " + timeoutSeconds + "s (last state: " + state
                        + ").", false, Map.of("polls", polls), true);
    }

    // ---- turning each SPI result into node outputs, nested under vpnResult ----

    private NodeExecutionResult fromResult(VpnConnectionRequest request, VpnConnectionResult result) {
        return outputs(request, result.status(), result.connectionId(), result.message(), result.success(),
                result.details(), false);
    }

    private NodeExecutionResult fromStatus(VpnConnectionRequest request, VpnConnectionStatus status) {
        return fromStatus(request, status, Map.of());
    }

    private NodeExecutionResult fromStatus(VpnConnectionRequest request, VpnConnectionStatus status,
                                           Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>(status.details());
        details.putAll(extra);
        details.put("providerState", status.providerState());
        if (!status.tunnels().isEmpty()) {
            details.put("tunnels", status.tunnels().stream().map(tunnel -> Map.of(
                    "address", tunnel.address() == null ? "" : tunnel.address(),
                    "status", tunnel.status().name(),
                    "providerState", tunnel.providerState() == null ? "" : tunnel.providerState())).toList());
        }
        return outputs(request, status.status(), status.connectionId(),
                "Status: " + status.status() + " (" + status.providerState() + ").",
                status.status() == VpnStatus.CONNECTED, details, false);
    }

    private NodeExecutionResult fromTest(VpnConnectionRequest request, VpnConnectionTestResult test) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("checked", test.checked());
        if (test.latencyMillis() != null) {
            details.put("latencyMillis", test.latencyMillis());
        }
        return outputs(request, test.status(), request.connectionId(), test.message(), test.success(),
                details, false);
    }

    private NodeExecutionResult fromInfo(VpnConnectionRequest request, VpnConnectionInfo info) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("attributes", info.attributes());
        return outputs(request, info.status(), info.connectionId(),
                "Connection information for " + info.connectionId() + ".", true, details, false);
    }

    /**
     * Publishes the standard result, nested under the chosen output variable.
     *
     * <p>Nested, never as dotted keys: a key with a dot in it cannot be persisted into the execution document
     * and would strand the workflow in RUNNING after the operation had already run. The engine resolves a
     * dotted output mapping into this structure, so {@code vpnResult.status} reads it unchanged.
     */
    private NodeExecutionResult outputs(VpnConnectionRequest request, VpnStatus status, String connectionId,
                                        String message, boolean success, Map<String, Object> details,
                                        boolean timedOut) {
        String variable = variableName(request);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("status", status.name());
        result.put("provider", request.provider());
        result.put("connectionId", connectionId == null ? "" : connectionId);
        result.put("message", message);
        if (!details.isEmpty()) {
            result.put("details", details);
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put(variable, result);
        outputs.put("success", success);

        if (timedOut) {
            // A timeout is a workflow failure with the contracted code, so a Decision or error branch can act
            // on it — not a soft success that a later node mistakes for a live connection.
            return NodeExecutionResult.failure(VpnErrors.CONNECTION_TIMEOUT, message, false)
                    .withOutputs(outputs);
        }
        return NodeExecutionResult.success(outputs);
    }

    private static String variableName(VpnConnectionRequest request) {
        Object configured = request.settings().get("outputVariable");
        String name = configured == null ? "" : String.valueOf(configured).trim();
        if (name.isEmpty()) {
            return "vpnResult";
        }
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return "vpnResult";
        }
        return name;
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new VpnOperationException(VpnErrors.PROVIDER_ERROR, "The wait was interrupted.", false, ex);
        }
    }

    /** Health, for a monitoring probe: the plugin, its version, and the providers it can dispatch to. */
    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("plugin", PLUGIN_ID);
        health.put("version", PLUGIN_VERSION);
        health.put("status", providers == null ? "STOPPED" : "RUNNING");
        health.put("providers", providers == null ? List.of() : providers.ids());
        health.put("nodeType", NODE_TYPE);
        return health;
    }

    // ---- schema ----

    private Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("provider", select("Provider",
                providers == null ? List.of("AWS", "AZURE", "GCP", "IPSEC", "OPENVPN", "WIREGUARD")
                        : providers.ids(),
                "AWS", "Which VPN this node acts on. Provider-specific fields appear below."));
        properties.put("operation", select("Operation",
                List.of("CONNECT", "DISCONNECT", "STATUS", "TEST_CONNECTION", "GET_INFO",
                        "WAIT_UNTIL_CONNECTED"),
                "CONNECT",
                "For cloud providers CONNECT reports the tunnel state — there is no dial operation. "
                        + "TEST_CONNECTION says exactly what it checked."));

        properties.put("connectionProfile", field("string", "Connection profile / credential id",
                "Names a stored credential whose parts are the secrets <profile>.<name> — e.g. a profile "
                        + "'aws-prod' with secrets aws-prod.accessKeyId and aws-prod.secretKey. Select a "
                        + "profile instead of entering credentials on every node."));
        properties.put("connectionId", field("string", "Connection id",
                "The provider connection identifier: an AWS vpn-…, an Azure connection name, a GCP tunnel. "
                        + "Supports variables."));
        properties.put("region", field("string", "Region", "Cloud region, e.g. ap-south-1. Supports variables."));

        // AWS
        properties.put("endpointHost", field("string", "Endpoint host override",
                "Rarely needed. Overrides the provider's default API host (e.g. a GovCloud or sovereign "
                        + "endpoint). Add it to the plugin's allowed hosts."));

        // Azure
        properties.put("subscriptionId", visibleForAzure(field("string", "Subscription id", null)));
        properties.put("resourceGroup", visibleForAzure(field("string", "Resource group", null)));
        properties.put("connectionName", visibleForAzure(field("string", "Connection name", null)));

        // GCP
        properties.put("project", visibleForGcp(field("string", "Project", null)));
        properties.put("tunnel", visibleForGcp(field("string", "Tunnel name", null)));

        // Generic
        properties.put("endpoint", visibleForGeneric(field("string", "Endpoint (host or host:port)",
                "The gateway. For WireGuard, vpn.example.com:51820.")));
        properties.put("protocol", visibleFor(select("Protocol", List.of("UDP", "TCP"), "UDP",
                "OpenVPN only. TCP lets this node open a real reachability socket to the port."), "provider",
                List.of("OPENVPN")));
        properties.put("ikeVersion", visibleFor(select("IKE version", List.of("IKEV2", "IKEV1"), "IKEV2",
                null), "provider", List.of("IPSEC")));
        properties.put("localCidr", visibleForGeneric(field("string", "Local CIDR", null)));
        properties.put("remoteCidr", visibleForGeneric(field("string", "Remote CIDR", null)));
        properties.put("allowedIps", visibleFor(field("string", "Allowed IPs",
                "WireGuard, e.g. 10.0.0.0/8."), "provider", List.of("WIREGUARD")));

        // Wait
        properties.put("waitUntilConnected", field("boolean", "Wait until connected",
                "For CONNECT: after reporting, poll until CONNECTED or the timeout. Or use the "
                        + "WAIT_UNTIL_CONNECTED operation directly."));
        properties.put("timeoutSeconds", field("integer", "Timeout (seconds)",
                "For a wait. On expiry the node fails with VPN_CONNECTION_TIMEOUT."));
        properties.put("pollIntervalSeconds", field("integer", "Poll interval (seconds)", null));

        properties.put("outputVariable", field("string", "Output variable",
                "The name subsequent nodes use: 'vpnResult' makes ${vpnResult.status} available. Defaults to "
                        + "vpnResult."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("provider", "operation"));
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> field(String type, String title, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", type);
        field.put("title", title);
        if (description != null) {
            field.put("description", description);
        }
        return field;
    }

    private static Map<String, Object> select(String title, List<String> options, String defaultValue,
                                              String description) {
        Map<String, Object> field = field("string", title, description);
        field.put("enum", options);
        field.put("default", defaultValue);
        return field;
    }

    private static Map<String, Object> visibleFor(Map<String, Object> field, String dependsOn,
                                                  List<String> values) {
        field.put("visibleWhen", Map.of(dependsOn, values));
        return field;
    }

    private static Map<String, Object> visibleForAzure(Map<String, Object> field) {
        return visibleFor(field, "provider", List.of("AZURE"));
    }

    private static Map<String, Object> visibleForGcp(Map<String, Object> field) {
        return visibleFor(field, "provider", List.of("GCP"));
    }

    private static Map<String, Object> visibleForGeneric(Map<String, Object> field) {
        return visibleFor(field, "provider", List.of("IPSEC", "OPENVPN", "WIREGUARD"));
    }
}
