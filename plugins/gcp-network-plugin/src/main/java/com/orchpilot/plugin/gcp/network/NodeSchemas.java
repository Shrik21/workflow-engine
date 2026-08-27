package com.orchpilot.plugin.gcp.network;

import com.orchpilot.plugin.gcp.network.model.NetworkOperation;
import com.orchpilot.workflow.sdk.schema.SchemaBuilder;

import java.util.List;
import java.util.Map;

/**
 * Each operation's configuration schema.
 *
 * <h2>The form is the schema</h2>
 *
 * The designer renders a node's form from what the plugin publishes here, so choosing "Create Subnet" shows a
 * region and a CIDR while "Create Firewall Rule" shows directions and ports — with no plugin-specific Angular
 * anywhere. Advanced settings that are correct as they stand are marked so, and render behind a toggle rather
 * than in front of the three fields an author is there to fill in.
 *
 * <h2>The credential is always a reference</h2>
 *
 * Every schema starts with a {@code secretRef}. What a workflow stores is the <em>name</em> of a secret; the
 * service-account key is resolved at execution through the engine's audited secret provider and never enters
 * the workflow document, its variables, its output or the AI Agent's context.
 */
final class NodeSchemas {

    private NodeSchemas() {
    }

    static Map<String, Object> forOperation(NetworkOperation operation) {
        SchemaBuilder schema = SchemaBuilder.object()
                .secretRef("connection", "GCP connection", true)
                .withDescription("connection",
                        "The NAME of a secret holding the service-account JSON key (prefix gcp.). Never the "
                                + "key itself, and never a token.")
                .string("project", "Project ID", true)
                .withDescription("project", "Supports variables, e.g. ${gcpProject}.");

        if (operation.regional()) {
            boolean optional = operation == NetworkOperation.LIST_SUBNETS
                    || operation == NetworkOperation.LIST_ROUTERS;
            schema.string("region", "Region", !optional);
            if (optional) {
                schema.withDescription("region", "Leave blank to look across every region.");
            }
        }

        switch (operation) {
            // ---------------------------------------------------------- VPC
            case CREATE_VPC -> schema
                    .string("vpcName", "VPC name", true)
                    .string("description", "Description", false)
                    .select("routingMode", "Routing mode", List.of("REGIONAL", "GLOBAL"), false)
                    .withDefault("routingMode", "REGIONAL")
                    .describeOptions("routingMode", Map.of(
                            "REGIONAL", "Cloud Routers learn routes only from their own region.",
                            "GLOBAL", "Routes are shared across every region. Needed for multi-region VPNs."))
                    .bool("autoCreateSubnets", "Auto-create subnets", false)
                    .withDefault("autoCreateSubnets", false)
                    .withDescription("autoCreateSubnets",
                            "Off gives a custom-mode VPC, which is almost always what you want. On makes GCP "
                                    + "create a subnet in every region immediately.");

            case GET_VPC, INSPECT_NETWORK -> schema.string("vpcName", "VPC name", true);

            case LIST_VPCS -> schema.string("filter", "Filter", false)
                    .withDescription("filter", "GCP filter syntax, e.g. name eq \"prod-.*\".")
                    .advanced("filter");

            case UPDATE_VPC -> {
                schema.string("vpcName", "VPC name", true)
                        .select("routingMode", "Routing mode", List.of("", "REGIONAL", "GLOBAL"), false)
                        .string("description", "Description", false)
                        .withDescription("description",
                                "Routing mode and description are the only fields GCP lets you change after "
                                        + "a VPC is created.");
                confirmation(schema, "Changing routing mode affects how every Cloud Router in the VPC learns "
                        + "routes.");
            }

            case DELETE_VPC -> {
                schema.string("vpcName", "VPC name", true);
                confirmation(schema, "Deleting a VPC is irreversible. The node refuses while subnets, "
                        + "firewall rules, custom routes, routers, peerings or instances still use it — "
                        + "dependents are never removed for you.");
            }

            // ---------------------------------------------------------- subnet
            case CREATE_SUBNET -> schema
                    .string("vpcName", "VPC", true)
                    .string("subnetName", "Subnet name", true)
                    .string("ipCidrRange", "IP CIDR range", true)
                    .withDescription("ipCidrRange",
                            "For example 10.10.0.0/24. Validated before anything is sent to GCP.")
                    .string("description", "Description", false)
                    .bool("privateGoogleAccess", "Private Google access", false)
                    .withDescription("privateGoogleAccess",
                            "Lets instances without external addresses reach Google APIs.")
                    .bool("flowLogs", "VPC flow logs", false)
                    .map("secondaryIpRanges", "Secondary ranges", false)
                    .withDescription("secondaryIpRanges",
                            "An array of {rangeName, ipCidrRange}, for GKE pods and services.")
                    .string("flowLogInterval", "Flow log interval", false)
                    .withDefault("flowLogInterval", "INTERVAL_5_SEC")
                    .advanced("flowLogInterval");

            case GET_SUBNET -> schema.string("subnetName", "Subnet name", true);

            case LIST_SUBNETS -> schema
                    .string("vpcName", "VPC", false)
                    .withDescription("vpcName", "Leave blank to list every subnet in the project.")
                    .string("filter", "Filter", false).advanced("filter");

            case UPDATE_SUBNET -> {
                schema.string("subnetName", "Subnet name", true)
                        .bool("privateGoogleAccess", "Private Google access", false)
                        .bool("flowLogs", "VPC flow logs", false)
                        .string("ipCidrRange", "Expand primary range to", false)
                        .withDescription("ipCidrRange",
                                "A subnet range can only be expanded, never shrunk.")
                        .map("secondaryIpRanges", "Secondary ranges", false)
                        .string("description", "Description", false);
                confirmation(schema, "Changing a subnet affects every instance in it.");
            }

            case DELETE_SUBNET -> {
                schema.string("subnetName", "Subnet name", true);
                confirmation(schema, "Refused while any VM instance still uses the subnet.");
            }

            // ---------------------------------------------------------- firewall
            case CREATE_FIREWALL, UPDATE_FIREWALL -> {
                schema.string("firewallName", "Rule name", true);
                if (operation == NetworkOperation.CREATE_FIREWALL) {
                    schema.string("network", "Network", true);
                }
                schema.select("direction", "Direction", List.of("INGRESS", "EGRESS"), false)
                        .withDefault("direction", "INGRESS")
                        .describeOptions("direction", Map.of(
                                "INGRESS", "Traffic arriving at the instances this rule targets.",
                                "EGRESS", "Traffic leaving them."))
                        .select("action", "Action", List.of("ALLOW", "DENY"), false)
                        .withDefault("action", "ALLOW")
                        .string("protocol", "Protocol", false).withDefault("protocol", "tcp")
                        .withDescription("protocol", "tcp, udp, icmp, esp, ah, sctp, or all.")
                        .string("ports", "Ports", false)
                        .withDescription("ports",
                                "Comma-separated, and a range is allowed: 22,443,8000-8080.")
                        .map("rules", "Protocol rules", false)
                        .withDescription("rules",
                                "For several protocols at once: an array of {protocol, ports[]}. Overrides "
                                        + "the two fields above.")
                        .string("sourceRanges", "Source ranges", false)
                        .withDescription("sourceRanges",
                                "Comma-separated CIDRs. 0.0.0.0/0 on an administrative port raises a "
                                        + "security finding and needs confirmation.")
                        .string("destinationRanges", "Destination ranges", false)
                        .string("sourceTags", "Source tags", false)
                        .string("targetTags", "Target tags", false)
                        .string("sourceServiceAccounts", "Source service accounts", false)
                        .string("targetServiceAccounts", "Target service accounts", false)
                        .integer("priority", "Priority", false).withDefault("priority", 1000)
                        .withDescription("priority", "0 is highest, 65535 lowest.")
                        .bool("logging", "Firewall logging", false)
                        .bool("disabled", "Create disabled", false)
                        .string("description", "Description", false)
                        .advanced("sourceServiceAccounts", "targetServiceAccounts", "disabled");
                confirmation(schema, "Firewall changes take effect immediately across the whole network.");
            }

            case GET_FIREWALL -> schema.string("firewallName", "Rule name", true);

            case LIST_FIREWALLS -> schema
                    .string("network", "Network", false)
                    .withDescription("network", "Leave blank to list every rule in the project.")
                    .string("filter", "Filter", false).advanced("filter");

            case DELETE_FIREWALL -> {
                schema.string("firewallName", "Rule name", true);
                confirmation(schema, "Deleting a DENY rule can allow traffic that was previously blocked.");
            }

            // ---------------------------------------------------------- routes
            case CREATE_ROUTE -> {
                schema.string("routeName", "Route name", true)
                        .string("network", "Network", true)
                        .string("destRange", "Destination range", true)
                        .withDescription("destRange", "For example 10.20.0.0/16 or 0.0.0.0/0.")
                        .select("nextHopType", "Next hop type",
                                List.of("GATEWAY", "IP", "INSTANCE", "VPN_TUNNEL", "ILB"), false)
                        .withDefault("nextHopType", "GATEWAY")
                        .describeOptions("nextHopType", Map.of(
                                "GATEWAY", "The default internet gateway.",
                                "IP", "An address inside the VPC, such as a NAT appliance.",
                                "INSTANCE", "A specific VM, which must have IP forwarding enabled.",
                                "VPN_TUNNEL", "A Cloud VPN tunnel.",
                                "ILB", "An internal load balancer forwarding rule."))
                        .string("nextHopValue", "Next hop", false)
                        .withDescription("nextHopValue",
                                "Required for every type except GATEWAY.")
                        .string("nextHopZone", "Next hop zone", false)
                        .withDescription("nextHopZone", "Only for an INSTANCE next hop given by bare name.")
                        .integer("priority", "Priority", false).withDefault("priority", 1000)
                        .string("tags", "Instance tags", false)
                        .withDescription("tags", "Comma-separated. Blank applies the route to every instance.")
                        .string("description", "Description", false)
                        .advanced("nextHopZone", "tags");
                confirmation(schema, "A route change can redirect or black-hole traffic for the whole VPC.");
            }

            case GET_ROUTE -> schema.string("routeName", "Route name", true);

            case LIST_ROUTES -> schema
                    .string("network", "Network", false)
                    .string("filter", "Filter", false).advanced("filter");

            case DELETE_ROUTE -> {
                schema.string("routeName", "Route name", true);
                confirmation(schema, "Removing a default route can black-hole egress traffic.");
            }

            // ---------------------------------------------------------- Cloud Router
            case CREATE_ROUTER -> schema
                    .string("routerName", "Router name", true)
                    .string("network", "Network", true)
                    .integer("bgpAsn", "BGP ASN", false)
                    .withDescription("bgpAsn",
                            "A private ASN, 64512-65534. Leave blank for a router used only for Cloud NAT.")
                    .string("bgpAdvertiseMode", "BGP advertise mode", false)
                    .withDefault("bgpAdvertiseMode", "DEFAULT")
                    .string("description", "Description", false)
                    .advanced("bgpAdvertiseMode");

            case GET_ROUTER -> schema.string("routerName", "Router name", true);

            case LIST_ROUTERS -> schema.string("filter", "Filter", false).advanced("filter");

            case DELETE_ROUTER -> {
                schema.string("routerName", "Router name", true)
                        .bool("deleteNatConfigurations", "Also delete its NAT configurations", false)
                        .withDescription("deleteNatConfigurations",
                                "Off refuses while the router still carries NAT, so instances do not lose "
                                        + "outbound access unexpectedly.");
                confirmation(schema, "Deleting a router removes every NAT configuration on it.");
            }

            // ---------------------------------------------------------- Cloud NAT
            case CREATE_NAT, UPDATE_NAT -> {
                schema.string("routerName", "Cloud Router", true)
                        .withDescription("routerName",
                                "NAT is a configuration on a router, not a resource of its own.")
                        .string("natName", "NAT name", true)
                        .select("natIpAllocateOption", "NAT IP allocation",
                                List.of("AUTO_ONLY", "MANUAL_ONLY"), false)
                        .withDefault("natIpAllocateOption", "AUTO_ONLY")
                        .describeOptions("natIpAllocateOption", Map.of(
                                "AUTO_ONLY", "GCP allocates and scales the external addresses.",
                                "MANUAL_ONLY", "Uses only the reserved addresses you name below."))
                        .string("natIps", "NAT IPs", false)
                        .withDescription("natIps",
                                "Comma-separated reserved address names. Required for MANUAL_ONLY.")
                        .select("sourceSubnetworkIpRangesToNat", "Subnet selection",
                                List.of("ALL_SUBNETWORKS_ALL_IP_RANGES",
                                        "ALL_SUBNETWORKS_ALL_PRIMARY_IP_RANGES", "LIST_OF_SUBNETWORKS"),
                                false)
                        .withDefault("sourceSubnetworkIpRangesToNat", "ALL_SUBNETWORKS_ALL_IP_RANGES")
                        .string("subnetworks", "Subnets", false)
                        .withDescription("subnetworks",
                                "Comma-separated. Required for LIST_OF_SUBNETWORKS, ignored otherwise.")
                        .bool("logging", "NAT logging", false)
                        .string("logFilter", "Log filter", false).withDefault("logFilter", "ALL")
                        .advanced("logFilter");
                if (operation == NetworkOperation.UPDATE_NAT) {
                    confirmation(schema, "Changing NAT affects outbound connectivity for every instance "
                            + "behind it.");
                }
            }

            case GET_NAT -> schema.string("routerName", "Cloud Router", true)
                    .string("natName", "NAT name", true);

            case DELETE_NAT -> {
                schema.string("routerName", "Cloud Router", true).string("natName", "NAT name", true);
                confirmation(schema, "Instances behind this NAT lose outbound internet access immediately.");
            }

            // ---------------------------------------------------------- peering
            case CREATE_PEERING -> {
                schema.string("network", "This network", true)
                        .string("peeringName", "Peering name", true)
                        .string("peerNetwork", "Peer network", true)
                        .string("peerProject", "Peer project", false)
                        .withDescription("peerProject",
                                "Required when the peer network is given as a bare name.")
                        .bool("importCustomRoutes", "Import custom routes", false)
                        .bool("exportCustomRoutes", "Export custom routes", false)
                        .bool("importSubnetRoutes", "Import subnet routes with public IP", false)
                        .bool("exportSubnetRoutes", "Export subnet routes with public IP", false)
                        .advanced("importSubnetRoutes", "exportSubnetRoutes");
                confirmation(schema, "Peering joins two networks; both sides must peer before traffic flows.");
            }

            case GET_PEERING -> schema.string("network", "Network", true)
                    .string("peeringName", "Peering name", true);

            case LIST_PEERINGS -> schema.string("network", "Network", true);

            case DELETE_PEERING -> {
                schema.string("network", "Network", true).string("peeringName", "Peering name", true);
                confirmation(schema, "Traffic between the two networks stops immediately.");
            }
        }

        if (!operation.readOnly()) {
            schema.bool("waitForCompletion", "Wait for completion", false)
                    .withDefault("waitForCompletion", true)
                    .withDescription("waitForCompletion",
                            "On, the node returns once GCP has finished, so the next node can rely on the "
                                    + "resource existing. Off returns as soon as the request is accepted.")
                    .integer("operationTimeoutSeconds", "Operation timeout (seconds)", false)
                    .withDefault("operationTimeoutSeconds", 300)
                    .advanced("operationTimeoutSeconds");
        }

        schema.string("outputVariable", "Output variable", false)
                .withDescription("outputVariable",
                        "Name to store the result under, for use as ${name} in a later node.");
        return schema.build();
    }

    /**
     * The confirmation gate carried by every risky operation.
     *
     * <p>Separate from the node's {@code destructive} flag on purpose. That flag makes a supervised AI Agent
     * seek approval; this applies to <em>any</em> caller, so a hand-built workflow cannot delete a VPC because
     * a variable resolved to something unexpected. Two gates, because either alone has a bypass.
     */
    private static void confirmation(SchemaBuilder schema, String why) {
        schema.bool("requireConfirmation", "Require confirmation", false)
                .withDefault("requireConfirmation", true)
                .bool("confirmed", "Confirmed", false)
                .withDescription("confirmed", why
                        + " Must be true to proceed while confirmation is required — set it from an approval "
                        + "or human-task node upstream.");
    }
}
