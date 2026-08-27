package com.orchpilot.plugin.gcp.network.service;

import com.orchpilot.plugin.gcp.network.client.ComputeClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattens Compute resources into the shallow maps a workflow branches on.
 *
 * <h2>Why summarise rather than pass through</h2>
 *
 * A Compute {@code Network} is a few kilobytes with fully-qualified self-links everywhere: its subnetworks
 * arrive as {@code https://www.googleapis.com/compute/v1/projects/p/regions/r/subnetworks/s} rather than
 * {@code s}. A Decision node asking "is this VPC in custom mode?" should not have to parse a URL, and an AI
 * agent should not spend a thousand tokens of context on link prefixes. Each summary below is the set of fields
 * that answer the questions workflows actually ask; the full resource stays available on the single-resource
 * reads for anything else.
 */
public final class NetworkResources {

    private NetworkResources() {
    }

    /** A VPC network. */
    public static Map<String, Object> network(Map<String, Object> network) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", network.get("name"));
        summary.put("id", network.get("id"));
        summary.put("description", network.get("description"));
        // autoCreateSubnetworks is absent on a custom-mode VPC rather than false, so its absence is the answer.
        boolean auto = Boolean.TRUE.equals(network.get("autoCreateSubnetworks"));
        summary.put("autoCreateSubnets", auto);
        summary.put("mode", auto ? "AUTO" : "CUSTOM");
        summary.put("routingMode", routingMode(network));
        summary.put("mtu", network.get("mtu"));
        summary.put("subnetCount", listOf(network, "subnetworks").size());
        summary.put("subnets", shortNames(listOf(network, "subnetworks")));
        summary.put("peeringCount", children(network, "peerings").size());
        summary.put("createdAt", network.get("creationTimestamp"));
        summary.put("selfLink", network.get("selfLink"));
        return summary;
    }

    /** A subnet. */
    public static Map<String, Object> subnet(Map<String, Object> subnet) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", subnet.get("name"));
        summary.put("region", ComputeClient.lastSegment(subnet.get("region")));
        summary.put("network", ComputeClient.lastSegment(subnet.get("network")));
        summary.put("ipCidrRange", subnet.get("ipCidrRange"));
        summary.put("gatewayAddress", subnet.get("gatewayAddress"));
        summary.put("privateGoogleAccess", Boolean.TRUE.equals(subnet.get("privateIpGoogleAccess")));
        summary.put("flowLogs", Boolean.TRUE.equals(subnet.get("enableFlowLogs")));
        summary.put("purpose", subnet.get("purpose"));
        summary.put("stackType", subnet.get("stackType"));

        List<Map<String, Object>> secondary = new ArrayList<>();
        for (Map<String, Object> range : children(subnet, "secondaryIpRanges")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rangeName", range.get("rangeName"));
            entry.put("ipCidrRange", range.get("ipCidrRange"));
            secondary.add(entry);
        }
        summary.put("secondaryRanges", secondary);
        summary.put("description", subnet.get("description"));
        summary.put("selfLink", subnet.get("selfLink"));
        return summary;
    }

    /** A firewall rule, with the allow/deny entries flattened into something readable. */
    public static Map<String, Object> firewall(Map<String, Object> firewall) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", firewall.get("name"));
        summary.put("network", ComputeClient.lastSegment(firewall.get("network")));
        summary.put("direction", firewall.getOrDefault("direction", "INGRESS"));
        summary.put("priority", firewall.get("priority"));
        summary.put("disabled", Boolean.TRUE.equals(firewall.get("disabled")));

        boolean deny = firewall.get("denied") != null;
        summary.put("action", deny ? "DENY" : "ALLOW");
        summary.put("rules", protocolsAndPorts(children(firewall, deny ? "denied" : "allowed")));

        summary.put("sourceRanges", firewall.get("sourceRanges"));
        summary.put("destinationRanges", firewall.get("destinationRanges"));
        summary.put("sourceTags", firewall.get("sourceTags"));
        summary.put("targetTags", firewall.get("targetTags"));
        summary.put("sourceServiceAccounts", firewall.get("sourceServiceAccounts"));
        summary.put("targetServiceAccounts", firewall.get("targetServiceAccounts"));
        summary.put("logging", loggingEnabled(firewall));
        summary.put("description", firewall.get("description"));
        return summary;
    }

    /** A static route. */
    public static Map<String, Object> route(Map<String, Object> route) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", route.get("name"));
        summary.put("network", ComputeClient.lastSegment(route.get("network")));
        summary.put("destRange", route.get("destRange"));
        summary.put("priority", route.get("priority"));
        summary.put("nextHop", nextHop(route));
        summary.put("tags", route.get("tags"));
        summary.put("description", route.get("description"));
        return summary;
    }

    /** A Cloud Router, including the NAT configurations that live on it. */
    public static Map<String, Object> router(Map<String, Object> router) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", router.get("name"));
        summary.put("region", ComputeClient.lastSegment(router.get("region")));
        summary.put("network", ComputeClient.lastSegment(router.get("network")));

        Map<String, Object> bgp = child(router, "bgp");
        if (bgp != null) {
            summary.put("bgpAsn", bgp.get("asn"));
            summary.put("bgpAdvertiseMode", bgp.get("advertiseMode"));
        }
        List<Map<String, Object>> nats = new ArrayList<>();
        for (Map<String, Object> nat : children(router, "nats")) {
            nats.add(nat(nat));
        }
        summary.put("natCount", nats.size());
        summary.put("nats", nats);
        summary.put("description", router.get("description"));
        return summary;
    }

    /** One NAT configuration from a router's {@code nats[]}. */
    public static Map<String, Object> nat(Map<String, Object> nat) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", nat.get("name"));
        summary.put("natIpAllocateOption", nat.get("natIpAllocateOption"));
        summary.put("sourceSubnetworkIpRangesToNat", nat.get("sourceSubnetworkIpRangesToNat"));
        summary.put("natIps", shortNames(asList(nat.get("natIps"))));

        List<Map<String, Object>> subnets = new ArrayList<>();
        for (Map<String, Object> subnet : children(nat, "subnetworks")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", ComputeClient.lastSegment(subnet.get("name")));
            entry.put("sourceIpRangesToNat", subnet.get("sourceIpRangesToNat"));
            subnets.add(entry);
        }
        summary.put("subnetworks", subnets);

        Map<String, Object> logging = child(nat, "logConfig");
        summary.put("logging", logging != null && Boolean.TRUE.equals(logging.get("enable")));
        return summary;
    }

    /** One VPC peering from a network's {@code peerings[]}. */
    public static Map<String, Object> peering(Map<String, Object> peering) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", peering.get("name"));
        summary.put("peerNetwork", ComputeClient.lastSegment(peering.get("network")));
        // ACTIVE only once both sides have peered; INACTIVE is the normal state after creating one half.
        summary.put("state", peering.get("state"));
        summary.put("stateDetails", peering.get("stateDetails"));
        summary.put("importCustomRoutes", Boolean.TRUE.equals(peering.get("importCustomRoutes")));
        summary.put("exportCustomRoutes", Boolean.TRUE.equals(peering.get("exportCustomRoutes")));
        summary.put("importSubnetRoutesWithPublicIp",
                Boolean.TRUE.equals(peering.get("importSubnetRoutesWithPublicIp")));
        summary.put("exportSubnetRoutesWithPublicIp",
                Boolean.TRUE.equals(peering.get("exportSubnetRoutesWithPublicIp")));
        return summary;
    }

    /** An instance, reduced to what matters when asking which VMs use a network. */
    public static Map<String, Object> instance(Map<String, Object> instance) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", instance.get("name"));
        summary.put("zone", ComputeClient.lastSegment(instance.get("zone")));
        summary.put("status", instance.get("status"));
        summary.put("machineType", ComputeClient.lastSegment(instance.get("machineType")));

        List<Map<String, Object>> interfaces = new ArrayList<>();
        for (Map<String, Object> nic : children(instance, "networkInterfaces")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("network", ComputeClient.lastSegment(nic.get("network")));
            entry.put("subnetwork", ComputeClient.lastSegment(nic.get("subnetwork")));
            entry.put("internalIp", nic.get("networkIP"));
            // An access config is what gives an instance a public address; its absence is the interesting case.
            List<Map<String, Object>> access = children(nic, "accessConfigs");
            entry.put("externalIp", access.isEmpty() ? null : access.get(0).get("natIP"));
            interfaces.add(entry);
        }
        summary.put("networkInterfaces", interfaces);
        return summary;
    }

    // ------------------------------------------------------------------ helpers

    /** Routing mode lives one level down, and its absence means regional. */
    public static String routingMode(Map<String, Object> network) {
        Map<String, Object> config = child(network, "routingConfig");
        Object mode = config == null ? null : config.get("routingMode");
        return mode == null ? "REGIONAL" : String.valueOf(mode);
    }

    /** Renders allow/deny entries as {@code tcp:22,443} rather than nested objects. */
    private static List<String> protocolsAndPorts(List<Map<String, Object>> entries) {
        List<String> rendered = new ArrayList<>(entries.size());
        for (Map<String, Object> entry : entries) {
            String protocol = String.valueOf(entry.getOrDefault("IPProtocol", "all"));
            List<Object> ports = asList(entry.get("ports"));
            rendered.add(ports.isEmpty() ? protocol : protocol + ":" + join(ports));
        }
        return rendered;
    }

    /** Compute stores exactly one of several nextHop fields; this reports which and what. */
    private static Map<String, Object> nextHop(Map<String, Object> route) {
        Map<String, Object> hop = new LinkedHashMap<>();
        for (String field : List.of("nextHopGateway", "nextHopInstance", "nextHopIp", "nextHopVpnTunnel",
                "nextHopIlb", "nextHopPeering", "nextHopNetwork")) {
            Object value = route.get(field);
            if (value != null) {
                hop.put("type", field);
                // An IP next hop is a literal address; the others are self-links.
                hop.put("value", "nextHopIp".equals(field) ? value : ComputeClient.lastSegment(value));
                return hop;
            }
        }
        hop.put("type", "unknown");
        hop.put("value", null);
        return hop;
    }

    private static boolean loggingEnabled(Map<String, Object> firewall) {
        Map<String, Object> logging = child(firewall, "logConfig");
        return logging != null && Boolean.TRUE.equals(logging.get("enable"));
    }

    private static List<String> shortNames(List<?> links) {
        List<String> names = new ArrayList<>(links.size());
        for (Object link : links) {
            names.add(ComputeClient.lastSegment(link));
        }
        return names;
    }

    private static String join(List<Object> values) {
        List<String> text = new ArrayList<>(values.size());
        for (Object value : values) {
            text.add(String.valueOf(value));
        }
        return String.join(",", text);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> child(Map<String, Object> parent, String key) {
        if (parent == null) {
            return null;
        }
        return parent.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> children(Map<String, Object> parent, String key) {
        if (parent == null || !(parent.get(key) instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private static List<Object> listOf(Map<String, Object> parent, String key) {
        return asList(parent == null ? null : parent.get(key));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }
}
