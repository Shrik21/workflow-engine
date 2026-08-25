package com.orchpilot.workflow.model;

import java.util.Set;

/**
 * The node types the engine itself implements.
 *
 * <p>Deliberately a set of string constants rather than an enum: node types are an open set. Every
 * other type in the system arrives from a plugin at runtime, and an enum would force a core rebuild
 * to add one. Nothing in the engine switches on these values; they exist so built-in executors and
 * the validator can name themselves without magic strings.
 */
public final class NodeTypes {

    /** Single entry point of a workflow. */
    public static final String START = "START";

    /** Collects user input, optionally parking the execution until it arrives. */
    public static final String FORM = "FORM";

    /** Chooses one of several outgoing branches by evaluating expressions. */
    public static final String DECISION = "DECISION";

    /** Terminates the workflow and records its outputs. */
    public static final String END = "END";

    /** Runs an AI model (through a provider-independent runtime) and publishes its output as variables. */
    public static final String AI_AGENT = "AI_AGENT";

    /**
     * Marker type for a node backed by a plugin. The concrete behaviour comes from
     * {@code pluginId} and {@code pluginVersion} on the node. A node may equally declare the
     * plugin's own node type directly, e.g. {@code SENDGRID_EMAIL}; both resolve through the
     * registry.
     */
    public static final String PLUGIN = "PLUGIN";

    /** Every type implemented by the engine itself. */
    public static final Set<String> BUILT_IN = Set.of(START, FORM, DECISION, END, AI_AGENT);

    private NodeTypes() {
    }

    /**
     * @param nodeType node type to test
     * @return {@code true} when the engine implements this type itself
     */
    public static boolean isBuiltIn(String nodeType) {
        return nodeType != null && BUILT_IN.contains(nodeType);
    }
}
