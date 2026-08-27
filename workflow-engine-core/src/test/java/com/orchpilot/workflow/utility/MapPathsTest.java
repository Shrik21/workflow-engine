package com.orchpilot.workflow.utility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapPathsTest {

    private static Map<String, Object> sample() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "Priya");
        customer.put("tier", "PREMIUM");
        root.put("customer", customer);
        root.put("amount", 15000);
        root.put("items", List.of(Map.of("sku", "A-1"), Map.of("sku", "B-2")));
        root.put("matrix", List.of(List.of("r0c0", "r0c1")));
        root.put("nullValue", null);
        return root;
    }

    @Test
    @DisplayName("reads nested map values by dotted path")
    void readsNestedValues() {
        assertEquals("Priya", MapPaths.find(sample(), "customer.name").orElse(null));
        assertEquals(15000, MapPaths.find(sample(), "amount").orElse(null));
    }

    @Test
    @DisplayName("reads list elements by subscript, including chained subscripts")
    void readsListElements() {
        assertEquals("A-1", MapPaths.find(sample(), "items[0].sku").orElse(null));
        assertEquals("B-2", MapPaths.find(sample(), "items[1].sku").orElse(null));
        assertEquals("r0c1", MapPaths.find(sample(), "matrix[0][1]").orElse(null));
    }

    @Test
    @DisplayName("returns empty for missing paths, out-of-range indexes and null values")
    void returnsEmptyForMisses() {
        assertTrue(MapPaths.find(sample(), "customer.missing").isEmpty());
        assertTrue(MapPaths.find(sample(), "missing.deep.path").isEmpty());
        assertTrue(MapPaths.find(sample(), "items[9].sku").isEmpty());
        assertTrue(MapPaths.find(sample(), "amount.nested").isEmpty());
        assertTrue(MapPaths.find(sample(), "nullValue").isEmpty());
        assertTrue(MapPaths.find(sample(), "").isEmpty());
        assertTrue(MapPaths.find(null, "a").isEmpty());
    }

    @Test
    @DisplayName("single-quoted segments allow keys that contain dots")
    void quotedSegmentsEscapeDots() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("order.id", "ORD-1");
        root.put("workflow", inner);

        assertEquals("ORD-1", MapPaths.find(root, "workflow.'order.id'").orElse(null));
    }

    @Test
    @DisplayName("writes create intermediate maps")
    void writesCreateIntermediateMaps() {
        Map<String, Object> root = new LinkedHashMap<>();

        MapPaths.put(root, "workflow.approval.status", "APPROVED");

        assertEquals("APPROVED", MapPaths.find(root, "workflow.approval.status").orElse(null));
    }

    @Test
    @DisplayName("writes refuse to traverse a non-map value instead of silently replacing it")
    void writesRefuseToOverwriteScalars() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("amount", 10);

        assertThrows(IllegalArgumentException.class, () -> MapPaths.put(root, "amount.nested", 1));
    }

    @Test
    @DisplayName("writes reject list subscripts, because growing a list silently hides mapping mistakes")
    void writesRejectSubscripts() {
        Map<String, Object> root = new LinkedHashMap<>();

        assertThrows(IllegalArgumentException.class, () -> MapPaths.put(root, "items[0]", "x"));
        assertThrows(IllegalArgumentException.class, () -> MapPaths.put(root, "", "x"));
    }

    @Test
    @DisplayName("remove deletes a nested value and reports whether anything was removed")
    void removeDeletesNestedValues() {
        Map<String, Object> root = sample();

        assertTrue(MapPaths.remove(root, "customer.name"));
        assertFalse(MapPaths.remove(root, "customer.name"));
        assertTrue(MapPaths.find(root, "customer.name").isEmpty());
    }

    @Test
    @DisplayName("deepCopy isolates nested maps and lists from the original")
    void deepCopyIsolatesNestedStructures() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("a", 1);
        root.put("nested", nested);
        List<Object> list = new ArrayList<>();
        list.add(new LinkedHashMap<>(Map.of("x", 1)));
        root.put("list", list);

        Map<String, Object> copy = MapPaths.deepCopy(root);
        nested.put("b", 2);
        list.add("appended");

        assertTrue(MapPaths.find(copy, "nested.b").isEmpty(), "the copy must not see later mutations");
        assertEquals(1, ((List<?>) copy.get("list")).size());
    }

    @Test
    @DisplayName("split keeps subscripts attached to their segment")
    void splitKeepsSubscripts() {
        assertEquals(List.of("response", "items[0]", "sku"), MapPaths.split("response.items[0].sku"));
        assertEquals(List.of("a"), MapPaths.split("a"));
    }
}
