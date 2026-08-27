package com.orchpilot.workflow.plugins.mongodb;

import com.orchpilot.workflow.plugins.mongodb.support.TestExecution;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin against a real MongoDB.
 *
 * <h2>Two ways to get a server, both opt-in</h2>
 *
 * A Testcontainers MongoDB when Docker is available, or an existing server named in
 * {@code WORKFLOW_IT_MONGODB_URI}. The environment variable is checked first and there is deliberately no
 * localhost default: this test writes and drops collections, and a suite that silently connects to whatever
 * MongoDB it finds is one that eventually drops something that mattered. It confines itself to a database
 * named below, and drops only that.
 *
 * <p>Same convention as the engine's own integration tests, for the same reason.
 */
@Tag("integration")
class MongoDbPluginIT {

    /** Scratch, and named so nobody mistakes it for anything. */
    private static final String DATABASE = "mongodb_plugin_it";
    private static final String COLLECTION = "people";

    private static MongoDBContainer container;
    private static String uri;
    private static MongoClient verifier;

    private final MongoDbPlugin plugin = new MongoDbPlugin();

    @BeforeAll
    static void startServer() {
        String configured = System.getenv("WORKFLOW_IT_MONGODB_URI");
        if (configured != null && !configured.isBlank()) {
            uri = configured;
        } else {
            container = new MongoDBContainer("mongo:7.0");
            container.start();
            uri = container.getConnectionString();
        }
        verifier = MongoClients.create(uri);
        verifier.getDatabase(DATABASE).drop();
    }

    @AfterAll
    static void stopServer() {
        if (verifier != null) {
            verifier.getDatabase(DATABASE).drop();
            verifier.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @AfterEach
    void clearCollection() {
        verifier.getDatabase(DATABASE).getCollection(COLLECTION).drop();
        plugin.destroy();
    }

    /** A node pointed at the scratch database. The URI carries no credentials, which is the only kind allowed. */
    private Map<String, Object> node(String operation, Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("operation", operation);
        values.put("connectionUri", uri);
        values.put("database", DATABASE);
        values.put("collection", COLLECTION);
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }

    private NodeExecutionResult run(Map<String, Object> configuration) {
        return run(configuration, Map.of(), Map.of());
    }

    private NodeExecutionResult run(Map<String, Object> configuration, Map<String, String> variables,
                                    Map<String, Object> settings) {
        TestExecution execution = TestExecution.with(configuration)
                .variables(variables)
                .settings(settings)
                .user("operator", "ADMIN")
                .build();
        plugin.initialize(execution);
        return plugin.execute(execution);
    }

    private static Map<String, Object> person(String name, String email, String department, int age) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", name);
        document.put("email", email);
        document.put("department", department);
        document.put("age", age);
        document.put("status", "ACTIVE");
        return document;
    }

    private long stored() {
        return verifier.getDatabase(DATABASE).getCollection(COLLECTION).countDocuments();
    }

    /**
     * Reads a dotted path out of the outputs, the way the engine's {@code VariableMapper} does.
     *
     * <p>Outputs are nested — {@code {"result": {"count": 3}}} — and an output mapping of
     * {@code result.count} resolves into that structure. Asserting through the same path means these tests
     * exercise what a workflow will actually read, and would have caught the flat dotted keys this plugin
     * published at first: those persist as field names containing dots, which MongoDB will not store.
     */
    @SuppressWarnings("unchecked")
    private static Object output(NodeExecutionResult result, String path) {
        Object current = result.outputs();
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(segment);
        }
        return current;
    }

    /** No output key may contain a dot: see {@link #output} and the engine's VariableStore. */
    private static void assertNoDottedKeys(NodeExecutionResult result) {
        for (String key : result.outputs().keySet()) {
            assertFalse(key.contains("."),
                    () -> "output key '" + key + "' contains a dot, which cannot be persisted");
        }
    }

    @Test
    @DisplayName("connects and reports the server version")
    void testConnection() {
        NodeExecutionResult result = run(node("TEST_CONNECTION"));

        assertTrue(result.isSuccess(), () -> result.errorCode() + " " + result.errorMessage());
        assertEquals(Boolean.TRUE, result.outputs().get("connected"));
        assertNotNull(result.outputs().get("serverVersion"));
    }

    @Test
    @DisplayName("inserts one document and publishes its id")
    void insertOne() {
        NodeExecutionResult result = run(node("INSERT_ONE",
                "documents", List.of(person("Sam", "sam@example.com", "IT", 30)),
                "outputVariable", "customer"));

        assertTrue(result.isSuccess(), () -> result.errorMessage());
        assertNotNull(output(result, "customer.insertedId"));
        assertEquals(1, stored());

        // Nested under the chosen name, so one mapping line promotes the whole result.
        assertTrue(result.outputs().get("customer") instanceof Map);
        assertEquals(Boolean.TRUE, result.outputs().get("success"));

        // The keys themselves must be plain. A dotted key persists as a field name containing a dot, which
        // fails while the execution is being saved — after the insert has already happened — and leaves the
        // workflow in RUNNING with the write done and nothing recorded.
        assertNoDottedKeys(result);
    }

    @Test
    @DisplayName("resolves workflow variables into the document it writes")
    void insertWithVariables() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", "${form.firstName} ${form.lastName}");
        document.put("email", "${form.email}");
        document.put("age", "${form.age}");

        NodeExecutionResult result = run(node("INSERT_ONE", "documents", List.of(document)),
                Map.of("form.firstName", "Ada", "form.lastName", "Lovelace",
                        "form.email", "ada@example.com", "form.age", "36"),
                Map.of());

        assertTrue(result.isSuccess(), () -> result.errorMessage());
        Document written = verifier.getDatabase(DATABASE).getCollection(COLLECTION).find().first();
        assertNotNull(written);
        assertEquals("Ada Lovelace", written.getString("name"));
        // A whole-value placeholder that resolved to a number is stored as one, or age comparisons fail.
        assertEquals(36, written.getInteger("age"));
    }

    @Test
    @DisplayName("finds one document and publishes its fields directly")
    void findOne() {
        run(node("INSERT_MANY", "documents", List.of(
                person("Sam", "sam@example.com", "IT", 30),
                person("Ada", "ada@example.com", "IT", 36))));

        NodeExecutionResult result = run(node("FIND_ONE",
                "filter", Map.of("email", "${user.email}"),
                "projection", Map.of("_id", 0, "name", 1, "email", 1),
                "outputVariable", "customer"),
                Map.of("user.email", "ada@example.com"), Map.of());

        assertTrue(result.isSuccess(), () -> result.errorMessage());
        assertEquals(Boolean.TRUE, output(result, "customer.found"));
        // ${customer.name} rather than ${customer.document.name}: the shape somebody expects after asking
        // for one record.
        assertEquals("Ada", output(result, "customer.name"));
    }

    @Test
    @DisplayName("a find that matches nothing succeeds and says so")
    void findOneMissing() {
        NodeExecutionResult result = run(node("FIND_ONE", "filter", Map.of("email", "nobody@example.com")));

        // Not an error: "no such customer" is an ordinary outcome a decision node branches on.
        assertTrue(result.isSuccess());
        assertEquals(Boolean.FALSE, output(result, "result.found"));
    }

    @Test
    @DisplayName("finds many, pages through them, and reports whether more remain")
    void findManyPaged() {
        List<Map<String, Object>> people = List.of(
                person("A", "a@example.com", "IT", 20),
                person("B", "b@example.com", "IT", 21),
                person("C", "c@example.com", "HR", 22),
                person("D", "d@example.com", "HR", 23),
                person("E", "e@example.com", "IT", 24));
        run(node("INSERT_MANY", "documents", people));

        NodeExecutionResult first = run(node("FIND_MANY",
                "filter", Map.of("department", "IT"),
                "sort", Map.of("age", 1),
                "page", 1, "pageSize", 2,
                "includeTotalCount", true));

        assertTrue(first.isSuccess(), () -> first.errorMessage());
        assertEquals(2, output(first, "result.count"));
        assertEquals(Boolean.TRUE, output(first, "result.hasMore"));
        assertEquals(3L, output(first, "result.totalCount"));

        NodeExecutionResult last = run(node("FIND_MANY",
                "filter", Map.of("department", "IT"),
                "sort", Map.of("age", 1),
                "page", 2, "pageSize", 2));
        assertEquals(Boolean.FALSE, output(last, "result.hasMore"));
    }

    @Test
    @DisplayName("a read is bounded by the installation's ceiling however much a node asks for")
    void ceilingApplies() {
        for (int index = 0; index < 12; index++) {
            run(node("INSERT_ONE", "documents",
                    List.of(person("P" + index, index + "@example.com", "IT", 20 + index))));
        }

        NodeExecutionResult result = run(node("FIND_MANY", "limit", 1_000, "maxDocuments", 1_000),
                Map.of(), Map.of("maxDocumentsCeiling", 5));

        assertEquals(5, output(result, "result.count"));
    }

    @Test
    @DisplayName("updates with operators, and reports what it matched")
    void updateOne() {
        run(node("INSERT_ONE", "documents", List.of(person("Sam", "sam@example.com", "IT", 30))));

        NodeExecutionResult result = run(node("UPDATE_ONE",
                "filter", Map.of("email", "sam@example.com"),
                "update", Map.of("$set", Map.of("status", "INACTIVE"), "$inc", Map.of("age", 1))));

        assertTrue(result.isSuccess(), () -> result.errorMessage());
        assertEquals(1L, output(result, "result.matchedCount"));
        assertEquals(1L, output(result, "result.modifiedCount"));

        Document updated = verifier.getDatabase(DATABASE).getCollection(COLLECTION).find().first();
        assertEquals("INACTIVE", updated.getString("status"));
        assertEquals(31, updated.getInteger("age"));
    }

    @Test
    @DisplayName("an upsert inserts when nothing matched, and reports the new id")
    void upsert() {
        NodeExecutionResult result = run(node("UPDATE_ONE",
                "filter", Map.of("email", "new@example.com"),
                "update", Map.of("$set", Map.of("name", "New")),
                "upsert", true));

        assertTrue(result.isSuccess(), () -> result.errorMessage());
        assertNotNull(output(result, "result.upsertedId"));
        assertEquals(1, stored());
    }

    @Test
    @DisplayName("update many is refused without confirmation, and runs with it")
    void updateManyNeedsConfirmation() {
        run(node("INSERT_MANY", "documents", List.of(
                person("A", "a@example.com", "IT", 20),
                person("B", "b@example.com", "IT", 21))));

        NodeExecutionResult refused = run(node("UPDATE_MANY",
                "filter", Map.of("department", "IT"),
                "update", Map.of("$set", Map.of("status", "REVIEWED"))));

        assertTrue(refused.isFailed());
        assertEquals(MongoErrors.CONFIRMATION_REQUIRED, refused.errorCode());

        NodeExecutionResult allowed = run(node("UPDATE_MANY",
                "filter", Map.of("department", "IT"),
                "update", Map.of("$set", Map.of("status", "REVIEWED")),
                "confirmed", true));

        assertTrue(allowed.isSuccess(), () -> allowed.errorMessage());
        assertEquals(2L, output(allowed, "result.modifiedCount"));
    }

    @Test
    @DisplayName("a bulk delete with a filter that resolved to nothing is refused before it runs")
    void emptyFilterIsRefused() {
        run(node("INSERT_MANY", "documents", List.of(
                person("A", "a@example.com", "IT", 20),
                person("B", "b@example.com", "HR", 21))));

        // ${missing.department} resolves to an empty string, and the filter collapses to {}. Without the
        // guard this deletes the collection's contents and reports success.
        NodeExecutionResult result = run(node("DELETE_MANY",
                "filter", Map.of(),
                "confirmed", true));

        assertTrue(result.isFailed());
        assertEquals(MongoErrors.CONFIRMATION_REQUIRED, result.errorCode());
        assertEquals(2, stored(), "nothing should have been deleted");
    }

    @Test
    @DisplayName("deletes what the filter names")
    void deleteMany() {
        run(node("INSERT_MANY", "documents", List.of(
                person("A", "a@example.com", "IT", 20),
                person("B", "b@example.com", "HR", 21),
                person("C", "c@example.com", "IT", 22))));

        NodeExecutionResult result = run(node("DELETE_MANY",
                "filter", Map.of("department", "IT"),
                "confirmed", true));

        assertTrue(result.isSuccess(), () -> result.errorMessage());
        assertEquals(2L, output(result, "result.deletedCount"));
        assertEquals(1, stored());
    }

    @Test
    @DisplayName("replaces a document entirely")
    void replaceOne() {
        run(node("INSERT_ONE", "documents", List.of(person("Sam", "sam@example.com", "IT", 30))));

        NodeExecutionResult result = run(node("REPLACE_ONE",
                "filter", Map.of("email", "sam@example.com"),
                "documents", List.of(Map.of("email", "sam@example.com", "name", "Samantha")),
                "confirmed", true));

        assertTrue(result.isSuccess(), () -> result.errorMessage());
        Document replaced = verifier.getDatabase(DATABASE).getCollection(COLLECTION).find().first();
        assertEquals("Samantha", replaced.getString("name"));
        // Every field the replacement did not mention is gone. That is what makes it a separate operation.
        assertFalse(replaced.containsKey("department"));
    }

    @Test
    @DisplayName("counts and collects distinct values")
    void countAndDistinct() {
        run(node("INSERT_MANY", "documents", List.of(
                person("A", "a@example.com", "IT", 20),
                person("B", "b@example.com", "HR", 21),
                person("C", "c@example.com", "IT", 22))));

        NodeExecutionResult count = run(node("COUNT", "filter", Map.of("department", "IT")));
        assertEquals(2L, output(count, "result.count"));

        NodeExecutionResult distinct = run(node("DISTINCT", "field", "department"));
        assertTrue(distinct.isSuccess(), () -> distinct.errorCode() + " " + distinct.errorMessage());
        @SuppressWarnings("unchecked")
        List<Object> values = (List<Object>) output(distinct, "result.values");
        assertEquals(2, values.size());
        assertTrue(values.contains("IT"));
    }

    @Test
    @DisplayName("runs an aggregation pipeline with variables in it")
    void aggregate() {
        run(node("INSERT_MANY", "documents", List.of(
                person("A", "a@example.com", "IT", 20),
                person("B", "b@example.com", "HR", 21),
                person("C", "c@example.com", "IT", 22))));

        NodeExecutionResult result = run(node("AGGREGATE", "pipeline", List.of(
                        Map.of("$match", Map.of("status", "${filter.status}")),
                        Map.of("$group", Map.of("_id", "$department", "count", Map.of("$sum", 1))),
                        Map.of("$sort", Map.of("count", -1)))),
                Map.of("filter.status", "ACTIVE"), Map.of());

        assertTrue(result.isSuccess(), () -> result.errorMessage());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) output(result, "result.items");
        assertEquals(2, items.size());
        assertEquals("IT", items.get(0).get("_id"));
        assertEquals(2, items.get(0).get("count"));
    }

    @Test
    @DisplayName("runs a batch of mixed writes")
    void bulkWrite() {
        run(node("INSERT_ONE", "documents", List.of(person("Sam", "sam@example.com", "IT", 30))));

        NodeExecutionResult result = run(node("BULK_WRITE", "confirmed", true, "documents", List.of(
                Map.of("insertOne", Map.of("document", person("New", "new@example.com", "HR", 25))),
                Map.of("updateOne", Map.of(
                        "filter", Map.of("email", "sam@example.com"),
                        "update", Map.of("$set", Map.of("status", "REVIEWED")))),
                Map.of("deleteOne", Map.of("filter", Map.of("email", "absent@example.com"))))));

        assertTrue(result.isSuccess(), () -> result.errorMessage());
        assertEquals(1, output(result, "result.insertedCount"));
        assertEquals(1, output(result, "result.modifiedCount"));
        assertEquals(0, output(result, "result.deletedCount"));
    }

    @Test
    @DisplayName("creates, lists and drops an index")
    void indexes() {
        run(node("INSERT_ONE", "documents", List.of(person("Sam", "sam@example.com", "IT", 30))));

        NodeExecutionResult created = run(node("CREATE_INDEX",
                "keys", Map.of("email", 1),
                "options", Map.of("unique", true, "name", "email_unique")));
        assertTrue(created.isSuccess(), () -> created.errorMessage());
        assertEquals("email_unique", output(created, "result.indexName"));

        NodeExecutionResult listed = run(node("LIST_INDEXES"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> indexes = (List<Map<String, Object>>) output(listed, "result.indexes");
        assertTrue(indexes.stream().anyMatch(index -> "email_unique".equals(index.get("name"))));

        NodeExecutionResult dropped = run(node("DROP_INDEX", "field", "email_unique", "confirmed", true));
        assertTrue(dropped.isSuccess(), () -> dropped.errorMessage());
    }

    @Test
    @DisplayName("a unique index violation fails as a duplicate key and is not retried")
    void duplicateKey() {
        run(node("CREATE_INDEX", "keys", Map.of("email", 1), "options", Map.of("unique", true)));
        run(node("INSERT_ONE", "documents", List.of(person("Sam", "sam@example.com", "IT", 30))));

        NodeExecutionResult result = run(node("INSERT_ONE",
                "documents", List.of(person("Other", "sam@example.com", "HR", 40))));

        assertTrue(result.isFailed());
        assertEquals(MongoErrors.DUPLICATE_KEY, result.errorCode());
        // Retrying produces the same duplicate.
        assertFalse(result.retryable());
    }

    @Test
    @DisplayName("lists and drops collections")
    void collections() {
        run(node("INSERT_ONE", "documents", List.of(person("Sam", "sam@example.com", "IT", 30))));

        NodeExecutionResult listed = run(node("LIST_COLLECTIONS"));
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) output(listed, "result.collections");
        assertTrue(names.contains(COLLECTION));

        NodeExecutionResult dropped = run(node("DROP_COLLECTION", "confirmed", true));
        assertTrue(dropped.isSuccess(), () -> dropped.errorMessage());
        assertEquals(0, stored());
    }

    @Test
    @DisplayName("an arbitrary command is refused unless an administrator switched it on")
    void executeCommandIsOff() {
        NodeExecutionResult refused = run(node("EXECUTE_COMMAND",
                "confirmed", true, "options", Map.of("ping", 1)));

        assertTrue(refused.isFailed());
        assertEquals(MongoErrors.PERMISSION_DENIED, refused.errorCode());

        NodeExecutionResult allowed = run(node("EXECUTE_COMMAND",
                        "confirmed", true, "options", Map.of("ping", 1)),
                Map.of(), Map.of("operation.execute_command.enabled", true));

        assertTrue(allowed.isSuccess(), () -> allowed.errorMessage());
        assertEquals(1.0, output(allowed, "result.ok"));
    }

    @Test
    @DisplayName("an unreachable server fails as a connection error the engine may retry")
    void unreachable() {
        Map<String, Object> configuration = node("FIND_ONE");
        configuration.put("connectionUri", "mongodb://127.0.0.1:1/" + DATABASE);
        configuration.put("serverSelectionTimeoutMillis", 500);

        NodeExecutionResult result = run(configuration);

        assertTrue(result.isFailed());
        assertEquals(MongoErrors.CONNECTION_FAILED, result.errorCode());
        assertTrue(result.retryable());
    }

    @Test
    @DisplayName("no filter and no document reaches a log line")
    void logsCarryNoData() {
        TestExecution execution = TestExecution.with(node("FIND_ONE",
                        "filter", Map.of("email", "private@example.com")))
                .user("operator", "ADMIN")
                .build();
        plugin.initialize(execution);
        plugin.execute(execution);

        String log = String.join("\n", execution.logLines());
        assertTrue(log.contains("FIND_ONE"), () -> "the operation should be logged: " + log);
        // An execution log is read by more people than the collection is.
        assertFalse(log.contains("private@example.com"));
    }

    @Test
    @DisplayName("one client is pooled across executions rather than opened per node")
    void connectionsArePooled() {
        run(node("TEST_CONNECTION"));
        Map<String, Object> health = plugin.health();
        assertEquals(1, health.get("pooledConnections"));

        // A second execution against the same connection reuses it.
        run(node("TEST_CONNECTION"));
        assertEquals(1, plugin.health().get("pooledConnections"));
        assertEquals("RUNNING", plugin.health().get("status"));
    }
}
