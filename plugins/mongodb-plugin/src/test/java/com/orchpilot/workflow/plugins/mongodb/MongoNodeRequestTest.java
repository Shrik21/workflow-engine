package com.orchpilot.workflow.plugins.mongodb;

import com.orchpilot.workflow.plugins.mongodb.support.TestExecution;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading a node's request, and refusing the ones that would go wrong quietly. */
class MongoNodeRequestTest {

    private static Map<String, Object> node(String operation, Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("operation", operation);
        values.put("host", "mongo.internal");
        values.put("database", "customers");
        values.put("collection", "people");
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }

    private static MongoNodeRequest read(Map<String, Object> values) {
        return read(values, Map.of());
    }

    private static MongoNodeRequest read(Map<String, Object> values, Map<String, Object> settings) {
        TestExecution execution = TestExecution.with(values).settings(settings).build();
        MongoConnectionSettings connection = MongoConnectionSettings.from(execution.configuration(),
                execution::resolve, execution.secrets());
        return MongoNodeRequest.from(execution.configuration(), execution.settings(), execution::resolve,
                connection);
    }

    @Nested
    @DisplayName("The operation")
    class Operation {

        @Test
        @DisplayName("an unknown one is refused, with the list of real ones")
        void unknown() {
            PluginConfigurationException failure = assertThrows(PluginConfigurationException.class,
                    () -> read(node("FIND_EVERYTHING")));

            assertTrue(failure.getMessage().contains("FIND_ONE"));
        }

        @Test
        @DisplayName("a missing one is refused rather than guessed at")
        void missing() {
            Map<String, Object> values = node("");
            // Defaulting would mean the node ran *something* against a database. Nothing sensible to guess.
            assertThrows(PluginConfigurationException.class, () -> read(values));
        }

        @Test
        @DisplayName("names are read loosely")
        void loose() {
            assertEquals(MongoOperation.FIND_MANY, read(node("find many")).operation());
            assertEquals(MongoOperation.FIND_MANY, read(node("find-many")).operation());
        }
    }

    @Nested
    @DisplayName("Updates and replacements")
    class Updates {

        @Test
        @DisplayName("an update with operators is accepted")
        void withOperators() {
            MongoNodeRequest request = read(node("UPDATE_ONE",
                    "filter", Map.of("_id", "1"),
                    "update", Map.of("$set", Map.of("status", "ACTIVE"))));

            assertTrue(request.validate().isEmpty());
        }

        @Test
        @DisplayName("a plain document as an update is refused, because it would replace")
        void plainDocumentRefused() {
            // The shell accepts this and silently replaces the document, losing every field not mentioned.
            MongoNodeRequest request = read(node("UPDATE_ONE",
                    "filter", Map.of("_id", "1"),
                    "update", Map.of("status", "ACTIVE")));

            assertTrue(request.validate().stream()
                    .anyMatch(problem -> problem.contains("would replace the matched document entirely")));
        }

        @Test
        @DisplayName("operators as a replacement are refused, pointing at Update One")
        void operatorsAsReplacementRefused() {
            MongoNodeRequest request = read(node("REPLACE_ONE",
                    "filter", Map.of("_id", "1"),
                    "documents", List.of(Map.of("$set", Map.of("status", "ACTIVE")))));

            assertTrue(request.validate().stream()
                    .anyMatch(problem -> problem.contains("Use Update One")));
        }
    }

    @Nested
    @DisplayName("Pipelines")
    class Pipelines {

        @Test
        @DisplayName("a well-formed pipeline passes")
        void valid() {
            MongoNodeRequest request = read(node("AGGREGATE", "pipeline",
                    List.of(Map.of("$match", Map.of("status", "ACTIVE")),
                            Map.of("$group", Map.of("_id", "$department")))));

            assertTrue(request.validate().isEmpty());
        }

        @Test
        @DisplayName("a stage missing its dollar is caught before the server sees it")
        void missingDollar() {
            MongoNodeRequest request = read(node("AGGREGATE",
                    "pipeline", List.of(Map.of("match", Map.of("status", "ACTIVE")))));

            assertTrue(request.validate().stream()
                    .anyMatch(problem -> problem.contains("Stages start with $")));
        }

        @Test
        @DisplayName("a stage with two operators is caught, and named by its position")
        void twoOperators() {
            MongoNodeRequest request = read(node("AGGREGATE", "pipeline",
                    List.of(Map.of("$match", Map.of()), Map.of("$sort", Map.of(), "$limit", 10))));

            assertTrue(request.validate().stream()
                    .anyMatch(problem -> problem.contains("stage 2")));
        }

        @Test
        @DisplayName("an aggregation with no pipeline is refused")
        void empty() {
            assertFalse(read(node("AGGREGATE")).validate().isEmpty());
        }
    }

    @Nested
    @DisplayName("Paging and limits")
    class Limits {

        @Test
        @DisplayName("a page number becomes a skip")
        void paging() {
            MongoNodeRequest request = read(node("FIND_MANY", "page", 3, "pageSize", 25));

            assertEquals(50, request.skip());
            assertEquals(25, request.effectiveLimit());
        }

        @Test
        @DisplayName("skip and limit work directly")
        void skipAndLimit() {
            MongoNodeRequest request = read(node("FIND_MANY", "skip", 10, "limit", 5));

            assertEquals(10, request.skip());
            assertEquals(5, request.effectiveLimit());
        }

        @Test
        @DisplayName("a read with no limit still has one")
        void alwaysBounded() {
            // find({}) against forty million documents is a valid query whose result does not fit in a heap.
            assertEquals(1_000, read(node("FIND_MANY")).effectiveLimit());
        }

        @Test
        @DisplayName("an operator cannot ask for more than the installation's ceiling")
        void ceiling() {
            MongoNodeRequest request = read(node("FIND_MANY", "limit", 999_999, "maxDocuments", 999_999),
                    Map.of("maxDocumentsCeiling", 500));

            assertEquals(500, request.effectiveLimit());
        }

        @Test
        @DisplayName("an administrator can lower the default")
        void configurableDefault() {
            assertEquals(50, read(node("FIND_MANY"), Map.of("maxDocumentsDefault", 50)).effectiveLimit());
        }

        @Test
        @DisplayName("the server time limit is capped the same way")
        void timeLimit() {
            MongoNodeRequest request = read(node("FIND_MANY", "maxTimeMillis", 600_000),
                    Map.of("maxTimeMillisCeiling", 60_000));

            assertEquals(60_000, request.maxTimeMillis());
        }
    }

    @Nested
    @DisplayName("The output variable")
    class OutputVariable {

        @Test
        @DisplayName("it defaults to 'result'")
        void defaulted() {
            assertEquals("result", read(node("FIND_ONE")).outputVariable());
        }

        @Test
        @DisplayName("a chosen name is used")
        void chosen() {
            assertEquals("customerResult",
                    read(node("FIND_ONE", "outputVariable", "customerResult")).outputVariable());
        }

        @Test
        @DisplayName("a name with a dot in it is refused, because nobody could read the result back")
        void refusesDots() {
            // ${a.b.insertedId} leaves nobody able to say which part was the variable.
            PluginConfigurationException failure = assertThrows(PluginConfigurationException.class,
                    () -> read(node("FIND_ONE", "outputVariable", "mongo.result")));

            assertTrue(failure.getMessage().contains("customerResult"));
        }
    }

    @Test
    @DisplayName("a request names what ran and where, never the filter")
    void safeToString() {
        String described = read(node("FIND_MANY", "filter", Map.of("email", "sam@example.com"))).toString();

        assertEquals("find_many customers.people", described);
        assertFalse(described.contains("sam@example.com"));
    }

    @Test
    @DisplayName("every problem is reported at once")
    void reportsEverythingTogether() {
        Map<String, Object> values = node("INSERT_MANY");
        values.remove("collection");
        values.remove("database");

        // No database, no collection, no documents: three runs to find three problems is a poor way to work.
        assertTrue(read(values).validate().size() >= 3);
    }
}
