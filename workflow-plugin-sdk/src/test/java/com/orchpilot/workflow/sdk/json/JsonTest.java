package com.orchpilot.workflow.sdk.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    @DisplayName("writes objects with insertion order preserved")
    void writesObjectsInInsertionOrder() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", 1);
        value.put("a", "two");
        value.put("c", true);
        value.put("d", null);

        assertEquals("{\"b\":1,\"a\":\"two\",\"c\":true,\"d\":null}", Json.write(value));
    }

    @Test
    @DisplayName("escapes control characters, quotes and backslashes")
    void escapesSpecialCharacters() {
        String written = Json.write(Map.of("k", "line1\nline2\t\"quoted\"\\end"));
        assertTrue(written.contains("\\n"), written);
        assertTrue(written.contains("\\t"), written);
        assertTrue(written.contains("\\\""), written);
        assertTrue(written.contains("\\\\"), written);
    }

    @Test
    @DisplayName("round-trips nested structures")
    void roundTripsNestedStructures() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("id", 42L);
        original.put("ratio", 1.5);
        original.put("tags", List.of("a", "b"));
        original.put("nested", Map.of("deep", List.of(Map.of("x", 1L))));

        Map<String, Object> parsed = Json.parseObject(Json.write(original));

        assertEquals(42L, parsed.get("id"));
        assertEquals(1.5, parsed.get("ratio"));
        assertEquals(List.of("a", "b"), parsed.get("tags"));
        assertInstanceOf(Map.class, parsed.get("nested"));
    }

    @Test
    @DisplayName("maps integral numbers to Long and fractional to Double")
    void mapsNumberTypes() {
        Map<String, Object> parsed = Json.parseObject("{\"i\":7,\"d\":7.5,\"e\":1e2,\"neg\":-3}");

        assertInstanceOf(Long.class, parsed.get("i"));
        assertInstanceOf(Double.class, parsed.get("d"));
        assertInstanceOf(Double.class, parsed.get("e"));
        assertEquals(-3L, parsed.get("neg"));
    }

    @Test
    @DisplayName("decodes string escapes including unicode")
    void decodesEscapes() {
        Map<String, Object> parsed = Json.parseObject(
                "{\"s\":\"a\\nb\\tc\\\"d\\\\e\\u0041\\/f\"}");
        assertEquals("a\nb\tc\"d\\eA/f", parsed.get("s"));
    }

    @Test
    @DisplayName("parses empty objects and arrays")
    void parsesEmptyContainers() {
        assertTrue(Json.parseObject("{}").isEmpty());
        assertTrue(Json.parseArray("[]").isEmpty());
        assertTrue(Json.parseObject("  ").isEmpty());
        assertNull(Json.parse(null));
    }

    @Test
    @DisplayName("rejects malformed input rather than guessing")
    void rejectsMalformedInput() {
        assertThrows(JsonException.class, () -> Json.parse("{"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\" 1}"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1} trailing"));
        assertThrows(JsonException.class, () -> Json.parse("[1,2"));
        assertThrows(JsonException.class, () -> Json.parse("\"unterminated"));
        assertThrows(JsonException.class, () -> Json.parseObject("[1]"));
        assertThrows(JsonException.class, () -> Json.parseArray("{}"));
    }

    @Test
    @DisplayName("refuses input nested beyond the depth limit instead of overflowing the stack")
    void refusesExcessiveNesting() {
        String deep = "[".repeat(200) + "]".repeat(200);
        assertThrows(JsonException.class, () -> Json.parse(deep));
    }

    @Test
    @DisplayName("writes non-finite numbers as null so output stays valid JSON")
    void writesNonFiniteNumbersAsNull() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("a", Double.NaN);
        value.put("b", Double.POSITIVE_INFINITY);

        assertEquals("{\"a\":null,\"b\":null}", Json.write(value));
    }

    @Test
    @DisplayName("pretty output parses back to the same value")
    void prettyOutputIsValid() {
        Map<String, Object> original = Map.of("a", List.of(1L, 2L), "b", Map.of("c", "d"));
        assertEquals(original, Json.parseObject(Json.writePretty(original)));
    }
}
