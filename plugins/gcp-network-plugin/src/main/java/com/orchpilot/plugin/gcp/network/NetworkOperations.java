package com.orchpilot.plugin.gcp.network;

import com.orchpilot.plugin.gcp.network.client.ComputeClient;
import com.orchpilot.plugin.gcp.network.exception.GcpNetworkException;
import com.orchpilot.plugin.gcp.network.model.NetworkOperation;
import com.orchpilot.plugin.gcp.network.service.NetworkResources;
import com.orchpilot.plugin.gcp.network.validation.CidrValidator;
import com.orchpilot.plugin.gcp.network.validation.FirewallExposure;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Executes one GCP network operation.
 *
 * <h2>Constructed per attempt</h2>
 *
 * Holds the configuration, the client and the cancellation flag, so the handlers read as the operation and
 * nothing else. Nothing is shared between executions, which is what makes concurrent nodes safe without locking.
 *
 * <h2>Two gates before anything destructive</h2>
 *
 * <ul>
 *   <li><b>Confirmation.</b> Every HIGH and CRITICAL operation refuses unless {@code confirmed} is true. This
 *       applies to any caller, agent or not, so a hand-built workflow cannot delete a VPC because a variable
 *       resolved to something unexpected.</li>
 *   <li><b>Dependencies.</b> A delete first asks what still references the resource and refuses while anything
 *       does, naming it. The specification is explicit that dependents are never removed automatically, and
 *       that is enforced here rather than left to GCP's own error — which arrives as a generic 400.</li>
 * </ul>
 */
final class NetworkOperations {

    /** Caps every listing so a large project cannot produce an output too big for a workflow variable. */
    private static final int LIST_CAP = 500;

    private final NetworkOperation operation;
    private final NodeConfiguration cfg;
    private final ComputeClient compute;
    private final BooleanSupplier cancelled;
    private final long operationTimeoutMillis;

    NetworkOperations(NetworkOperation operation, NodeConfiguration cfg, ComputeClient compute,
                      BooleanSupplier cancelled) {
        this.operation = operation;
        this.cfg = cfg;
        this.compute = compute;
        this.operationTimeoutMillis = Math.max(cfg.getLong("operationTimeoutSeconds", 300), 10) * 1000;
        this.cancelled = cancelled;
    }

    // ------------------------------------------------------------------ dispatch

    NodeExecutionResult run() {
        return switch (operation) {
            case CREATE_VPC -> createVpc();
            case GET_VPC -> getVpc();
            case LIST_VPCS -> listVpcs();
            case UPDATE_VPC -> updateVpc();
            case DELETE_VPC -> deleteVpc();

            case CREATE_SUBNET -> createSubnet();
            case GET_SUBNET -> getSubnet();
            case LIST_SUBNETS -> listSubnets();
            case UPDATE_SUBNET -> updateSubnet();
            case DELETE_SUBNET -> deleteSubnet();

            case CREATE_FIREWALL -> createFirewall();
            case GET_FIREWALL -> getFirewall();
            case LIST_FIREWALLS -> listFirewalls();
            case UPDATE_FIREWALL -> updateFirewall();
            case DELETE_FIREWALL -> deleteFirewall();

            case CREATE_ROUTE -> createRoute();
            case GET_ROUTE -> getRoute();
            case LIST_ROUTES -> listRoutes();
            case DELETE_ROUTE -> deleteRoute();

            case CREATE_ROUTER -> createRouter();
            case GET_ROUTER -> getRouter();
            case LIST_ROUTERS -> listRouters();
            case DELETE_ROUTER -> deleteRouter();

            case CREATE_NAT -> createNat();
            case GET_NAT -> getNat();
            case UPDATE_NAT -> updateNat();
            case DELETE_NAT -> deleteNat();

            case CREATE_PEERING -> createPeering();
            case GET_PEERING -> getPeering();
            case LIST_PEERINGS -> listPeerings();
            case DELETE_PEERING -> deletePeering();

            case INSPECT_NETWORK -> inspect();
        };
    }

    // ================================================================ VPC

    private NodeExecutionResult createVpc() {
        String project = project();
        String name = require("vpcName");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        optional(body, "description", cfg.getString("description", null));

        // Custom mode unless explicitly asked otherwise: auto mode silently creates a subnet in every region,
        // which is rarely what someone building a network on purpose wants.
        boolean auto = cfg.getBoolean("autoCreateSubnets", false);
        body.put("autoCreateSubnetworks", auto);

        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("routingMode", cfg.getString("routingMode", "REGIONAL").toUpperCase(Locale.ROOT));
        body.put("routingConfig", routing);

        Map<String, Object> op = compute.insert(ComputeClient.globalPath(project, "networks"), body,
                "creating VPC '" + name + "'");
        awaitIfAsked(op, project, "creating VPC '" + name + "'");

        Map<String, Object> created = compute.get(
                ComputeClient.globalPath(project, "networks") + "/" + ComputeClient.encode(name),
                "VPC '" + name + "'");
        return success(NetworkResources.network(created), Map.of("vpcName", name));
    }

    private NodeExecutionResult getVpc() {
        String project = project();
        String name = require("vpcName");
        Map<String, Object> network = compute.get(
                ComputeClient.globalPath(project, "networks") + "/" + ComputeClient.encode(name),
                "VPC '" + name + "'");

        Map<String, Object> data = NetworkResources.network(network);
        data.put("network", network);
        return success(data, Map.of("vpcName", name));
    }

    private NodeExecutionResult listVpcs() {
        String project = project();
        List<Map<String, Object>> networks = compute.list(ComputeClient.globalPath(project, "networks"),
                cfg.getString("filter", null), LIST_CAP, "listing VPCs");
        return successList(summarise(networks, NetworkResources::network));
    }

    private NodeExecutionResult updateVpc() {
        String project = project();
        String name = require("vpcName");
        requireConfirmation();

        // Only these two are mutable on a network. Sending anything else produces a confusing 400 rather than
        // a helpful "that field cannot be changed".
        Map<String, Object> body = new LinkedHashMap<>();
        String routingMode = cfg.getString("routingMode", null);
        if (routingMode != null && !routingMode.isBlank()) {
            Map<String, Object> routing = new LinkedHashMap<>();
            routing.put("routingMode", routingMode.toUpperCase(Locale.ROOT));
            body.put("routingConfig", routing);
        }
        optional(body, "description", cfg.getString("description", null));
        if (body.isEmpty()) {
            throw GcpNetworkException.invalidArgument(
                    "Nothing to update. A VPC's routing mode and description are the only fields GCP allows "
                            + "to be changed after creation.");
        }

        Map<String, Object> op = compute.patch(
                ComputeClient.globalPath(project, "networks") + "/" + ComputeClient.encode(name), body,
                "updating VPC '" + name + "'");
        awaitIfAsked(op, project, "updating VPC '" + name + "'");
        return success(Map.of("name", name, "updated", body.keySet()), Map.of("vpcName", name));
    }

    private NodeExecutionResult deleteVpc() {
        String project = project();
        String name = require("vpcName");
        requireConfirmation();

        List<String> dependents = vpcDependents(project, name);
        if (!dependents.isEmpty()) {
            throw GcpNetworkException.hasDependencies("VPC '" + name + "'", dependents);
        }

        Map<String, Object> op = compute.delete(
                ComputeClient.globalPath(project, "networks") + "/" + ComputeClient.encode(name),
                "deleting VPC '" + name + "'");
        awaitIfAsked(op, project, "deleting VPC '" + name + "'");
        return success(Map.of("name", name, "deleted", true), Map.of("vpcName", name));
    }

    /**
     * Everything that would block a VPC deletion.
     *
     * <p>Checked before the call rather than after the failure, because Compute reports this as a generic
     * {@code RESOURCE_IN_USE_BY_ANOTHER_RESOURCE} that names one dependent at a time — so an operator deletes
     * a subnet, retries, and discovers a route, and repeats. Listing them all at once turns several rounds
     * into one.
     */
    private List<String> vpcDependents(String project, String network) {
        List<String> dependents = new ArrayList<>();
        String selfLinkSuffix = "/networks/" + network;

        int subnets = compute.listAggregated(ComputeClient.aggregatedPath(project, "subnetworks"),
                "subnetworks", null, LIST_CAP, "checking subnets of '" + network + "'").stream()
                .filter(subnet -> endsWith(subnet.get("network"), selfLinkSuffix))
                .toList().size();
        if (subnets > 0) {
            dependents.add(subnets + " subnet(s)");
        }

        int firewalls = compute.list(ComputeClient.globalPath(project, "firewalls"), null, LIST_CAP,
                "checking firewall rules of '" + network + "'").stream()
                .filter(rule -> endsWith(rule.get("network"), selfLinkSuffix))
                .toList().size();
        if (firewalls > 0) {
            dependents.add(firewalls + " firewall rule(s)");
        }

        // Compute creates a default route per network; those disappear with it and must not block a delete.
        int routes = compute.list(ComputeClient.globalPath(project, "routes"), null, LIST_CAP,
                "checking routes of '" + network + "'").stream()
                .filter(route -> endsWith(route.get("network"), selfLinkSuffix))
                .filter(route -> !isDefaultRoute(route))
                .toList().size();
        if (routes > 0) {
            dependents.add(routes + " custom route(s)");
        }

        int routers = compute.listAggregated(ComputeClient.aggregatedPath(project, "routers"), "routers",
                null, LIST_CAP, "checking Cloud Routers of '" + network + "'").stream()
                .filter(router -> endsWith(router.get("network"), selfLinkSuffix))
                .toList().size();
        if (routers > 0) {
            dependents.add(routers + " Cloud Router(s)");
        }

        Map<String, Object> networkResource = compute.get(
                ComputeClient.globalPath(project, "networks") + "/" + ComputeClient.encode(network),
                "VPC '" + network + "'");
        int peerings = NetworkResources.children(networkResource, "peerings").size();
        if (peerings > 0) {
            dependents.add(peerings + " peering(s)");
        }

        int instances = countInstancesOn(project, selfLinkSuffix);
        if (instances > 0) {
            dependents.add(instances + " VM instance(s)");
        }
        return dependents;
    }

    // ================================================================ subnet

    private NodeExecutionResult createSubnet() {
        String project = project();
        String region = require("region");
        String name = require("subnetName");
        String range = require("ipCidrRange");

        CidrValidator.requireSubnetRange(range, "ipCidrRange");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("ipCidrRange", range);
        body.put("network", networkLink(project, require("vpcName")));
        optional(body, "description", cfg.getString("description", null));
        body.put("privateIpGoogleAccess", cfg.getBoolean("privateGoogleAccess", false));

        if (cfg.getBoolean("flowLogs", false)) {
            body.put("enableFlowLogs", true);
            Map<String, Object> logging = new LinkedHashMap<>();
            logging.put("aggregationInterval", cfg.getString("flowLogInterval", "INTERVAL_5_SEC"));
            logging.put("flowSampling", 0.5);
            logging.put("metadata", "INCLUDE_ALL_METADATA");
            body.put("logConfig", logging);
        }

        List<Map<String, Object>> secondary = secondaryRanges();
        if (!secondary.isEmpty()) {
            body.put("secondaryIpRanges", secondary);
        }

        Map<String, Object> op = compute.insert(
                ComputeClient.regionalPath(project, region, "subnetworks"), body,
                "creating subnet '" + name + "'");
        awaitIfAsked(op, project, "creating subnet '" + name + "'");

        Map<String, Object> created = compute.get(
                ComputeClient.regionalPath(project, region, "subnetworks") + "/" + ComputeClient.encode(name),
                "subnet '" + name + "'");
        return success(NetworkResources.subnet(created), Map.of("subnetName", name, "region", region));
    }

    /** Parses the secondary-range list, validating each CIDR the same way the primary is. */
    private List<Map<String, Object>> secondaryRanges() {
        List<Map<String, Object>> ranges = new ArrayList<>();
        for (Map<String, Object> entry : mapList("secondaryIpRanges")) {
            String rangeName = text(entry.get("rangeName"));
            String cidr = text(entry.get("ipCidrRange"));
            if (rangeName == null || cidr == null) {
                throw GcpNetworkException.invalidArgument(
                        "Every secondary range needs a 'rangeName' and an 'ipCidrRange'.");
            }
            CidrValidator.requireSubnetRange(cidr, "secondaryIpRanges[" + rangeName + "]");
            Map<String, Object> range = new LinkedHashMap<>();
            range.put("rangeName", rangeName);
            range.put("ipCidrRange", cidr);
            ranges.add(range);
        }
        return ranges;
    }

    private NodeExecutionResult getSubnet() {
        String project = project();
        String region = require("region");
        String name = require("subnetName");
        Map<String, Object> subnet = compute.get(
                ComputeClient.regionalPath(project, region, "subnetworks") + "/" + ComputeClient.encode(name),
                "subnet '" + name + "'");

        Map<String, Object> data = NetworkResources.subnet(subnet);
        data.put("subnet", subnet);
        return success(data, Map.of("subnetName", name));
    }

    private NodeExecutionResult listSubnets() {
        String project = project();
        String region = cfg.getString("region", null);
        String filter = cfg.getString("filter", null);

        List<Map<String, Object>> subnets = region == null || region.isBlank()
                ? compute.listAggregated(ComputeClient.aggregatedPath(project, "subnetworks"), "subnetworks",
                        filter, LIST_CAP, "listing subnets")
                : compute.list(ComputeClient.regionalPath(project, region, "subnetworks"), filter, LIST_CAP,
                        "listing subnets in " + region);

        String vpc = cfg.getString("vpcName", null);
        if (vpc != null && !vpc.isBlank()) {
            subnets = subnets.stream()
                    .filter(subnet -> endsWith(subnet.get("network"), "/networks/" + vpc))
                    .toList();
        }
        return successList(summarise(subnets, NetworkResources::subnet));
    }

    private NodeExecutionResult updateSubnet() {
        String project = project();
        String region = require("region");
        String name = require("subnetName");
        requireConfirmation();

        Map<String, Object> body = new LinkedHashMap<>();
        // A PATCH on a subnetwork requires the fingerprint, which is optimistic concurrency: it fails rather
        // than clobbering a change someone else made between the read and the write.
        Map<String, Object> current = compute.get(
                ComputeClient.regionalPath(project, region, "subnetworks") + "/" + ComputeClient.encode(name),
                "subnet '" + name + "'");
        body.put("fingerprint", current.get("fingerprint"));

        if (cfg.has("privateGoogleAccess")) {
            body.put("privateIpGoogleAccess", cfg.getBoolean("privateGoogleAccess", false));
        }
        if (cfg.has("flowLogs")) {
            body.put("enableFlowLogs", cfg.getBoolean("flowLogs", false));
        }
        String expanded = cfg.getString("ipCidrRange", null);
        if (expanded != null && !expanded.isBlank()) {
            CidrValidator.requireSubnetRange(expanded, "ipCidrRange");
            body.put("ipCidrRange", expanded);
        }
        List<Map<String, Object>> secondary = secondaryRanges();
        if (!secondary.isEmpty()) {
            body.put("secondaryIpRanges", secondary);
        }
        optional(body, "description", cfg.getString("description", null));

        Map<String, Object> op = compute.patch(
                ComputeClient.regionalPath(project, region, "subnetworks") + "/" + ComputeClient.encode(name),
                body, "updating subnet '" + name + "'");
        awaitIfAsked(op, project, "updating subnet '" + name + "'");
        return success(Map.of("name", name, "region", region, "updated", body.keySet()),
                Map.of("subnetName", name));
    }

    private NodeExecutionResult deleteSubnet() {
        String project = project();
        String region = require("region");
        String name = require("subnetName");
        requireConfirmation();

        List<String> dependents = new ArrayList<>();
        int instances = countInstancesOn(project, "/subnetworks/" + name);
        if (instances > 0) {
            dependents.add(instances + " VM instance(s)");
        }
        if (!dependents.isEmpty()) {
            throw GcpNetworkException.hasDependencies("Subnet '" + name + "'", dependents);
        }

        Map<String, Object> op = compute.delete(
                ComputeClient.regionalPath(project, region, "subnetworks") + "/" + ComputeClient.encode(name),
                "deleting subnet '" + name + "'");
        awaitIfAsked(op, project, "deleting subnet '" + name + "'");
        return success(Map.of("name", name, "region", region, "deleted", true), Map.of("subnetName", name));
    }

    // ================================================================ firewall

    private NodeExecutionResult createFirewall() {
        String project = project();
        String name = require("firewallName");
        Map<String, Object> body = firewallBody(project, name, true);

        List<FirewallExposure.Finding> findings = assessExposure(body);
        Map<String, Object> op = compute.insert(ComputeClient.globalPath(project, "firewalls"), body,
                "creating firewall rule '" + name + "'");
        awaitIfAsked(op, project, "creating firewall rule '" + name + "'");

        Map<String, Object> created = compute.get(
                ComputeClient.globalPath(project, "firewalls") + "/" + ComputeClient.encode(name),
                "firewall rule '" + name + "'");
        Map<String, Object> data = NetworkResources.firewall(created);
        addFindings(data, findings);
        return success(data, Map.of("firewallName", name));
    }

    private NodeExecutionResult updateFirewall() {
        String project = project();
        String name = require("firewallName");
        Map<String, Object> body = firewallBody(project, name, false);

        List<FirewallExposure.Finding> findings = assessExposure(body);
        Map<String, Object> op = compute.patch(
                ComputeClient.globalPath(project, "firewalls") + "/" + ComputeClient.encode(name), body,
                "updating firewall rule '" + name + "'");
        awaitIfAsked(op, project, "updating firewall rule '" + name + "'");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("updated", body.keySet());
        addFindings(data, findings);
        return success(data, Map.of("firewallName", name));
    }

    /**
     * Assesses a rule for internet exposure and gates on it.
     *
     * <p>Reported, never silently blocked — the specification is explicit and correct that a public bastion is
     * sometimes intended. What is required is that somebody said so: the confirmation flag turns the finding
     * into an explicit decision, and the finding travels in the output either way so the audit trail has it.
     */
    private List<FirewallExposure.Finding> assessExposure(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> sources = (List<String>) body.get("sourceRanges");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allowed = (List<Map<String, Object>>) body.get("allowed");

        List<FirewallExposure.Finding> findings = FirewallExposure.assess(
                text(body.get("direction")), allowed != null ? "ALLOW" : "DENY", sources, allowed);

        if (!findings.isEmpty() && !cfg.getBoolean("confirmed", false)) {
            StringBuilder message = new StringBuilder(
                    "This firewall rule exposes administrative access to the public internet:");
            for (FirewallExposure.Finding finding : findings) {
                message.append("\n  • ").append(finding.message());
            }
            message.append("\n\nSet 'confirmed' to true to proceed — from an approval or human-task node "
                    + "upstream — if this is intended.");
            throw GcpNetworkException.confirmationRequired(message.toString());
        }
        return findings;
    }

    private static void addFindings(Map<String, Object> data, List<FirewallExposure.Finding> findings) {
        List<Map<String, Object>> rendered = new ArrayList<>(findings.size());
        for (FirewallExposure.Finding finding : findings) {
            rendered.add(finding.toMap());
        }
        data.put("securityFindings", rendered);
        data.put("exposesAdministrativeAccess", !rendered.isEmpty());
    }

    /** Builds the firewall body. {@code forCreate} adds the fields GCP refuses to accept on a patch. */
    private Map<String, Object> firewallBody(String project, String name, boolean forCreate) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (forCreate) {
            body.put("name", name);
            body.put("network", networkLink(project, require("network")));
        }
        optional(body, "description", cfg.getString("description", null));
        body.put("direction", cfg.getString("direction", "INGRESS").toUpperCase(Locale.ROOT));
        body.put("priority", (int) cfg.getLong("priority", 1000));
        body.put("disabled", cfg.getBoolean("disabled", false));

        List<Map<String, Object>> rules = protocolRules();
        String action = cfg.getString("action", "ALLOW").toUpperCase(Locale.ROOT);
        body.put("DENY".equals(action) ? "denied" : "allowed", rules);

        List<String> sourceRanges = stringList("sourceRanges");
        CidrValidator.requireMatchRanges(sourceRanges, "sourceRanges");
        List<String> destinationRanges = stringList("destinationRanges");
        CidrValidator.requireMatchRanges(destinationRanges, "destinationRanges");

        putIfNotEmpty(body, "sourceRanges", sourceRanges);
        putIfNotEmpty(body, "destinationRanges", destinationRanges);
        putIfNotEmpty(body, "sourceTags", stringList("sourceTags"));
        putIfNotEmpty(body, "targetTags", stringList("targetTags"));
        putIfNotEmpty(body, "sourceServiceAccounts", stringList("sourceServiceAccounts"));
        putIfNotEmpty(body, "targetServiceAccounts", stringList("targetServiceAccounts"));

        Map<String, Object> logging = new LinkedHashMap<>();
        logging.put("enable", cfg.getBoolean("logging", false));
        body.put("logConfig", logging);
        return body;
    }

    /** {@code protocol} plus {@code ports}, in the shape Compute's allowed/denied array wants. */
    private List<Map<String, Object>> protocolRules() {
        List<Map<String, Object>> declared = mapList("rules");
        if (!declared.isEmpty()) {
            List<Map<String, Object>> rules = new ArrayList<>(declared.size());
            for (Map<String, Object> entry : declared) {
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("IPProtocol", text(entry.getOrDefault("protocol", "tcp")));
                List<String> ports = new ArrayList<>();
                if (entry.get("ports") instanceof List<?> list) {
                    for (Object port : list) {
                        ports.add(String.valueOf(port));
                    }
                }
                putIfNotEmpty(rule, "ports", ports);
                rules.add(rule);
            }
            return rules;
        }

        // The simple form: one protocol and a comma-separated port list.
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("IPProtocol", cfg.getString("protocol", "tcp"));
        putIfNotEmpty(rule, "ports", stringList("ports"));
        return List.of(rule);
    }

    private NodeExecutionResult getFirewall() {
        String project = project();
        String name = require("firewallName");
        Map<String, Object> rule = compute.get(
                ComputeClient.globalPath(project, "firewalls") + "/" + ComputeClient.encode(name),
                "firewall rule '" + name + "'");

        Map<String, Object> data = NetworkResources.firewall(rule);
        data.put("firewall", rule);
        return success(data, Map.of("firewallName", name));
    }

    private NodeExecutionResult listFirewalls() {
        String project = project();
        List<Map<String, Object>> rules = compute.list(ComputeClient.globalPath(project, "firewalls"),
                cfg.getString("filter", null), LIST_CAP, "listing firewall rules");

        String vpc = cfg.getString("network", null);
        if (vpc != null && !vpc.isBlank()) {
            rules = rules.stream().filter(rule -> endsWith(rule.get("network"), "/networks/" + vpc)).toList();
        }
        return successList(summarise(rules, NetworkResources::firewall));
    }

    private NodeExecutionResult deleteFirewall() {
        String project = project();
        String name = require("firewallName");
        requireConfirmation();

        Map<String, Object> op = compute.delete(
                ComputeClient.globalPath(project, "firewalls") + "/" + ComputeClient.encode(name),
                "deleting firewall rule '" + name + "'");
        awaitIfAsked(op, project, "deleting firewall rule '" + name + "'");
        return success(Map.of("name", name, "deleted", true), Map.of("firewallName", name));
    }

    // ================================================================ routes

    private NodeExecutionResult createRoute() {
        String project = project();
        String name = require("routeName");
        String destRange = require("destRange");
        CidrValidator.requireMatchRange(destRange, "destRange");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("network", networkLink(project, require("network")));
        body.put("destRange", destRange);
        body.put("priority", (int) cfg.getLong("priority", 1000));
        optional(body, "description", cfg.getString("description", null));
        putIfNotEmpty(body, "tags", stringList("tags"));
        applyNextHop(body, project);

        Map<String, Object> op = compute.insert(ComputeClient.globalPath(project, "routes"), body,
                "creating route '" + name + "'");
        awaitIfAsked(op, project, "creating route '" + name + "'");

        Map<String, Object> created = compute.get(
                ComputeClient.globalPath(project, "routes") + "/" + ComputeClient.encode(name),
                "route '" + name + "'");
        return success(NetworkResources.route(created), Map.of("routeName", name));
    }

    /**
     * Sets exactly one next hop.
     *
     * <p>Compute requires precisely one and rejects a body with two, so this refuses locally rather than
     * letting an ambiguous configuration reach the API as an opaque 400.
     */
    private void applyNextHop(Map<String, Object> body, String project) {
        String type = cfg.getString("nextHopType", "GATEWAY").toUpperCase(Locale.ROOT);
        String value = cfg.getString("nextHopValue", null);

        switch (type) {
            case "GATEWAY" -> body.put("nextHopGateway",
                    "projects/" + project + "/global/gateways/default-internet-gateway");
            case "IP" -> {
                requireValue(value, "nextHopValue", "an IP address");
                body.put("nextHopIp", value);
            }
            case "INSTANCE" -> {
                requireValue(value, "nextHopValue", "an instance self-link or name");
                String zone = cfg.getString("nextHopZone", null);
                body.put("nextHopInstance", value.startsWith("http") || value.contains("/")
                        ? value
                        : "projects/" + project + "/zones/" + requireValue(zone, "nextHopZone", "a zone")
                                + "/instances/" + value);
            }
            case "VPN_TUNNEL" -> {
                requireValue(value, "nextHopValue", "a VPN tunnel self-link");
                body.put("nextHopVpnTunnel", value);
            }
            case "ILB" -> {
                requireValue(value, "nextHopValue", "a forwarding-rule self-link");
                body.put("nextHopIlb", value);
            }
            default -> throw GcpNetworkException.invalidArgument(
                    "Unknown next hop type '" + type + "'. Use GATEWAY, IP, INSTANCE, VPN_TUNNEL or ILB.");
        }
    }

    private NodeExecutionResult getRoute() {
        String project = project();
        String name = require("routeName");
        Map<String, Object> route = compute.get(
                ComputeClient.globalPath(project, "routes") + "/" + ComputeClient.encode(name),
                "route '" + name + "'");
        Map<String, Object> data = NetworkResources.route(route);
        data.put("route", route);
        return success(data, Map.of("routeName", name));
    }

    private NodeExecutionResult listRoutes() {
        String project = project();
        List<Map<String, Object>> routes = compute.list(ComputeClient.globalPath(project, "routes"),
                cfg.getString("filter", null), LIST_CAP, "listing routes");

        String vpc = cfg.getString("network", null);
        if (vpc != null && !vpc.isBlank()) {
            routes = routes.stream().filter(r -> endsWith(r.get("network"), "/networks/" + vpc)).toList();
        }
        return successList(summarise(routes, NetworkResources::route));
    }

    private NodeExecutionResult deleteRoute() {
        String project = project();
        String name = require("routeName");
        requireConfirmation();

        Map<String, Object> op = compute.delete(
                ComputeClient.globalPath(project, "routes") + "/" + ComputeClient.encode(name),
                "deleting route '" + name + "'");
        awaitIfAsked(op, project, "deleting route '" + name + "'");
        return success(Map.of("name", name, "deleted", true), Map.of("routeName", name));
    }

    // ================================================================ Cloud Router

    private NodeExecutionResult createRouter() {
        String project = project();
        String region = require("region");
        String name = require("routerName");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("network", networkLink(project, require("network")));
        optional(body, "description", cfg.getString("description", null));

        long asn = cfg.getLong("bgpAsn", 0);
        if (asn > 0) {
            Map<String, Object> bgp = new LinkedHashMap<>();
            bgp.put("asn", asn);
            bgp.put("advertiseMode", cfg.getString("bgpAdvertiseMode", "DEFAULT"));
            body.put("bgp", bgp);
        }

        Map<String, Object> op = compute.insert(ComputeClient.regionalPath(project, region, "routers"), body,
                "creating Cloud Router '" + name + "'");
        awaitIfAsked(op, project, "creating Cloud Router '" + name + "'");

        Map<String, Object> created = routerResource(project, region, name);
        return success(NetworkResources.router(created), Map.of("routerName", name, "region", region));
    }

    private NodeExecutionResult getRouter() {
        String project = project();
        String region = require("region");
        String name = require("routerName");
        Map<String, Object> router = routerResource(project, region, name);

        Map<String, Object> data = NetworkResources.router(router);
        data.put("router", router);
        return success(data, Map.of("routerName", name));
    }

    private NodeExecutionResult listRouters() {
        String project = project();
        String region = cfg.getString("region", null);
        List<Map<String, Object>> routers = region == null || region.isBlank()
                ? compute.listAggregated(ComputeClient.aggregatedPath(project, "routers"), "routers",
                        cfg.getString("filter", null), LIST_CAP, "listing Cloud Routers")
                : compute.list(ComputeClient.regionalPath(project, region, "routers"),
                        cfg.getString("filter", null), LIST_CAP, "listing Cloud Routers in " + region);
        return successList(summarise(routers, NetworkResources::router));
    }

    private NodeExecutionResult deleteRouter() {
        String project = project();
        String region = require("region");
        String name = require("routerName");
        requireConfirmation();

        Map<String, Object> router = routerResource(project, region, name);
        int nats = NetworkResources.children(router, "nats").size();
        if (nats > 0 && !cfg.getBoolean("deleteNatConfigurations", false)) {
            throw GcpNetworkException.hasDependencies("Cloud Router '" + name + "'",
                    List.of(nats + " NAT configuration(s)"));
        }

        Map<String, Object> op = compute.delete(
                ComputeClient.regionalPath(project, region, "routers") + "/" + ComputeClient.encode(name),
                "deleting Cloud Router '" + name + "'");
        awaitIfAsked(op, project, "deleting Cloud Router '" + name + "'");
        return success(Map.of("name", name, "region", region, "deleted", true),
                Map.of("routerName", name));
    }

    // ================================================================ Cloud NAT

    /**
     * NAT is not a resource of its own.
     *
     * <p>It lives in a Cloud Router's {@code nats[]} array, so every NAT operation is a read-modify-write of
     * its router. That is why these three read the router first, change one entry, and PATCH the whole array
     * back — and why deleting the last NAT means sending an array with one fewer element rather than a DELETE.
     */
    private NodeExecutionResult createNat() {
        String project = project();
        String region = require("region");
        String routerName = require("routerName");
        String name = require("natName");

        Map<String, Object> router = routerResource(project, region, routerName);
        List<Map<String, Object>> nats = new ArrayList<>(NetworkResources.children(router, "nats"));
        for (Map<String, Object> existing : nats) {
            if (name.equals(text(existing.get("name")))) {
                throw new GcpNetworkException("GCP_RESOURCE_ALREADY_EXISTS",
                        "Router '" + routerName + "' already has a NAT configuration called '" + name + "'.",
                        false);
            }
        }
        nats.add(natBody(project, region, name));
        return patchRouterNats(project, region, routerName, nats, name,
                "creating Cloud NAT '" + name + "'");
    }

    private NodeExecutionResult updateNat() {
        String project = project();
        String region = require("region");
        String routerName = require("routerName");
        String name = require("natName");
        requireConfirmation();

        Map<String, Object> router = routerResource(project, region, routerName);
        List<Map<String, Object>> nats = new ArrayList<>(NetworkResources.children(router, "nats"));
        boolean replaced = false;
        for (int i = 0; i < nats.size(); i++) {
            if (name.equals(text(nats.get(i).get("name")))) {
                nats.set(i, natBody(project, region, name));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            throw GcpNetworkException.notFound("NAT configuration '" + name + "' on router '"
                    + routerName + "'");
        }
        return patchRouterNats(project, region, routerName, nats, name,
                "updating Cloud NAT '" + name + "'");
    }

    private NodeExecutionResult deleteNat() {
        String project = project();
        String region = require("region");
        String routerName = require("routerName");
        String name = require("natName");
        requireConfirmation();

        Map<String, Object> router = routerResource(project, region, routerName);
        List<Map<String, Object>> nats = new ArrayList<>(NetworkResources.children(router, "nats"));
        boolean removed = nats.removeIf(nat -> name.equals(text(nat.get("name"))));
        if (!removed) {
            throw GcpNetworkException.notFound("NAT configuration '" + name + "' on router '"
                    + routerName + "'");
        }
        return patchRouterNats(project, region, routerName, nats, name,
                "deleting Cloud NAT '" + name + "'");
    }

    private NodeExecutionResult getNat() {
        String project = project();
        String region = require("region");
        String routerName = require("routerName");
        String name = require("natName");

        Map<String, Object> router = routerResource(project, region, routerName);
        for (Map<String, Object> nat : NetworkResources.children(router, "nats")) {
            if (name.equals(text(nat.get("name")))) {
                Map<String, Object> data = NetworkResources.nat(nat);
                data.put("router", routerName);
                data.put("region", region);
                return success(data, Map.of("natName", name));
            }
        }
        throw GcpNetworkException.notFound("NAT configuration '" + name + "' on router '" + routerName + "'");
    }

    private Map<String, Object> natBody(String project, String region, String name) {
        Map<String, Object> nat = new LinkedHashMap<>();
        nat.put("name", name);
        nat.put("natIpAllocateOption", cfg.getString("natIpAllocateOption", "AUTO_ONLY"));

        String selection = cfg.getString("sourceSubnetworkIpRangesToNat",
                "ALL_SUBNETWORKS_ALL_IP_RANGES");
        nat.put("sourceSubnetworkIpRangesToNat", selection);

        // Only a LIST_OF_SUBNETWORKS selection may name subnets; sending them otherwise is rejected.
        if ("LIST_OF_SUBNETWORKS".equals(selection)) {
            List<Map<String, Object>> subnets = new ArrayList<>();
            for (String name0 : stringList("subnetworks")) {
                Map<String, Object> subnet = new LinkedHashMap<>();
                subnet.put("name", "projects/" + project + "/regions/" + region + "/subnetworks/" + name0);
                subnet.put("sourceIpRangesToNat", List.of("ALL_IP_RANGES"));
                subnets.add(subnet);
            }
            if (subnets.isEmpty()) {
                throw GcpNetworkException.invalidArgument(
                        "A LIST_OF_SUBNETWORKS NAT needs at least one entry in 'subnetworks'.");
            }
            nat.put("subnetworks", subnets);
        }

        List<String> natIps = stringList("natIps");
        if ("MANUAL_ONLY".equals(nat.get("natIpAllocateOption"))) {
            if (natIps.isEmpty()) {
                throw GcpNetworkException.invalidArgument(
                        "A MANUAL_ONLY NAT needs at least one reserved address in 'natIps'.");
            }
            nat.put("natIps", natIps);
        }

        Map<String, Object> logging = new LinkedHashMap<>();
        logging.put("enable", cfg.getBoolean("logging", false));
        logging.put("filter", cfg.getString("logFilter", "ALL"));
        nat.put("logConfig", logging);
        return nat;
    }

    private NodeExecutionResult patchRouterNats(String project, String region, String routerName,
                                                List<Map<String, Object>> nats, String natName,
                                                String what) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nats", nats);

        Map<String, Object> op = compute.patch(
                ComputeClient.regionalPath(project, region, "routers") + "/"
                        + ComputeClient.encode(routerName), body, what);
        awaitIfAsked(op, project, what);

        Map<String, Object> updated = routerResource(project, region, routerName);
        Map<String, Object> data = NetworkResources.router(updated);
        data.put("natName", natName);
        return success(data, Map.of("natName", natName, "routerName", routerName));
    }

    // ================================================================ peering

    private NodeExecutionResult createPeering() {
        String project = project();
        String network = require("network");
        String name = require("peeringName");

        Map<String, Object> peering = new LinkedHashMap<>();
        peering.put("name", name);
        peering.put("network", peerNetworkLink());
        peering.put("exchangeSubnetRoutes", cfg.getBoolean("exportSubnetRoutes", true));
        peering.put("importCustomRoutes", cfg.getBoolean("importCustomRoutes", false));
        peering.put("exportCustomRoutes", cfg.getBoolean("exportCustomRoutes", false));
        peering.put("importSubnetRoutesWithPublicIp",
                cfg.getBoolean("importSubnetRoutes", false));
        peering.put("exportSubnetRoutesWithPublicIp",
                cfg.getBoolean("exportSubnetRoutes", false));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("networkPeering", peering);

        Map<String, Object> op = compute.action(
                ComputeClient.globalPath(project, "networks") + "/" + ComputeClient.encode(network)
                        + "/addPeering", body, "creating peering '" + name + "'");
        awaitIfAsked(op, project, "creating peering '" + name + "'");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("network", network);
        data.put("peerNetwork", cfg.getString("peerNetwork", null));
        // INACTIVE until the other side peers back, which is normal and worth saying rather than looking broken.
        data.put("note", "A peering becomes ACTIVE only once the peer network peers back to this one.");
        return success(data, Map.of("peeringName", name));
    }

    private NodeExecutionResult getPeering() {
        String project = project();
        String network = require("network");
        String name = require("peeringName");

        Map<String, Object> resource = compute.get(
                ComputeClient.globalPath(project, "networks") + "/" + ComputeClient.encode(network),
                "VPC '" + network + "'");
        for (Map<String, Object> peering : NetworkResources.children(resource, "peerings")) {
            if (name.equals(text(peering.get("name")))) {
                return success(NetworkResources.peering(peering), Map.of("peeringName", name));
            }
        }
        throw GcpNetworkException.notFound("Peering '" + name + "' on network '" + network + "'");
    }

    private NodeExecutionResult listPeerings() {
        String project = project();
        String network = require("network");
        Map<String, Object> resource = compute.get(
                ComputeClient.globalPath(project, "networks") + "/" + ComputeClient.encode(network),
                "VPC '" + network + "'");
        return successList(summarise(NetworkResources.children(resource, "peerings"),
                NetworkResources::peering));
    }

    private NodeExecutionResult deletePeering() {
        String project = project();
        String network = require("network");
        String name = require("peeringName");
        requireConfirmation();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        Map<String, Object> op = compute.action(
                ComputeClient.globalPath(project, "networks") + "/" + ComputeClient.encode(network)
                        + "/removePeering", body, "deleting peering '" + name + "'");
        awaitIfAsked(op, project, "deleting peering '" + name + "'");
        return success(Map.of("name", name, "network", network, "deleted", true),
                Map.of("peeringName", name));
    }

    // ================================================================ inspection

    /**
     * Reads a VPC and everything attached to it in one operation.
     *
     * <p>The point is a single call an agent or a report can use instead of eight. Each part is collected
     * independently and a failure on one — most often a permission the service account lacks for that resource
     * type — is recorded rather than failing the whole inspection, because a partial picture is far more useful
     * than none.
     */
    private NodeExecutionResult inspect() {
        String project = project();
        String network = require("vpcName");
        String suffix = "/networks/" + network;

        Map<String, Object> resource = compute.get(
                ComputeClient.globalPath(project, "networks") + "/" + ComputeClient.encode(network),
                "VPC '" + network + "'");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vpc", NetworkResources.network(resource));

        List<String> unavailable = new ArrayList<>();
        data.put("subnets", collect(unavailable, "subnets", () -> summarise(
                compute.listAggregated(ComputeClient.aggregatedPath(project, "subnetworks"), "subnetworks",
                        null, LIST_CAP, "listing subnets").stream()
                        .filter(s -> endsWith(s.get("network"), suffix)).toList(),
                NetworkResources::subnet)));

        data.put("firewallRules", collect(unavailable, "firewallRules", () -> summarise(
                compute.list(ComputeClient.globalPath(project, "firewalls"), null, LIST_CAP,
                        "listing firewall rules").stream()
                        .filter(f -> endsWith(f.get("network"), suffix)).toList(),
                NetworkResources::firewall)));

        data.put("routes", collect(unavailable, "routes", () -> summarise(
                compute.list(ComputeClient.globalPath(project, "routes"), null, LIST_CAP, "listing routes")
                        .stream().filter(r -> endsWith(r.get("network"), suffix)).toList(),
                NetworkResources::route)));

        data.put("routers", collect(unavailable, "routers", () -> summarise(
                compute.listAggregated(ComputeClient.aggregatedPath(project, "routers"), "routers", null,
                        LIST_CAP, "listing Cloud Routers").stream()
                        .filter(r -> endsWith(r.get("network"), suffix)).toList(),
                NetworkResources::router)));

        data.put("peerings", summarise(NetworkResources.children(resource, "peerings"),
                NetworkResources::peering));

        data.put("instances", collect(unavailable, "instances", () -> summarise(
                compute.listAggregated(ComputeClient.aggregatedPath(project, "instances"), "instances", null,
                        LIST_CAP, "listing instances").stream()
                        .filter(i -> instanceUsesNetwork(i, suffix)).toList(),
                NetworkResources::instance)));

        // NAT lives on routers, so it is derived rather than fetched again.
        List<Map<String, Object>> nats = new ArrayList<>();
        if (data.get("routers") instanceof List<?> routers) {
            for (Object router : routers) {
                if (router instanceof Map<?, ?> map && map.get("nats") instanceof List<?> list) {
                    for (Object nat : list) {
                        if (nat instanceof Map<?, ?> entry) {
                            Map<String, Object> copy = new LinkedHashMap<>();
                            copy.putAll(castMap(entry));
                            copy.put("router", map.get("name"));
                            nats.add(copy);
                        }
                    }
                }
            }
        }
        data.put("nats", nats);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("subnets", sizeOf(data.get("subnets")));
        counts.put("firewallRules", sizeOf(data.get("firewallRules")));
        counts.put("routes", sizeOf(data.get("routes")));
        counts.put("routers", sizeOf(data.get("routers")));
        counts.put("nats", nats.size());
        counts.put("peerings", sizeOf(data.get("peerings")));
        counts.put("instances", sizeOf(data.get("instances")));
        data.put("counts", counts);

        if (!unavailable.isEmpty()) {
            data.put("unavailable", unavailable);
            data.put("partial", true);
        }
        return success(data, Map.of("vpcName", network));
    }

    /** Runs one part of an inspection, recording rather than propagating a per-resource failure. */
    private List<Map<String, Object>> collect(List<String> unavailable, String what,
                                              java.util.function.Supplier<List<Map<String, Object>>> part) {
        try {
            return part.get();
        } catch (GcpNetworkException ex) {
            unavailable.add(what + " (" + ex.errorCode() + ")");
            return List.of();
        }
    }

    // ------------------------------------------------------------------ shared helpers

    private int countInstancesOn(String project, String suffix) {
        try {
            return compute.listAggregated(ComputeClient.aggregatedPath(project, "instances"), "instances",
                    null, LIST_CAP, "checking instances").stream()
                    .filter(instance -> instanceUsesNetwork(instance, suffix))
                    .toList().size();
        } catch (GcpNetworkException ex) {
            // Without permission to list instances this check cannot run. Reporting zero would be a false
            // all-clear, so the deletion is refused with the reason instead.
            throw new GcpNetworkException(ex.errorCode(),
                    "Could not check for VM instances before deleting: " + ex.getMessage()
                            + " Grant compute.instances.list, or delete from the console.", false);
        }
    }

    private static boolean instanceUsesNetwork(Map<String, Object> instance, String suffix) {
        for (Map<String, Object> nic : NetworkResources.children(instance, "networkInterfaces")) {
            if (endsWith(nic.get("network"), suffix) || endsWith(nic.get("subnetwork"), suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Compute's own per-network default routes; they vanish with the network and must not block a delete. */
    private static boolean isDefaultRoute(Map<String, Object> route) {
        String name = text(route.get("name"));
        return name != null && name.startsWith("default-route-");
    }

    private Map<String, Object> routerResource(String project, String region, String name) {
        return compute.get(
                ComputeClient.regionalPath(project, region, "routers") + "/" + ComputeClient.encode(name),
                "Cloud Router '" + name + "'");
    }

    private String networkLink(String project, String network) {
        return network.startsWith("http") || network.contains("/")
                ? network
                : "projects/" + project + "/global/networks/" + network;
    }

    /** The peer side of a peering, which may be in another project. */
    private String peerNetworkLink() {
        String peerNetwork = require("peerNetwork");
        if (peerNetwork.startsWith("http") || peerNetwork.contains("/")) {
            return peerNetwork;
        }
        String peerProject = cfg.getString("peerProject", null);
        if (peerProject == null || peerProject.isBlank()) {
            throw GcpNetworkException.invalidArgument(
                    "Peering needs 'peerProject' when 'peerNetwork' is a bare name rather than a self-link.");
        }
        return "projects/" + peerProject + "/global/networks/" + peerNetwork;
    }

    /**
     * Waits for the operation unless the node opted out.
     *
     * <p>Waiting is the default because "GCP accepted the request" is not "the subnet exists", and the next
     * node in a workflow almost always needs the latter. Turning it off is for a long create whose result a
     * later node polls for itself.
     */
    private void awaitIfAsked(Map<String, Object> operation, String project, String what) {
        if (!cfg.getBoolean("waitForCompletion", true)) {
            return;
        }
        compute.await(operation, project, cancelled, operationTimeoutMillis, what);
    }

    /** The second gate on anything destructive; the first is the node's {@code destructive} flag. */
    private void requireConfirmation() {
        if (!operation.destructive()) {
            return;
        }
        if (!cfg.getBoolean("requireConfirmation", true) || cfg.getBoolean("confirmed", false)) {
            return;
        }
        throw GcpNetworkException.confirmationRequired(
                operation.displayName() + " is " + operation.risk() + " risk and has not been confirmed. "
                        + "Set 'confirmed' to true from an approval or human-task node, or turn off "
                        + "'requireConfirmation' if the workflow gates it another way.");
    }

    private String project() {
        return require("project");
    }

    private String require(String key) {
        String value = cfg.getString(key, null);
        if (value == null || value.isBlank()) {
            throw GcpNetworkException.invalidArgument("'" + key + "' is required for "
                    + operation.displayName() + ".");
        }
        return value.trim();
    }

    private static String requireValue(String value, String field, String what) {
        if (value == null || value.isBlank()) {
            throw GcpNetworkException.invalidArgument("'" + field + "' must be " + what + ".");
        }
        return value.trim();
    }

    // ------------------------------------------------------------------ output

    private NodeExecutionResult success(Map<String, Object> data, Map<String, String> named) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("pluginId", GcpNetworkPlugin.PLUGIN_ID);
        outputs.put("operationId", operation.capability());
        outputs.put("operation", operation.name());
        outputs.put("data", data);
        // Promoted alongside data so a later node can write ${vpcName} rather than ${result.data.name}.
        outputs.putAll(named);
        outputs.putAll(data);
        return NodeExecutionResult.success(outputs);
    }

    private NodeExecutionResult successList(List<Map<String, Object>> items) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("pluginId", GcpNetworkPlugin.PLUGIN_ID);
        outputs.put("operationId", operation.capability());
        outputs.put("operation", operation.name());
        outputs.put("count", items.size());
        outputs.put("items", items);
        outputs.put("data", items);
        return NodeExecutionResult.success(outputs);
    }

    private static List<Map<String, Object>> summarise(
            List<Map<String, Object>> resources,
            java.util.function.Function<Map<String, Object>, Map<String, Object>> summariser) {
        List<Map<String, Object>> summaries = new ArrayList<>(resources.size());
        for (Map<String, Object> resource : resources) {
            summaries.add(summariser.apply(resource));
        }
        return summaries;
    }

    // ------------------------------------------------------------------ configuration readers

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(String key) {
        Object raw = cfg.find(key).orElse(null);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    /**
     * Reads a list field, accepting either an array or a comma-separated string.
     *
     * <p>Both arrive in practice: the designer's key/value controls produce arrays, while a workflow variable
     * carrying {@code "22,443"} is the natural thing an author writes.
     */
    private List<String> stringList(String key) {
        Object raw = cfg.find(key).orElse(null);
        if (raw == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                String text = String.valueOf(entry).trim();
                if (!text.isEmpty()) {
                    values.add(text);
                }
            }
            return values;
        }
        for (String part : String.valueOf(raw).split(",")) {
            String text = part.trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        return values;
    }

    private static void optional(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    private static void putIfNotEmpty(Map<String, Object> body, String key, List<?> values) {
        if (values != null && !values.isEmpty()) {
            body.put(key, values);
        }
    }

    private static boolean endsWith(Object selfLink, String suffix) {
        return selfLink != null && String.valueOf(selfLink).endsWith(suffix);
    }

    private static int sizeOf(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
