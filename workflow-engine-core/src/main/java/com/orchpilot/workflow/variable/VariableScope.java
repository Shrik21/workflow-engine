package com.orchpilot.workflow.variable;

import java.util.Optional;

/**
 * The addressable variable scopes of an execution.
 *
 * <p>Scopes exist so that a node cannot accidentally overwrite the execution's input, and so that two
 * nodes publishing an output called {@code status} do not collide. Everything a node writes lands under
 * {@link #NODE} keyed by node id; only explicit output mappings promote a value into {@link #WORKFLOW}.
 */
public enum VariableScope {

    /** Immutable payload the execution was started with. Addressed as {@code input.*}. */
    INPUT("input", true),

    /** Mutable, workflow-wide values. Addressed as {@code workflow.*}. The default write target. */
    WORKFLOW("workflow", false),

    /** Per-node outputs, addressed as {@code node.<nodeId>.<output>}. Written by the engine only. */
    NODE("node", false),

    /** Values an end node promotes into the execution result. Addressed as {@code output.*}. */
    OUTPUT("output", false),

    /** Engine-provided facts such as {@code system.executionId}. Read-only. */
    SYSTEM("system", true);

    private final String key;
    private final boolean readOnly;

    VariableScope(String key, boolean readOnly) {
        this.key = key;
        this.readOnly = readOnly;
    }

    /** @return the top-level key this scope occupies in the variable map */
    public String key() {
        return key;
    }

    /** @return whether nodes are forbidden from writing to this scope */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * @param key candidate scope key
     * @return the matching scope, or empty when {@code key} is not a scope name
     */
    public static Optional<VariableScope> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (VariableScope scope : values()) {
            if (scope.key.equals(key)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }
}
