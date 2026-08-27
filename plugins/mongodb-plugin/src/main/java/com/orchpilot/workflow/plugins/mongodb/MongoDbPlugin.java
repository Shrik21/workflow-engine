package com.orchpilot.workflow.plugins.mongodb;

import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;
import com.mongodb.client.MongoClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes MongoDB from a workflow.
 *
 * <h2>Three nodes</h2>
 *
 * {@code MONGODB_READ}, {@code MONGODB_WRITE} and {@code MONGODB_ADMIN}, each with an operation selector.
 * The split is by consequence rather than by method name: a read cannot lose data, a write changes documents,
 * and an administrative operation changes the shape of the database itself. That is the same line permissions
 * are drawn along, and it is the line an operator scanning a workflow cares about — twenty node types would
 * put Find One and Drop Collection side by side in the palette as equals.
 *
 * <h2>What this plugin cannot reach</h2>
 *
 * The engine's own database. {@link PluginContext} exposes a namespaced document store and nothing else; there
 * is no path from here to the engine's {@code MongoTemplate}, its collections, or its connection. A MongoDB
 * plugin is exactly the case that boundary was drawn for, and it holds: this connects only to what a node
 * configures and a secret authenticates.
 *
 * <p>Thread-safe. The context and the client cache are written once during {@code initialize}; the cache is
 * itself concurrent.
 */
public class MongoDbPlugin implements WorkflowNodePlugin {

    /** Reads. Nothing here changes a document. */
    public static final String NODE_READ = "MONGODB_READ";

    /** Writes: inserts, updates, replacements, deletes and batches of them. */
    public static final String NODE_WRITE = "MONGODB_WRITE";

    /** Collections, indexes, commands, and a connection test. */
    public static final String NODE_ADMIN = "MONGODB_ADMIN";

    private static final String PLUGIN_ID = "mongodb";

    /** Must match the POM and the JAR manifest: the engine probes all three and refuses a disagreement. */
    private static final String PLUGIN_VERSION = "1.0.3";

    private volatile PluginContext context;
    private volatile MongoClientCache clients;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "MongoDB";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "Reads and writes MongoDB from a workflow, with the connection configured on the node";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) {
        this.context = pluginContext;
        this.clients = new MongoClientCache(pluginContext.logger());
        pluginContext.logger().info("MongoDB plugin initialised; connections come from each node");
    }

    /**
     * Closes every pooled client.
     *
     * <p>Not optional. A {@code MongoClient} owns a connection pool and monitoring threads, and leaving one
     * behind pins this plugin's class loader in memory: the engine unloads the version, the class loader
     * cannot be collected because a live thread references it, and a few reloads later the JVM is out of
     * metaspace. This is the method that stops that.
     */
    @Override
    public void destroy() {
        MongoClientCache open = this.clients;
        if (open != null) {
            open.close();
        }
        this.clients = null;
    }

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        return List.of(
                NodeDefinition.builder(NODE_READ)
                        .displayName("MongoDB Read")
                        .description("Finds, counts, aggregates. Every read is bounded by a document limit "
                                + "and a server-side time limit.")
                        .category("Database")
                        .icon("database")
                        .configurationSchema(readSchema())
                        .outputVariables("result", "result.items", "result.count", "result.document",
                                "result.found", "success")
                        // A read changes nothing, so replaying one after a retry is free.
                        .idempotent(true)
                        .supportsRetry(true)
                        .build(),

                NodeDefinition.builder(NODE_WRITE)
                        .displayName("MongoDB Write")
                        .description("Inserts, updates, replaces, deletes, and batches of those. Bulk "
                                + "operations require explicit confirmation on the node.")
                        .category("Database")
                        .icon("database")
                        .configurationSchema(writeSchema())
                        .outputVariables("result", "result.insertedId", "result.insertedIds",
                                "result.matchedCount", "result.modifiedCount", "result.deletedCount",
                                "result.upsertedId", "success")
                        // An insert repeated is a second document. The engine's guard replays the recorded
                        // result rather than writing twice.
                        .idempotent(false)
                        .supportsRetry(true)
                        .build(),

                NodeDefinition.builder(NODE_ADMIN)
                        .displayName("MongoDB Admin")
                        .description("Collections, indexes, database commands, and a connection test. "
                                + "Destructive operations require confirmation and a mapped permission.")
                        .category("Database")
                        .icon("database")
                        .configurationSchema(adminSchema())
                        .outputVariables("result", "result.collections", "result.indexes",
                                "result.serverVersion", "result.connected", "success")
                        .idempotent(false)
                        .supportsRetry(false)
                        .build());
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext execution) {
        MongoConnectionSettings connection;
        MongoNodeRequest request;

        try {
            connection = MongoConnectionSettings.from(execution.configuration(), execution::resolve,
                    context.secrets());
            request = MongoNodeRequest.from(execution.configuration(), context.settings(),
                    execution::resolve, connection);
        } catch (PluginConfigurationException ex) {
            return NodeExecutionResult.failure(MongoErrors.CONFIGURATION_INVALID, ex.getMessage(), false);
        }

        List<String> problems = new ArrayList<>(connection.validate());
        problems.addAll(request.validate());
        if (!problems.isEmpty()) {
            // Every problem at once: one workflow run per mistake is a poor way to configure a query.
            return NodeExecutionResult.failure(MongoErrors.CONFIGURATION_INVALID,
                    "The MongoDB node is not correctly configured: " + String.join(" ", problems), false);
        }

        MongoGuards.Decision guard = MongoGuards.check(request.operation(), execution.configuration(),
                context.settings(), execution.currentUser());
        if (!guard.allowed()) {
            return NodeExecutionResult.failure(guard.code(), guard.message(), false);
        }

        MongoGuards.Decision filterGuard = MongoGuards.checkFilter(request.operation(), request.filter(),
                execution.configuration());
        if (!filterGuard.allowed()) {
            return NodeExecutionResult.failure(filterGuard.code(), filterGuard.message(), false);
        }

        long startedAt = System.currentTimeMillis();
        try {
            MongoClient client = clients.get(connection);
            MongoOperations.Outcome outcome = MongoOperations.run(client, request);

            // The operation, where it ran and how long it took. Never the filter, never a document: those are
            // the customer's data, and an execution log is read by more people than the collection is.
            context.logger().info("MongoDB {} on {} in {}ms: {}", request.operation().name(), request,
                    System.currentTimeMillis() - startedAt, outcome.summary());

            if (queryLoggingEnabled()) {
                // Off by default, for administrators diagnosing a query that matches nothing. It writes the
                // filter, which is data, which is why it is a decision somebody has to make.
                context.logger().debug("MongoDB filter for {}: {}", request, request.filter().toJson());
            }

            return NodeExecutionResult.success(outcome.outputs());

        } catch (MongoOperations.ResultTooLargeException ex) {
            return NodeExecutionResult.failure(MongoErrors.RESULT_TOO_LARGE, ex.getMessage(), false);

        } catch (IllegalArgumentException ex) {
            // A malformed bulk operation or an unusable option: configuration, not transport.
            return NodeExecutionResult.failure(MongoErrors.VALIDATION_FAILED, ex.getMessage(), false);

        } catch (Exception ex) {
            MongoErrors.Classification classification = MongoErrors.classify(ex, connection);
            if (MongoErrors.AUTHENTICATION_FAILED.equals(classification.code())) {
                // The pooled client authenticated with a credential the server no longer accepts. Keeping it
                // would fail every execution until the plugin was reloaded.
                clients.discard(connection);
            }
            context.logger().warn("MongoDB {} failed after {}ms: {} {}", request,
                    System.currentTimeMillis() - startedAt, classification.code(), classification.message());
            return NodeExecutionResult.failure(classification.code(), classification.message(),
                    classification.retryable());
        }
    }

    private boolean queryLoggingEnabled() {
        return context.settings().getBoolean("logQueries", false);
    }

    /**
     * What the plugin can say about itself.
     *
     * <p>Reported through the plugin's own logger at initialise time and available to a health endpoint that
     * asks for it. Deliberately not a live probe of every configured connection: this plugin has no list of
     * them — a connection belongs to a node — and pinging every database on a health check would make a
     * monitoring interval into database load somebody else pays for.
     *
     * @return the plugin's status
     */
    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("plugin", PLUGIN_ID);
        health.put("version", PLUGIN_VERSION);
        health.put("status", clients == null ? "STOPPED" : "RUNNING");
        health.put("pooledConnections", clients == null ? 0 : clients.size());
        health.put("nodeTypes", List.of(NODE_READ, NODE_WRITE, NODE_ADMIN));
        health.put("driverVersion", driverVersion());
        return health;
    }

    private static String driverVersion() {
        Package driver = MongoClient.class.getPackage();
        String version = driver == null ? null : driver.getImplementationVersion();
        return version == null ? "bundled" : version;
    }

    // ------------------------------------------------------------------------------------- schemas

    private static Map<String, Object> readSchema() {
        Map<String, Object> properties = connectionProperties();

        properties.put("operation", select("Operation",
                List.of("FIND_ONE", "FIND_MANY", "COUNT", "DISTINCT", "AGGREGATE"), "FIND_ONE",
                "What to do. The fields below that do not apply to it are ignored."));
        properties.put("collection", field("string", "Collection", "Supports variables."));

        properties.put("filter", json("Filter",
                "A MongoDB query document. Variables are resolved inside it: "
                        + "{\"email\": \"${form.email}\"}. For an ObjectId, write {\"_id\": {\"$oid\": "
                        + "\"${customer.id}\"}} — a 24-character string is left a string on purpose."));
        properties.put("projection", json("Projection", "{\"_id\": 1, \"name\": 1}"));
        properties.put("sort", json("Sort", "{\"createdAt\": -1}"));
        properties.put("hint", json("Index hint", "Forces an index, as {\"email\": 1}."));
        properties.put("collation", json("Collation", "{\"locale\": \"en\", \"numericOrdering\": true}"));

        properties.put("pipeline", jsonArray("Aggregation pipeline",
                "Used by AGGREGATE. An array of stages: [{\"$match\": {...}}, {\"$group\": {...}}]."));
        properties.put("field", field("string", "Field", "The field to collect, used by DISTINCT."));
        properties.put("allowDiskUse", field("boolean", "Allow disk use",
                "Lets the server spill an aggregation to disk rather than failing at its in-memory limit."));

        properties.put("page", field("integer", "Page", "1-based. Used with page size instead of skip."));
        properties.put("pageSize", field("integer", "Page size", null));
        properties.put("skip", field("integer", "Skip", null));
        properties.put("limit", field("integer", "Limit", "Capped by the maximum documents below."));
        properties.put("includeTotalCount", field("boolean", "Include total count",
                "Counts every matching document as well as returning a page. A second query: off by default."));

        properties.putAll(limitProperties());
        properties.put("outputVariable", outputVariableField());

        showFor(properties, "operation", Map.of(
                "filter", List.of("FIND_ONE", "FIND_MANY", "COUNT", "DISTINCT"),
                "projection", List.of("FIND_ONE", "FIND_MANY"),
                "sort", List.of("FIND_ONE", "FIND_MANY"),
                "hint", List.of("FIND_ONE", "FIND_MANY"),
                "collation", List.of("FIND_ONE", "FIND_MANY"),
                "pipeline", List.of("AGGREGATE"),
                "allowDiskUse", List.of("AGGREGATE"),
                "field", List.of("DISTINCT")));
        showFor(properties, "operation", Map.of(
                "page", List.of("FIND_MANY"),
                "pageSize", List.of("FIND_MANY"),
                "skip", List.of("FIND_MANY", "COUNT"),
                "limit", List.of("FIND_MANY"),
                "includeTotalCount", List.of("FIND_MANY")));

        // An aggregation without a pipeline and a distinct without a field cannot run. Declared here so the
        // designer refuses to publish them, rather than the execution discovering it.
        requireFor(properties, "operation", Map.of(
                "pipeline", List.of("AGGREGATE"),
                "field", List.of("DISTINCT")));

        describeOperations(properties, Map.of(
                "FIND_ONE", "Returns the first document matching the filter, or nothing.",
                "FIND_MANY", "Returns every matching document, with paging and sorting.",
                "COUNT", "Counts matching documents without returning them.",
                "DISTINCT", "Returns the distinct values of one field across matching documents.",
                "AGGREGATE", "Runs an aggregation pipeline and returns its output."));

        // Deliberately still only the operation. Adding "collection" here would be more correct in the
        // abstract and would newly refuse any already-published workflow whose node omits it — a plugin
        // update must not do that. Conditional requirements above are additive and cannot.
        return schema(List.of("operation"), properties);
    }

    private static Map<String, Object> writeSchema() {
        Map<String, Object> properties = connectionProperties();

        properties.put("operation", select("Operation",
                List.of("INSERT_ONE", "INSERT_MANY", "UPDATE_ONE", "UPDATE_MANY", "REPLACE_ONE",
                        "DELETE_ONE", "DELETE_MANY", "BULK_WRITE"), "INSERT_ONE",
                "Update One changes named fields; Replace One overwrites the whole document. The difference "
                        + "is every field the replacement does not mention."));
        properties.put("collection", field("string", "Collection", "Supports variables."));

        properties.put("documents", jsonArray("Documents",
                "The documents to insert, or the replacement for REPLACE_ONE, or the operations for "
                        + "BULK_WRITE. A single object is accepted where one document is expected."));
        properties.put("filter", json("Filter", "Which documents to act on. Variables are resolved inside."));
        properties.put("update", json("Update",
                "Update operators: " + String.join(", ", MongoNodeRequest.updateOperators()) + ". A plain "
                        + "document here is refused, because it would silently replace rather than update."));

        properties.put("upsert", field("boolean", "Upsert",
                "Insert the document when the filter matches nothing."));
        properties.put("ordered", field("boolean", "Ordered",
                "Stop at the first failure. Off, the remaining operations are still attempted."));
        properties.put("useTransaction", field("boolean", "Run in a transaction",
                "BULK_WRITE only, and only on a replica set or sharded cluster. The transaction lives for "
                        + "this node and no longer: it commits or rolls back before the workflow moves on."));

        properties.put("confirmed", field("boolean", "Confirmed",
                "Required for anything that affects more than one document: UPDATE_MANY, DELETE_MANY, "
                        + "REPLACE_ONE and BULK_WRITE."));
        properties.put("allowEmptyFilter", field("boolean", "Allow an empty filter",
                "Required when a bulk update or delete has no filter, which matches every document. The "
                        + "usual cause is a variable that resolved to nothing."));

        properties.putAll(limitProperties());
        properties.put("outputVariable", outputVariableField());

        showFor(properties, "operation", Map.of(
                "documents", List.of("INSERT_ONE", "INSERT_MANY", "REPLACE_ONE", "BULK_WRITE"),
                "filter", List.of("UPDATE_ONE", "UPDATE_MANY", "REPLACE_ONE", "DELETE_ONE", "DELETE_MANY"),
                "update", List.of("UPDATE_ONE", "UPDATE_MANY"),
                "upsert", List.of("UPDATE_ONE", "UPDATE_MANY", "REPLACE_ONE"),
                "ordered", List.of("INSERT_MANY", "BULK_WRITE"),
                "useTransaction", List.of("BULK_WRITE"),
                "confirmed", List.of("UPDATE_MANY", "DELETE_MANY", "REPLACE_ONE", "BULK_WRITE"),
                "allowEmptyFilter", List.of("UPDATE_MANY", "DELETE_MANY")));

        // An insert with no documents, or an update with no update document, cannot run. A filter is not
        // listed: an intentionally empty one is meaningful, and `allowEmptyFilter` already gates that.
        requireFor(properties, "operation", Map.of(
                "documents", List.of("INSERT_ONE", "INSERT_MANY", "REPLACE_ONE", "BULK_WRITE"),
                "update", List.of("UPDATE_ONE", "UPDATE_MANY")));

        describeOperations(properties, Map.of(
                "INSERT_ONE", "Adds a single document.",
                "INSERT_MANY", "Adds several documents in one call.",
                "UPDATE_ONE", "Changes the named fields of the first matching document.",
                "UPDATE_MANY", "Changes the named fields of every matching document.",
                "REPLACE_ONE", "Overwrites a whole document — every field the replacement omits is lost.",
                "DELETE_ONE", "Removes the first matching document.",
                "DELETE_MANY", "Removes every matching document.",
                "BULK_WRITE", "Runs several write operations in one round trip."));

        return schema(List.of("operation"), properties);
    }

    private static Map<String, Object> adminSchema() {
        Map<String, Object> properties = connectionProperties();

        properties.put("operation", select("Operation",
                List.of("TEST_CONNECTION", "LIST_COLLECTIONS", "COLLECTION_STATS", "CREATE_COLLECTION",
                        "RENAME_COLLECTION", "DROP_COLLECTION", "LIST_INDEXES", "CREATE_INDEX", "DROP_INDEX",
                        "EXECUTE_COMMAND"), "TEST_CONNECTION",
                "Test Connection connects, pings and reports the server version without touching data."));
        properties.put("collection", field("string", "Collection", null));
        properties.put("targetCollection", field("string", "Target collection",
                "The new name for RENAME_COLLECTION, or the name for CREATE_COLLECTION."));

        properties.put("keys", json("Index keys", "{\"email\": 1} or {\"lastName\": 1, \"firstName\": 1}."));
        properties.put("field", field("string", "Index name", "Used by DROP_INDEX."));
        properties.put("options", json("Options",
                "Index options such as {\"unique\": true, \"sparse\": true, \"expireAfterSeconds\": 3600, "
                        + "\"partialFilterExpression\": {...}} — or, for EXECUTE_COMMAND, the command itself."));

        properties.put("confirmed", field("boolean", "Confirmed",
                "Required for DROP_COLLECTION, RENAME_COLLECTION, DROP_INDEX and EXECUTE_COMMAND."));

        properties.putAll(limitProperties());
        properties.put("outputVariable", outputVariableField());

        showFor(properties, "operation", Map.of(
                "collection", List.of("COLLECTION_STATS", "RENAME_COLLECTION", "DROP_COLLECTION",
                        "LIST_INDEXES", "CREATE_INDEX", "DROP_INDEX"),
                "targetCollection", List.of("CREATE_COLLECTION", "RENAME_COLLECTION"),
                "keys", List.of("CREATE_INDEX", "DROP_INDEX"),
                "field", List.of("DROP_INDEX"),
                "options", List.of("CREATE_INDEX", "EXECUTE_COMMAND"),
                "confirmed", List.of("RENAME_COLLECTION", "DROP_COLLECTION", "DROP_INDEX",
                        "EXECUTE_COMMAND")));

        return schema(List.of("operation"), properties);
    }

    /** The connection fields, identical on all three nodes so a node can be retyped without reconfiguring. */
    private static Map<String, Object> connectionProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("connectionUri", field("string", "Connection string",
                "mongodb://host:27017/db or mongodb+srv://cluster.mongodb.net/db. A URI containing "
                        + "user:password@ is refused: supply the credentials below instead."));
        properties.put("host", field("string", "Host", "Used when no connection string is given."));
        properties.put("port", field("integer", "Port", null));
        properties.put("database", field("string", "Database",
                "Overrides the database named in the connection string."));

        properties.put("username", field("string", "Username", "Supports variables."));

        Map<String, Object> passwordSecret = field("string", "Password secret",
                "The NAME of a secret holding the password, never the password itself. The value is fetched "
                        + "at execution and never enters the workflow definition.");
        passwordSecret.put("format", "secret-ref");
        properties.put("passwordSecret", passwordSecret);

        properties.put("credentialId", field("string", "Credential id",
                "An alternative to the above: names a stored credential whose username and password are held "
                        + "as the secrets <id>.username and <id>.password."));
        properties.put("authenticationDatabase", field("string", "Authentication database",
                "Where the user is defined. Usually 'admin'. Defaults to the database being used."));
        properties.put("replicaSet", field("string", "Replica set", null));

        properties.put("tls", field("boolean", "TLS", "Required by Atlas and by most managed deployments."));
        properties.put("tlsAllowInvalidHostnames", field("boolean", "Allow invalid hostnames",
                "Accepts a certificate that does not match the host. Only for a deployment with a "
                        + "self-signed certificate you have decided to trust."));

        properties.put("connectionTimeoutMillis", field("integer", "Connection timeout (ms)", null));
        properties.put("socketTimeoutMillis", field("integer", "Socket timeout (ms)", null));
        properties.put("serverSelectionTimeoutMillis", field("integer", "Server selection timeout (ms)",
                "How long to look for a usable server before giving up. A failing-over replica set needs a "
                        + "few seconds here."));
        properties.put("maxPoolSize", field("integer", "Maximum pool size", null));
        properties.put("minPoolSize", field("integer", "Minimum pool size", null));

        return properties;
    }

    private static Map<String, Object> limitProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("maxDocuments", field("integer", "Maximum documents",
                "The most this node will return. Capped by the installation's ceiling."));
        properties.put("maxTimeMillis", field("integer", "Maximum server time (ms)",
                "Passed to MongoDB as maxTimeMS, so a slow query is stopped by the server rather than by a "
                        + "socket timeout that leaves it running."));
        return properties;
    }

    private static Map<String, Object> outputVariableField() {
        return field("string", "Output variable",
                "The name subsequent nodes use: 'customerResult' makes ${customerResult.items} available "
                        + "once mapped. Defaults to 'result'.");
    }

    private static Map<String, Object> schema(List<String> required, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", required);
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> field(String type, String title, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", type);
        field.put("title", title);
        if (description != null) {
            field.put("description", description);
        }
        return field;
    }

    private static Map<String, Object> select(String title, List<String> options, String defaultValue,
                                              String description) {
        Map<String, Object> field = field("string", title, description);
        field.put("enum", options);
        field.put("default", defaultValue);
        return field;
    }

    /** An object with no declared properties: the designer renders a JSON editor, which is honest here. */
    private static Map<String, Object> json(String title, String description) {
        return field("object", title, description);
    }

    private static Map<String, Object> jsonArray(String title, String description) {
        Map<String, Object> field = field("array", title, description);
        field.put("items", Map.of("type", "object"));
        return field;
    }

    /**
     * Shows a field only for certain operations.
     *
     * <p>A node with one operation selector and every field for every operation on screen at once is a form
     * with forty controls, of which six matter. {@code visibleWhen} is read by the designer's schema form and
     * hides the rest — schema-driven, so it works for any plugin that wants it and needed no MongoDB-specific
     * component in the front end.
     *
     * <p>Presentation only. Nothing here is a security control: a hidden field that is set anyway is still
     * read by the plugin, and every rule that matters is enforced in {@link MongoGuards} on the server.
     *
     * @param field      the field to qualify
     * @param dependsOn  the field whose value decides
     * @param values     the values that show it
     * @return the same field, with the condition attached
     */
    private static Map<String, Object> visibleWhen(Map<String, Object> field, String dependsOn,
                                                   List<String> values) {
        field.put("visibleWhen", Map.of(dependsOn, values));
        return field;
    }

    /** Applies a visibility condition to fields already in the map, so the schemas above stay readable. */
    private static void showFor(Map<String, Object> properties, String operationField,
                                Map<String, List<String>> conditions) {
        conditions.forEach((name, operations) -> {
            Object property = properties.get(name);
            if (property instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                visibleWhen(typed, operationField, operations);
            }
        });
    }

    /**
     * Marks fields the chosen operation cannot run without.
     *
     * <p>These cannot go in the schema's {@code required} list: that list is unconditional, so requiring
     * {@code pipeline} there would refuse every Find and every Count. Until {@code requiredWhen} existed this
     * node declared only the operation itself required and caught the rest during execution — which is a
     * failed run rather than a validation error, and by then the workflow is published.
     */
    /**
     * Attaches a sentence per operation to the selector.
     *
     * <p>A dropdown of eleven names tells an author nothing about the difference between Update One and
     * Replace One, which is the whole document. The designer shows the description of whichever is selected.
     */
    private static void describeOperations(Map<String, Object> properties,
                                           Map<String, String> descriptions) {
        Object property = properties.get("operation");
        if (property instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            typed.put("enumDescriptions", Map.copyOf(descriptions));
        }
    }

    private static void requireFor(Map<String, Object> properties, String operationField,
                                   Map<String, List<String>> conditions) {
        conditions.forEach((name, operations) -> {
            Object property = properties.get(name);
            if (property instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                typed.put("requiredWhen", Map.of(operationField, operations));
            }
        });
    }

    /** Every operation name, for documentation generated from the plugin rather than maintained beside it. */
    static List<String> operationNames() {
        return Arrays.stream(MongoOperation.values()).map(Enum::name).toList();
    }
}
