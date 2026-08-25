package com.orchpilot.workflow.plugins.gcp;

/**
 * The Compute Engine operations this plugin exposes, each as its own workflow node type.
 *
 * <h2>One operation, one node, one AI tool, one risk level</h2>
 *
 * Modelling every operation as a separate node type is what lets OrchPilot treat them differently where it
 * matters. The AI Agent's tool registry turns each node type into a tool named from {@link #nodeType()} (so
 * {@code GCP_COMPUTE_DELETE_INSTANCE} becomes {@code gcp_compute_delete_instance}), and the {@link #risk()} of each
 * op is carried onto the node definition as {@code destructive} — so a supervised agent gates a Delete while a Get
 * or a List runs freely. A single node with an operation dropdown could not make that distinction.
 */
public enum GcpOperation {

    CREATE("GCP_COMPUTE_CREATE_INSTANCE", "Create GCP Compute Instance",
            "Creates a Compute Engine VM instance. Returns the instance identity and, when configured to wait, "
                    + "its final state.", Risk.MODIFY),
    GET("GCP_COMPUTE_GET_INSTANCE", "Get GCP Compute Instance",
            "Reads one Compute Engine instance: status, machine type, disks, network interfaces and labels.",
            Risk.READ_ONLY),
    LIST("GCP_COMPUTE_LIST_INSTANCES", "List GCP Compute Instances",
            "Lists Compute Engine instances in a zone (or across all zones), with an optional filter.",
            Risk.READ_ONLY),
    START("GCP_COMPUTE_START_INSTANCE", "Start GCP Compute Instance",
            "Starts a stopped Compute Engine instance.", Risk.MODIFY),
    STOP("GCP_COMPUTE_STOP_INSTANCE", "Stop GCP Compute Instance",
            "Stops a running Compute Engine instance.", Risk.MODIFY),
    RESTART("GCP_COMPUTE_RESTART_INSTANCE", "Restart GCP Compute Instance",
            "Gracefully restarts an instance by stopping then starting it, waiting for each step.", Risk.MODIFY),
    RESET("GCP_COMPUTE_RESET_INSTANCE", "Reset GCP Compute Instance",
            "Hard-resets an instance (a power cycle), without a graceful shutdown.", Risk.MODIFY),
    SUSPEND("GCP_COMPUTE_SUSPEND_INSTANCE", "Suspend GCP Compute Instance",
            "Suspends an instance, preserving memory to persistent storage.", Risk.MODIFY),
    RESUME("GCP_COMPUTE_RESUME_INSTANCE", "Resume GCP Compute Instance",
            "Resumes a suspended instance.", Risk.MODIFY),
    DELETE("GCP_COMPUTE_DELETE_INSTANCE", "Delete GCP Compute Instance",
            "Permanently deletes a Compute Engine instance. Destructive and irreversible.", Risk.DESTRUCTIVE);

    /** Sensitivity of an operation, mirrored onto the node's {@code destructive} flag for the AI Agent. */
    public enum Risk {
        READ_ONLY,
        MODIFY,
        DESTRUCTIVE
    }

    private final String nodeType;
    private final String displayName;
    private final String description;
    private final Risk risk;

    GcpOperation(String nodeType, String displayName, String description, Risk risk) {
        this.nodeType = nodeType;
        this.displayName = displayName;
        this.description = description;
        this.risk = risk;
    }

    public String nodeType() {
        return nodeType;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Risk risk() {
        return risk;
    }

    /** @return whether a supervised AI Agent must have this operation approved before it runs */
    public boolean destructive() {
        return risk == Risk.DESTRUCTIVE;
    }

    /** @return the operation for a node type, or null when the type is not one of ours */
    public static GcpOperation forNodeType(String nodeType) {
        for (GcpOperation operation : values()) {
            if (operation.nodeType.equals(nodeType)) {
                return operation;
            }
        }
        return null;
    }
}
