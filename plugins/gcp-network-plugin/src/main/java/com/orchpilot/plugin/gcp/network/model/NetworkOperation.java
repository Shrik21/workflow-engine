package com.orchpilot.plugin.gcp.network.model;

/**
 * Every operation this plugin offers, with the risk and capability that go with it.
 *
 * <h2>One node in the designer, one node type per operation underneath</h2>
 *
 * The palette shows a single <em>GCP Network</em> row and the property panel offers this list as an Operation
 * dropdown — which is the "one plugin, one node" the specification asks for, and what an author sees.
 *
 * <p>Underneath, each operation is its own node type, because two things the specification also asks for are
 * per-node-type in this engine and cannot be expressed any other way:
 *
 * <ul>
 *   <li><b>Risk.</b> {@code destructive} is a flag on a node type, and the approval policy reads it. One node
 *       type for all thirty-two operations would force a single answer for both <em>List VPCs</em> and
 *       <em>Delete VPC</em> — either every read needs approval, or no deletion does.</li>
 *   <li><b>AI tools.</b> The agent is offered one tool per node type. Collapsing to one would give it a single
 *       {@code gcp-network} tool with a union of every field, instead of the {@code gcp.subnet.create} /
 *       {@code gcp.firewall.delete} catalogue the specification lists.</li>
 * </ul>
 *
 * <p>So the split is invisible to the author and load-bearing for safety.
 */
public enum NetworkOperation {

    // ================================================================ VPC
    CREATE_VPC("GCP_NET_CREATE_VPC", "Create VPC", "gcp.network.create", Resource.VPC,
            "Creates a VPC network. Custom mode unless auto-subnets is explicitly turned on.",
            Risk.HIGH, "GCP_NETWORK_CREATE"),
    GET_VPC("GCP_NET_GET_VPC", "Get VPC", "gcp.network.get", Resource.VPC,
            "Reads one VPC network.", Risk.READ, "GCP_NETWORK_READ"),
    LIST_VPCS("GCP_NET_LIST_VPCS", "List VPCs", "gcp.network.list", Resource.VPC,
            "Lists the VPC networks in a project.", Risk.READ, "GCP_NETWORK_READ"),
    UPDATE_VPC("GCP_NET_UPDATE_VPC", "Update VPC", "gcp.network.update", Resource.VPC,
            "Changes the routing mode or description of a VPC. Other fields are immutable in GCP.",
            Risk.HIGH, "GCP_NETWORK_UPDATE"),
    DELETE_VPC("GCP_NET_DELETE_VPC", "Delete VPC", "gcp.network.delete", Resource.VPC,
            "Deletes a VPC. Refuses while subnets, firewall rules, routes, routers or peerings still "
                    + "reference it — dependents are never removed for you.",
            Risk.CRITICAL, "GCP_NETWORK_DELETE"),

    // ================================================================ subnet
    CREATE_SUBNET("GCP_NET_CREATE_SUBNET", "Create Subnet", "gcp.subnet.create", Resource.SUBNET,
            "Creates a subnet in a region, with an optional set of secondary ranges.",
            Risk.MEDIUM, "GCP_SUBNET_CREATE"),
    GET_SUBNET("GCP_NET_GET_SUBNET", "Get Subnet", "gcp.subnet.get", Resource.SUBNET,
            "Reads one subnet.", Risk.READ, "GCP_SUBNET_READ"),
    LIST_SUBNETS("GCP_NET_LIST_SUBNETS", "List Subnets", "gcp.subnet.list", Resource.SUBNET,
            "Lists subnets in a region, or across every region.", Risk.READ, "GCP_SUBNET_READ"),
    UPDATE_SUBNET("GCP_NET_UPDATE_SUBNET", "Update Subnet", "gcp.subnet.update", Resource.SUBNET,
            "Changes private Google access, flow logs, secondary ranges or expands the primary range.",
            Risk.HIGH, "GCP_SUBNET_UPDATE"),
    DELETE_SUBNET("GCP_NET_DELETE_SUBNET", "Delete Subnet", "gcp.subnet.delete", Resource.SUBNET,
            "Deletes a subnet. Refuses while instances or NAT configurations still use it.",
            Risk.CRITICAL, "GCP_SUBNET_DELETE"),

    // ================================================================ firewall
    CREATE_FIREWALL("GCP_NET_CREATE_FIREWALL", "Create Firewall Rule", "gcp.firewall.create",
            Resource.FIREWALL,
            "Creates a firewall rule. A rule opening SSH or RDP to the whole internet is reported as a "
                    + "finding and requires confirmation.",
            Risk.HIGH, "GCP_FIREWALL_CREATE"),
    GET_FIREWALL("GCP_NET_GET_FIREWALL", "Get Firewall Rule", "gcp.firewall.get", Resource.FIREWALL,
            "Reads one firewall rule.", Risk.READ, "GCP_FIREWALL_READ"),
    LIST_FIREWALLS("GCP_NET_LIST_FIREWALLS", "List Firewall Rules", "gcp.firewall.list", Resource.FIREWALL,
            "Lists firewall rules, optionally only those attached to one network.",
            Risk.READ, "GCP_FIREWALL_READ"),
    UPDATE_FIREWALL("GCP_NET_UPDATE_FIREWALL", "Update Firewall Rule", "gcp.firewall.update",
            Resource.FIREWALL, "Changes a firewall rule. Re-assessed for exposure like a creation.",
            Risk.HIGH, "GCP_FIREWALL_UPDATE"),
    DELETE_FIREWALL("GCP_NET_DELETE_FIREWALL", "Delete Firewall Rule", "gcp.firewall.delete",
            Resource.FIREWALL,
            "Deletes a firewall rule. Removing a deny rule can open traffic that was previously blocked.",
            Risk.CRITICAL, "GCP_FIREWALL_DELETE"),

    // ================================================================ routes
    CREATE_ROUTE("GCP_NET_CREATE_ROUTE", "Create Route", "gcp.route.create", Resource.ROUTE,
            "Creates a static route with one next hop.", Risk.HIGH, "GCP_ROUTE_CREATE"),
    GET_ROUTE("GCP_NET_GET_ROUTE", "Get Route", "gcp.route.get", Resource.ROUTE,
            "Reads one route.", Risk.READ, "GCP_ROUTE_READ"),
    LIST_ROUTES("GCP_NET_LIST_ROUTES", "List Routes", "gcp.route.list", Resource.ROUTE,
            "Lists routes, optionally only those of one network.", Risk.READ, "GCP_ROUTE_READ"),
    DELETE_ROUTE("GCP_NET_DELETE_ROUTE", "Delete Route", "gcp.route.delete", Resource.ROUTE,
            "Deletes a route. Removing a default route can black-hole egress.",
            Risk.CRITICAL, "GCP_ROUTE_DELETE"),

    // ================================================================ Cloud Router
    CREATE_ROUTER("GCP_NET_CREATE_ROUTER", "Create Cloud Router", "gcp.router.create", Resource.ROUTER,
            "Creates a Cloud Router in a region, optionally with a BGP ASN.",
            Risk.MEDIUM, "GCP_ROUTER_CREATE"),
    GET_ROUTER("GCP_NET_GET_ROUTER", "Get Cloud Router", "gcp.router.get", Resource.ROUTER,
            "Reads one Cloud Router, including any NAT configurations on it.",
            Risk.READ, "GCP_ROUTER_READ"),
    LIST_ROUTERS("GCP_NET_LIST_ROUTERS", "List Cloud Routers", "gcp.router.list", Resource.ROUTER,
            "Lists Cloud Routers in a region.", Risk.READ, "GCP_ROUTER_READ"),
    DELETE_ROUTER("GCP_NET_DELETE_ROUTER", "Delete Cloud Router", "gcp.router.delete", Resource.ROUTER,
            "Deletes a Cloud Router and every NAT configuration on it.",
            Risk.CRITICAL, "GCP_ROUTER_DELETE"),

    // ================================================================ Cloud NAT
    CREATE_NAT("GCP_NET_CREATE_NAT", "Create Cloud NAT", "gcp.nat.create", Resource.NAT,
            "Adds a NAT configuration to a Cloud Router, giving private instances outbound access.",
            Risk.HIGH, "GCP_NAT_CREATE"),
    GET_NAT("GCP_NET_GET_NAT", "Get Cloud NAT", "gcp.nat.get", Resource.NAT,
            "Reads one NAT configuration from its router.", Risk.READ, "GCP_NAT_READ"),
    UPDATE_NAT("GCP_NET_UPDATE_NAT", "Update Cloud NAT", "gcp.nat.update", Resource.NAT,
            "Changes a NAT configuration in place.", Risk.HIGH, "GCP_NAT_UPDATE"),
    DELETE_NAT("GCP_NET_DELETE_NAT", "Delete Cloud NAT", "gcp.nat.delete", Resource.NAT,
            "Removes a NAT configuration. Instances behind it lose outbound internet access.",
            Risk.CRITICAL, "GCP_NAT_DELETE"),

    // ================================================================ peering
    CREATE_PEERING("GCP_NET_CREATE_PEERING", "Create VPC Peering", "gcp.peering.create", Resource.PEERING,
            "Peers this VPC with another. Both sides must peer before traffic flows.",
            Risk.HIGH, "GCP_PEERING_CREATE"),
    GET_PEERING("GCP_NET_GET_PEERING", "Get VPC Peering", "gcp.peering.get", Resource.PEERING,
            "Reads one peering from its network.", Risk.READ, "GCP_PEERING_READ"),
    LIST_PEERINGS("GCP_NET_LIST_PEERINGS", "List VPC Peerings", "gcp.peering.list", Resource.PEERING,
            "Lists a network's peerings and their states.", Risk.READ, "GCP_PEERING_READ"),
    DELETE_PEERING("GCP_NET_DELETE_PEERING", "Delete VPC Peering", "gcp.peering.delete", Resource.PEERING,
            "Removes a peering. Traffic between the two networks stops immediately.",
            Risk.CRITICAL, "GCP_PEERING_DELETE"),

    // ================================================================ inspection
    INSPECT_NETWORK("GCP_NET_INSPECT", "Inspect Network", "gcp.network.inspect", Resource.VPC,
            "Reads a VPC and everything attached to it — subnets, firewall rules, routes, routers, NAT, "
                    + "peerings and the instances using it — as one structured result.",
            Risk.READ, "GCP_NETWORK_INSPECT");

    /** Which GCP resource an operation acts on; drives which fields its form shows. */
    public enum Resource {
        VPC, SUBNET, FIREWALL, ROUTE, ROUTER, NAT, PEERING
    }

    /**
     * Consequence if the operation runs when it should not.
     *
     * <p>Five levels as the specification asks. The engine's node contract carries a boolean, so
     * {@link #destructive()} folds them onto it — but the finer grade is published in the manifest, because
     * "delete a firewall rule" and "delete a VPC" both need a human and are not the same conversation.
     */
    public enum Risk {
        READ,
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    private final String nodeType;
    private final String displayName;
    private final String capability;
    private final Resource resource;
    private final String description;
    private final Risk risk;
    private final String permission;

    NetworkOperation(String nodeType, String displayName, String capability, Resource resource,
                     String description, Risk risk, String permission) {
        this.nodeType = nodeType;
        this.displayName = displayName;
        this.capability = capability;
        this.resource = resource;
        this.description = description;
        this.risk = risk;
        this.permission = permission;
    }

    public String nodeType() {
        return nodeType;
    }

    public String displayName() {
        return displayName;
    }

    public String capability() {
        return capability;
    }

    public Resource resource() {
        return resource;
    }

    public String description() {
        return description;
    }

    public Risk risk() {
        return risk;
    }

    /** The permission name published for this operation; see the README on how it is enforced. */
    public String permission() {
        return permission;
    }

    /** @return whether a supervised agent must have this approved, and the node carries a confirmation gate */
    public boolean destructive() {
        return risk == Risk.HIGH || risk == Risk.CRITICAL;
    }

    /** @return whether the operation only reads, so it is safe to repeat after a retry */
    public boolean readOnly() {
        return risk == Risk.READ;
    }

    /** @return whether the operation is regional rather than global, so its form needs a region */
    public boolean regional() {
        return switch (resource) {
            case SUBNET, ROUTER, NAT -> true;
            default -> this == LIST_SUBNETS;
        };
    }

    public static NetworkOperation forNodeType(String nodeType) {
        for (NetworkOperation operation : values()) {
            if (operation.nodeType.equals(nodeType)) {
                return operation;
            }
        }
        return null;
    }
}
