package com.orchpilot.workflow.variable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableStoreTest {

    @Test
    @DisplayName("an unqualified write lands in the workflow scope")
    void unqualifiedWriteGoesToWorkflowScope() {
        VariableStore store = VariableStore.create();

        store.set("approved", true);

        assertEquals(true, store.find("workflow.approved").orElse(null));
        assertEquals(true, store.find("approved").orElse(null));
    }

    @Test
    @DisplayName("input and system are read-only to nodes")
    void readOnlyScopesRejectWrites() {
        VariableStore store = VariableStore.create();

        assertThrows(IllegalArgumentException.class, () -> store.set("input.employeeId", "E-1"));
        assertThrows(IllegalArgumentException.class, () -> store.set("system.executionId", "hacked"));
    }

    @Test
    @DisplayName("seed bypasses the read-only check, which is how the engine populates input and system")
    void seedPopulatesReadOnlyScopes() {
        VariableStore store = VariableStore.create();

        store.seed(VariableScope.INPUT, Map.of("employeeId", "E-1"));

        assertEquals("E-1", store.find("input.employeeId").orElse(null));
    }

    @Test
    @DisplayName("bare lookups prefer workflow over input")
    void workflowScopeWinsOverInput() {
        VariableStore store = VariableStore.create();
        store.seed(VariableScope.INPUT, Map.of("region", "from-input"));
        store.set("region", "from-workflow");

        assertEquals("from-workflow", store.find("region").orElse(null));
        assertEquals("from-input", store.find("input.region").orElse(null));
    }

    @Test
    @DisplayName("node outputs are addressable by node id")
    void nodeOutputsAreScopedByNodeId() {
        VariableStore store = VariableStore.create();

        store.putNodeOutputs("form-1", Map.of("approved", true));
        store.putNodeOutputs("call-1", Map.of("approved", false));

        assertEquals(true, store.find("node.form-1.approved").orElse(null));
        assertEquals(false, store.find("node.call-1.approved").orElse(null));
    }

    @Test
    @DisplayName("an output key containing a dot is refused, naming the node that produced it")
    void dottedOutputKeysAreRefused() {
        VariableStore store = VariableStore.create();

        /*
         * This store is persisted as a MongoDB document, where a field name may not contain a dot. Accepting
         * the key here moves the failure to the moment the execution is saved — after the node has already
         * done its work — and the visible symptom is a workflow stuck in RUNNING at whatever node last
         * persisted cleanly, with the side effect performed and nothing recorded. Two plugins were written
         * this way before it was noticed.
         */
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> store.putNodeOutputs("mongo-1", Map.of("result.insertedId", "abc")));

        assertTrue(refusal.getMessage().contains("mongo-1"));
        assertTrue(refusal.getMessage().contains("result.insertedId"));
        // The message has to say what to do instead, since the fix is in the plugin.
        assertTrue(refusal.getMessage().contains("nested object"));
    }

    @Test
    @DisplayName("a nested output is addressable by the same dotted path a flat key would have used")
    void nestedOutputsReadTheSameWay() {
        VariableStore store = VariableStore.create();

        store.putNodeOutputs("mongo-1", Map.of("result", Map.of("insertedId", "abc", "success", true)));

        // Which is why refusing the flat form costs nothing.
        assertEquals("abc", store.find("node.mongo-1.result.insertedId").orElse(null));
    }

    @Test
    @DisplayName("snapshots are deep copies, so a caller cannot mutate stored state through one")
    void snapshotsAreIsolated() {
        VariableStore store = VariableStore.create();
        store.set("nested", new java.util.LinkedHashMap<>(Map.of("a", 1)));

        Map<String, Object> snapshot = store.snapshot();
        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = (Map<String, Object>) snapshot.get("workflow");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) workflow.get("nested");
        nested.put("b", 2);

        assertTrue(store.find("nested.b").isEmpty());
    }

    @Test
    @DisplayName("a snapshot round-trips through fromSnapshot")
    void snapshotRoundTrips() {
        VariableStore original = VariableStore.create();
        original.seed(VariableScope.INPUT, Map.of("employeeId", "E-1"));
        original.set("approved", true);
        original.putNodeOutputs("n1", Map.of("x", 1));

        VariableStore restored = VariableStore.fromSnapshot(original.snapshot());

        assertEquals("E-1", restored.find("input.employeeId").orElse(null));
        assertEquals(true, restored.find("workflow.approved").orElse(null));
        assertEquals(1, restored.find("node.n1.x").orElse(null));
    }

    @Test
    @DisplayName("fromSnapshot tolerates a null or partial snapshot")
    void fromSnapshotTolerantOfMissingScopes() {
        VariableStore store = VariableStore.fromSnapshot(null);
        assertTrue(store.find("anything").isEmpty());

        VariableStore partial = VariableStore.fromSnapshot(Map.of("workflow", Map.of("a", 1)));
        assertEquals(1, partial.find("a").orElse(null));
        assertTrue(partial.scopeSnapshot(VariableScope.NODE).isEmpty());
    }

    @Test
    @DisplayName("the expression root exposes both scoped and promoted names")
    void expressionRootFlattensScopes() {
        VariableStore store = VariableStore.create();
        store.seed(VariableScope.INPUT, Map.of("region", "APAC"));
        store.set("amount", 15000);
        store.seed(VariableScope.SYSTEM, Map.of("executionId", "exec-1"));

        Map<String, Object> root = store.expressionRoot();

        assertEquals(15000, root.get("amount"));
        assertEquals("APAC", root.get("region"));
        assertEquals("exec-1", root.get("executionId"));
        assertTrue(root.containsKey("workflow"));
        assertTrue(root.containsKey("node"));
    }
}
