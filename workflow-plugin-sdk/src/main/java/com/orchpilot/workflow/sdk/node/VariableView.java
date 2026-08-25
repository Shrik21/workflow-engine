package com.orchpilot.workflow.sdk.node;

import java.util.Map;
import java.util.Optional;

/**
 * Read-only view over an execution's variables, addressed by dotted path.
 *
 * <p>Paths are scoped: {@code input.*}, {@code workflow.*}, {@code node.<nodeId>.*} and
 * {@code system.*}. A plugin gets read access only; writes happen through the outputs it returns in
 * its {@link NodeExecutionResult}, which keeps every mutation attributable to a node.
 *
 * @since 1.0.0
 */
public interface VariableView {

    /**
     * @param path dotted variable path, e.g. {@code workflow.orderId}
     * @return the value, or empty when absent or {@code null}
     */
    Optional<Object> find(String path);

    /**
     * @return unmodifiable snapshot of every scope, keyed by scope name
     */
    Map<String, Object> asMap();

    /**
     * @param path dotted variable path
     * @return {@code true} when a non-null value is present
     */
    default boolean has(String path) {
        return find(path).isPresent();
    }

    /**
     * @param path dotted variable path
     * @return the value rendered as text, or {@code null} when absent
     */
    default String getString(String path) {
        return find(path).map(String::valueOf).orElse(null);
    }
}
