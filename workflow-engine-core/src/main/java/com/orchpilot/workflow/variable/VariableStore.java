package com.orchpilot.workflow.variable;

import com.orchpilot.workflow.utility.MapPaths;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The variables of one execution, scoped and thread-safe.
 *
 * <p>Reads and writes are guarded by a read/write lock. A single execution is driven by one thread at
 * a time, but the store is also read by the heartbeat writer and by any plugin that holds a
 * {@code VariableView}, so unsynchronised access would be a data race on a {@code LinkedHashMap}.
 *
 * <p>Snapshots are deep copies. Handing out the live map would let a plugin mutate persisted state
 * without going through a node's declared outputs, which would make execution history a lie.
 */
public class VariableStore {

    /** Precedence used to resolve an unqualified name such as {@code amount}. */
    private static final List<VariableScope> LOOKUP_ORDER = List.of(
            VariableScope.WORKFLOW, VariableScope.INPUT, VariableScope.OUTPUT, VariableScope.SYSTEM);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, Object> root = new LinkedHashMap<>();

    private VariableStore() {
        for (VariableScope scope : VariableScope.values()) {
            root.put(scope.key(), new LinkedHashMap<String, Object>());
        }
    }

    /**
     * @return an empty store with every scope present
     */
    public static VariableStore create() {
        return new VariableStore();
    }

    /**
     * Rehydrates a store from a persisted snapshot. Unknown top-level keys are preserved so that a
     * document written by a newer engine version is not silently truncated by an older one.
     *
     * @param snapshot previously persisted variable map, may be {@code null}
     * @return a store backed by a deep copy of the snapshot
     */
    @SuppressWarnings("unchecked")
    public static VariableStore fromSnapshot(Map<String, Object> snapshot) {
        VariableStore store = new VariableStore();
        if (snapshot == null) {
            return store;
        }
        snapshot.forEach((key, value) -> {
            if (value instanceof Map) {
                store.root.put(key, MapPaths.deepCopy((Map<String, Object>) value));
            } else {
                store.root.put(key, value);
            }
        });
        for (VariableScope scope : VariableScope.values()) {
            store.root.computeIfAbsent(scope.key(), k -> new LinkedHashMap<String, Object>());
        }
        return store;
    }

    /**
     * Reads a variable.
     *
     * <p>A path beginning with a scope name is resolved inside that scope. An unqualified path is
     * looked up in {@code workflow}, {@code input}, {@code output}, {@code system} order, so a
     * decision expression can say {@code amount > 1000} without naming a scope.
     *
     * @param path dotted variable path
     * @return the value, or empty when absent
     */
    public Optional<Object> find(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            List<String> segments = MapPaths.split(path);
            Optional<VariableScope> scope = VariableScope.fromKey(segments.get(0));
            if (scope.isPresent()) {
                return MapPaths.find(root, path);
            }
            for (VariableScope candidate : LOOKUP_ORDER) {
                Optional<Object> found = MapPaths.find(root, candidate.key() + "." + path);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Writes a variable.
     *
     * <p>An unqualified path writes to {@link VariableScope#WORKFLOW}, which is what an output mapping
     * of {@code "approved"} means. Writing to a read-only scope is rejected rather than ignored.
     *
     * @param path  dotted variable path, optionally scope-qualified
     * @param value value to store
     * @throws IllegalArgumentException when the target scope is read-only
     */
    public void set(String path, Object value) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Variable path must not be blank");
        }
        lock.writeLock().lock();
        try {
            List<String> segments = MapPaths.split(path);
            Optional<VariableScope> scope = VariableScope.fromKey(segments.get(0));
            if (scope.isPresent()) {
                if (scope.get().isReadOnly()) {
                    throw new IllegalArgumentException(
                            "Scope '" + scope.get().key() + "' is read-only; cannot write '" + path + "'");
                }
                MapPaths.put(root, path, value);
            } else {
                MapPaths.put(root, VariableScope.WORKFLOW.key() + "." + path, value);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Bulk write into a scope, bypassing the read-only check. Used by the engine to seed
     * {@code input} and {@code system}, which nodes may not write themselves.
     *
     * @param scope  target scope
     * @param values values to merge
     */
    public void seed(VariableScope scope, Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            scopeMap(scope).putAll(MapPaths.deepCopy(values));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Publishes a node's outputs under {@code node.<nodeId>}.
     *
     * <h2>An output key may not contain a dot</h2>
     *
     * This store is persisted as a MongoDB document, and Spring Data refuses a map key containing a dot. A
     * plugin publishing {@code result.insertedId} as a literal key therefore throws while the <em>execution
     * is being saved</em> — after the node has already done whatever it does. The visible symptom is a
     * workflow stuck in RUNNING at whatever node last persisted cleanly, with the side effect performed and
     * nothing recorded to say so, which is a long way from the cause.
     *
     * <p>Rejected here instead, where the key can be named and blamed on the node that produced it. A plugin
     * wanting {@code result.insertedId} publishes a nested {@code result} object: an output mapping resolves a
     * dotted name into a structure, so nothing is lost.
     *
     * @param nodeId  node that produced the outputs
     * @param outputs values to publish; {@code null} clears nothing
     * @throws IllegalArgumentException when an output key contains a dot
     */
    public void putNodeOutputs(String nodeId, Map<String, Object> outputs) {
        if (nodeId == null || outputs == null || outputs.isEmpty()) {
            return;
        }
        for (String key : outputs.keySet()) {
            if (key != null && key.indexOf('.') >= 0) {
                throw new IllegalArgumentException("Node '" + nodeId + "' published the output key '" + key
                        + "', which contains a dot. Output keys become field names in the execution document, "
                        + "where dots are not allowed. Publish a nested object instead — {\""
                        + key.substring(0, key.indexOf('.')) + "\": {\""
                        + key.substring(key.indexOf('.') + 1) + "\": …}} — which an output mapping of '" + key
                        + "' still reads.");
            }
        }
        lock.writeLock().lock();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> nodeScope = (Map<String, Object>) root.get(VariableScope.NODE.key());
            @SuppressWarnings("unchecked")
            Map<String, Object> forNode = (Map<String, Object>) nodeScope
                    .computeIfAbsent(nodeId, k -> new LinkedHashMap<String, Object>());
            forNode.putAll(MapPaths.deepCopy(outputs));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * @param scope scope to read
     * @return a deep copy of the scope's contents
     */
    public Map<String, Object> scopeSnapshot(VariableScope scope) {
        lock.readLock().lock();
        try {
            return MapPaths.deepCopy(scopeMap(scope));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * @return a deep copy of every scope, suitable for persistence
     */
    public Map<String, Object> snapshot() {
        lock.readLock().lock();
        try {
            return MapPaths.deepCopy(root);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Flattened view for expression evaluation.
     *
     * <p>Scope maps are addressable by name, and the contents of {@code system}, {@code output},
     * {@code input} and {@code workflow} are also promoted to the top level in increasing precedence,
     * so both {@code workflow.amount} and {@code amount} resolve. A workflow variable whose name
     * collides with a scope name loses; naming a variable {@code input} is a mistake worth making
     * visible rather than silently honouring.
     *
     * @return a fresh map safe to hand to the expression evaluator
     */
    public Map<String, Object> expressionRoot() {
        lock.readLock().lock();
        try {
            Map<String, Object> flattened = new LinkedHashMap<>();
            promote(flattened, VariableScope.SYSTEM);
            promote(flattened, VariableScope.OUTPUT);
            promote(flattened, VariableScope.INPUT);
            promote(flattened, VariableScope.WORKFLOW);
            for (VariableScope scope : VariableScope.values()) {
                flattened.put(scope.key(), MapPaths.deepCopy(scopeMap(scope)));
            }
            return flattened;
        } finally {
            lock.readLock().unlock();
        }
    }

    private void promote(Map<String, Object> target, VariableScope scope) {
        target.putAll(MapPaths.deepCopy(scopeMap(scope)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scopeMap(VariableScope scope) {
        return (Map<String, Object>) root.computeIfAbsent(scope.key(), k -> new LinkedHashMap<String, Object>());
    }
}
