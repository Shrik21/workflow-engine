package com.orchpilot.workflow.plugins.mongodb;

import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning configured JSON into a query.
 *
 * <p>Two things here fail silently if they are wrong, which is why most of these tests exist: a value that
 * keeps the wrong type produces a query that runs and matches nothing, and a value containing a quote
 * produces a parse error on the one customer whose name has an apostrophe in it.
 */
class BsonJsonTest {

    private static UnaryOperator<String> resolverOf(Map<String, String> variables) {
        return template -> {
            String result = template == null ? "" : template;
            for (Map.Entry<String, String> variable : variables.entrySet()) {
                result = result.replace("${" + variable.getKey() + "}", variable.getValue());
            }
            return result;
        };
    }

    private static final UnaryOperator<String> IDENTITY = value -> value;

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }

    @Nested
    @DisplayName("Variables")
    class Variables {

        @Test
        @DisplayName("a placeholder inside a filter is resolved")
        void resolvesInFilter() {
            Document filter = BsonJson.document(map("email", "${form.email}"), "filter",
                    resolverOf(Map.of("form.email", "sam@example.com")));

            assertEquals("sam@example.com", filter.getString("email"));
        }

        @Test
        @DisplayName("a value containing a quote survives, because substitution happens in the tree")
        void quotesSurvive() {
            // Substituting into JSON text and parsing afterwards makes this a syntax error at execution, on
            // input that depends on the data. It is the reason interpolation is not a string replace.
            Document filter = BsonJson.document(map("lastName", "${customer.lastName}"), "filter",
                    resolverOf(Map.of("customer.lastName", "O\"Brien \\ the second")));

            assertEquals("O\"Brien \\ the second", filter.getString("lastName"));
        }

        @Test
        @DisplayName("placeholders nested at any depth are resolved")
        void resolvesDeeply() {
            Document filter = BsonJson.document(
                    map("$and", List.of(map("status", "${status}"), map("age", map("$gte", "${minAge}")))),
                    "filter", resolverOf(Map.of("status", "ACTIVE", "minAge", "18")));

            @SuppressWarnings("unchecked")
            List<Document> conditions = (List<Document>) filter.get("$and");
            assertEquals("ACTIVE", conditions.get(0).getString("status"));
            assertEquals(18, conditions.get(1).get("age", Document.class).getInteger("$gte"));
        }

        @Test
        @DisplayName("a field name can come from a variable")
        void resolvesKeys() {
            Document projection = BsonJson.document(map("${column}", 1), "projection",
                    resolverOf(Map.of("column", "email")));

            assertEquals(1, projection.getInteger("email"));
        }
    }

    @Nested
    @DisplayName("Types")
    class Types {

        @Test
        @DisplayName("a whole-value placeholder that resolves to a number becomes a number")
        void coercesNumbers() {
            // age: "30" matches nothing where age is numeric, and the query is valid, so nothing says so.
            Document filter = BsonJson.document(map("age", "${form.age}"), "filter",
                    resolverOf(Map.of("form.age", "30")));

            assertEquals(30, filter.get("age"));
            assertInstanceOf(Integer.class, filter.get("age"));
        }

        @Test
        @DisplayName("a large number stays exact rather than becoming a double")
        void coercesLongs() {
            Document filter = BsonJson.document(map("epoch", "${ts}"), "filter",
                    resolverOf(Map.of("ts", "1786949850123")));

            assertEquals(1_786_949_850_123L, filter.get("epoch"));
        }

        @Test
        @DisplayName("true and false become booleans")
        void coercesBooleans() {
            Document filter = BsonJson.document(map("active", "${flag}"), "filter",
                    resolverOf(Map.of("flag", "true")));

            assertEquals(Boolean.TRUE, filter.get("active"));
        }

        @Test
        @DisplayName("a placeholder with text around it stays text")
        void keepsInterpolatedText() {
            Document filter = BsonJson.document(map("label", "order ${id}"), "filter",
                    resolverOf(Map.of("id", "1001")));

            assertEquals("order 1001", filter.getString("label"));
        }

        @Test
        @DisplayName("a leading zero stays a string, because it is an identifier and not a number")
        void keepsLeadingZeros() {
            // JSON has no number with a leading zero, so treating this as text is the correct reading as
            // well as the useful one: postcodes and account numbers survive.
            Document filter = BsonJson.document(map("postcode", "${form.postcode}"), "filter",
                    resolverOf(Map.of("form.postcode", "01234")));

            assertEquals("01234", filter.getString("postcode"));
        }

        @Test
        @DisplayName("a value that looks like an ObjectId stays a string")
        void doesNotGuessObjectIds() {
            // Guessing would turn an ordinary 24-character identifier into a type the collection does not
            // hold, and the query would then match nothing. The explicit $oid form is right there.
            Document filter = BsonJson.document(map("reference", "${ref}"), "filter",
                    resolverOf(Map.of("ref", "507f1f77bcf86cd799439011")));

            assertEquals("507f1f77bcf86cd799439011", filter.getString("reference"));
        }

        @Test
        @DisplayName("numbers already in the configuration are left alone")
        void leavesLiteralsAlone() {
            Document filter = BsonJson.document(map("age", map("$gte", 18)), "filter", IDENTITY);

            assertEquals(18, filter.get("age", Document.class).getInteger("$gte"));
        }
    }

    @Nested
    @DisplayName("Extended JSON")
    class ExtendedJson {

        @Test
        @DisplayName("$oid becomes an ObjectId, with the variable resolved first")
        void objectId() {
            Document filter = BsonJson.document(map("_id", map("$oid", "${customer.id}")), "filter",
                    resolverOf(Map.of("customer.id", "507f1f77bcf86cd799439011")));

            assertEquals(new ObjectId("507f1f77bcf86cd799439011"), filter.get("_id"));
        }

        @Test
        @DisplayName("a malformed $oid names the field it is in")
        void malformedObjectId() {
            PluginConfigurationException failure = assertThrows(PluginConfigurationException.class,
                    () -> BsonJson.document(map("_id", map("$oid", "not-an-id")), "filter", IDENTITY));

            assertTrue(failure.getMessage().contains("filter"));
            assertTrue(failure.getMessage().contains("24 hexadecimal"));
        }

        @Test
        @DisplayName("$date reads ISO-8601 and epoch milliseconds")
        void dates() {
            Document iso = BsonJson.document(map("createdAt", map("$date", "2026-08-17T09:30:00Z")),
                    "filter", IDENTITY);
            assertEquals(java.util.Date.from(java.time.Instant.parse("2026-08-17T09:30:00Z")),
                    iso.get("createdAt"));

            Document epoch = BsonJson.document(map("createdAt", map("$date", "1786949850123")),
                    "filter", IDENTITY);
            assertEquals(new java.util.Date(1_786_949_850_123L), epoch.get("createdAt"));
        }

        @Test
        @DisplayName("$numberDecimal keeps a money value exact")
        void decimals() {
            Document document = BsonJson.document(map("total", map("$numberDecimal", "${order.total}")),
                    "documents", resolverOf(Map.of("order.total", "19.99")));

            assertEquals(Decimal128.parse("19.99"), document.get("total"));
        }
    }

    @Nested
    @DisplayName("Shapes")
    class Shapes {

        @Test
        @DisplayName("a pipeline is read as a list of stages")
        void pipeline() {
            List<Document> pipeline = BsonJson.documents(
                    List.of(map("$match", map("status", "${status}")), map("$count", "total")),
                    "pipeline", resolverOf(Map.of("status", "ACTIVE")));

            assertEquals(2, pipeline.size());
            assertEquals("ACTIVE", pipeline.get(0).get("$match", Document.class).getString("status"));
        }

        @Test
        @DisplayName("one document is accepted where a list is expected")
        void singleDocument() {
            assertEquals(1, BsonJson.documents(map("name", "Sam"), "documents", IDENTITY).size());
        }

        @Test
        @DisplayName("JSON text is accepted as well as a structure")
        void text() {
            Document filter = BsonJson.document("{\"status\": \"ACTIVE\"}", "filter", IDENTITY);
            assertEquals("ACTIVE", filter.getString("status"));

            List<Document> pipeline = BsonJson.documents("[{\"$match\": {\"a\": 1}}]", "pipeline", IDENTITY);
            assertEquals(1, pipeline.size());
        }

        @Test
        @DisplayName("nothing configured is an empty document, not an error")
        void absent() {
            assertTrue(BsonJson.document(null, "filter", IDENTITY).isEmpty());
            assertTrue(BsonJson.documents(null, "pipeline", IDENTITY).isEmpty());
        }

        @Test
        @DisplayName("malformed JSON names the field")
        void malformed() {
            PluginConfigurationException failure = assertThrows(PluginConfigurationException.class,
                    () -> BsonJson.document("{not json", "projection", IDENTITY));

            assertTrue(failure.getMessage().contains("projection"));
        }

        @Test
        @DisplayName("a list where a document was expected says so")
        void wrongShape() {
            PluginConfigurationException failure = assertThrows(PluginConfigurationException.class,
                    () -> BsonJson.document(List.of(map("a", 1)), "filter", IDENTITY));

            assertTrue(failure.getMessage().contains("must be a JSON object"));
        }
    }

    @Nested
    @DisplayName("Text interpolation")
    class TextInterpolation {

        @Test
        @DisplayName("an inserted value is escaped, so it cannot break the JSON around it")
        void escapes() {
            String json = BsonJson.interpolateText("{\"name\": \"${customer.name}\"}",
                    value -> "O\"Brien");

            assertEquals("{\"name\": \"O\\\"Brien\"}", json);
            // The point of escaping: what comes out still parses.
            assertEquals("O\"Brien", Document.parse(json).getString("name"));
        }

        @Test
        @DisplayName("newlines and control characters are escaped too")
        void escapesControlCharacters() {
            String json = BsonJson.interpolateText("{\"note\": \"${note}\"}", value -> "line\none\ttab");

            assertEquals("line\none\ttab", Document.parse(json).getString("note"));
        }
    }
}
