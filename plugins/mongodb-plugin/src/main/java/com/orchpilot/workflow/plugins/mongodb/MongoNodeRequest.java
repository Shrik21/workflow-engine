package com.orchpilot.workflow.plugins.mongodb;

import com.orchpilot.workflow.sdk.context.PluginSettings;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * One node's MongoDB request, resolved and bounded.
 *
 * <h2>Bounded, because the default is unbounded</h2>
 *
 * {@code find({})} against a collection of forty million documents is a valid query, and the engine will
 * faithfully hold every result in memory while the workflow that asked for them dies. Every read here carries
 * a document limit and a server-side time limit, both defaulted rather than optional, and both capped by
 * whatever ceiling an administrator set in the plugin's installation settings. An operator can lower them; the
 * ceiling is the only thing they cannot raise past.
 */
final class MongoNodeRequest {

    /** Enough for a page of results and a report; far short of anything that fills a heap. */
    private static final int DEFAULT_MAX_DOCUMENTS = 1_000;

    /** A ceiling an administrator can lower, and an operator cannot exceed. */
    private static final int CEILING_MAX_DOCUMENTS = 10_000;

    private static final long DEFAULT_MAX_TIME_MILLIS = 30_000;
    private static final long CEILING_MAX_TIME_MILLIS = 300_000;

    /** MongoDB's own document ceiling is 16 MiB; a result set worth many of those is a report, not a step. */
    private static final long DEFAULT_MAX_RESULT_BYTES = 16L * 1024 * 1024;

    private final MongoOperation operation;
    private final String database;
    private final String collection;
    private final Document filter;
    private final Document projection;
    private final Document sort;
    private final Document update;
    private final Document options;
    private final List<Document> documents;
    private final List<Document> pipeline;
    private final String field;
    private final int skip;
    private final int limit;
    private final int maxDocuments;
    private final long maxTimeMillis;
    private final long maxResultBytes;
    private final boolean upsert;
    private final boolean ordered;
    private final boolean useTransaction;
    private final String outputVariable;
    private final NodeConfiguration configuration;

    private MongoNodeRequest(Builder builder) {
        this.operation = builder.operation;
        this.database = builder.database;
        this.collection = builder.collection;
        this.filter = builder.filter;
        this.projection = builder.projection;
        this.sort = builder.sort;
        this.update = builder.update;
        this.options = builder.options;
        this.documents = List.copyOf(builder.documents);
        this.pipeline = List.copyOf(builder.pipeline);
        this.field = builder.field;
        this.skip = builder.skip;
        this.limit = builder.limit;
        this.maxDocuments = builder.maxDocuments;
        this.maxTimeMillis = builder.maxTimeMillis;
        this.maxResultBytes = builder.maxResultBytes;
        this.upsert = builder.upsert;
        this.ordered = builder.ordered;
        this.useTransaction = builder.useTransaction;
        this.outputVariable = builder.outputVariable;
        this.configuration = builder.configuration;
    }

    /**
     * Reads a node's configuration.
     *
     * @param configuration the node configuration
     * @param settings      the plugin's installation settings, holding the ceilings
     * @param resolve       the engine's variable resolver
     * @param connection    the connection, for the database it names
     * @return the resolved request
     * @throws PluginConfigurationException when the operation is missing or unknown
     */
    static MongoNodeRequest from(NodeConfiguration configuration, PluginSettings settings,
                                 UnaryOperator<String> resolve, MongoConnectionSettings connection) {
        Builder builder = new Builder();
        builder.configuration = configuration;

        String requested = configuration.getString("operation", "");
        builder.operation = MongoOperation.parse(requested)
                .orElseThrow(() -> new PluginConfigurationException(requested.isBlank()
                        ? "No operation was chosen. Set 'operation' to one of: " + String.join(", ",
                                MongoOperation.names())
                        : "'" + requested + "' is not a MongoDB operation this node performs. Choose one of: "
                                + String.join(", ", MongoOperation.names())));

        String configuredDatabase = resolve.apply(configuration.getString("database", "")).trim();
        builder.database = configuredDatabase.isBlank() ? connection.database() : configuredDatabase;
        builder.collection = resolve.apply(configuration.getString("collection", "")).trim();

        builder.filter = BsonJson.document(configuration.find("filter").orElse(null), "filter", resolve);
        builder.projection = BsonJson.document(configuration.find("projection").orElse(null), "projection",
                resolve);
        builder.sort = BsonJson.document(configuration.find("sort").orElse(null), "sort", resolve);
        builder.update = BsonJson.document(configuration.find("update").orElse(null), "update", resolve);
        builder.options = BsonJson.document(configuration.find("options").orElse(null), "options", resolve);

        builder.documents = BsonJson.documents(configuration.find("documents").orElse(
                configuration.find("document").orElse(null)), "documents", resolve);
        builder.pipeline = BsonJson.documents(configuration.find("pipeline").orElse(null), "pipeline", resolve);

        builder.field = resolve.apply(configuration.getString("field", "")).trim();

        builder.upsert = configuration.getBoolean("upsert", false);
        builder.ordered = configuration.getBoolean("ordered", true);
        builder.useTransaction = configuration.getBoolean("useTransaction", false);
        builder.outputVariable = outputVariable(configuration, resolve);

        applyPaging(configuration, builder);
        applyLimits(configuration, settings, builder);

        return new MongoNodeRequest(builder);
    }

    /**
     * Page/pageSize or skip/limit, whichever the operator used.
     *
     * <p>Both, because both are what people have: a UI paginates by page number, and a batch job resumes from
     * an offset. Page wins when present, since writing a page number is the more deliberate act.
     */
    private static void applyPaging(NodeConfiguration configuration, Builder builder) {
        int page = configuration.getInt("page", 0);
        int pageSize = configuration.getInt("pageSize", 0);

        if (page > 0 && pageSize > 0) {
            builder.skip = (page - 1) * pageSize;
            builder.limit = pageSize;
            return;
        }
        builder.skip = Math.max(0, configuration.getInt("skip", 0));
        builder.limit = Math.max(0, configuration.getInt("limit", 0));
    }

    private static void applyLimits(NodeConfiguration configuration, PluginSettings settings, Builder builder) {
        int ceilingDocuments = settings.getInt("maxDocumentsCeiling", CEILING_MAX_DOCUMENTS);
        int requestedDocuments = configuration.getInt("maxDocuments",
                settings.getInt("maxDocumentsDefault", DEFAULT_MAX_DOCUMENTS));
        builder.maxDocuments = Math.max(1, Math.min(requestedDocuments, ceilingDocuments));

        long ceilingTime = settingLong(settings, "maxTimeMillisCeiling", CEILING_MAX_TIME_MILLIS);
        long requestedTime = configuration.getLong("maxTimeMillis",
                settingLong(settings, "maxTimeMillisDefault", DEFAULT_MAX_TIME_MILLIS));
        builder.maxTimeMillis = Math.max(1, Math.min(requestedTime, ceilingTime));

        builder.maxResultBytes = settingLong(settings, "maxResultBytes", DEFAULT_MAX_RESULT_BYTES);
    }

    /**
     * A {@code long} installation setting.
     *
     * <p>{@link PluginSettings} offers {@code getInt} and not {@code getLong}, and a result-size ceiling in
     * bytes overflows an int at two gigabytes. Read here rather than widened in the SDK, which is a shared
     * contract and not something a plugin should grow to suit itself.
     */
    private static long settingLong(PluginSettings settings, String key, long defaultValue) {
        Object raw = settings.find(key).orElse(null);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * The name subsequent nodes will use.
     *
     * <p>Defaults to {@code result}, so a node that nobody configured still publishes something addressable.
     * Restricted to an identifier, because a name with a dot in it would produce {@code ${a.b.insertedId}}
     * and leave nobody able to tell which part was the variable.
     */
    private static String outputVariable(NodeConfiguration configuration, UnaryOperator<String> resolve) {
        String configured = resolve.apply(configuration.getString("outputVariable", "")).trim();
        if (configured.isEmpty()) {
            return "result";
        }
        if (!configured.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new PluginConfigurationException(
                    "'" + configured + "' is not a usable output variable name. Use letters, digits and "
                            + "underscores, starting with a letter — 'customerResult', for instance, which "
                            + "subsequent nodes then read as ${customerResult.items}.");
        }
        return configured;
    }

    /**
     * Everything wrong with this request, all of it at once.
     *
     * @return the problems, empty when the operation can run
     */
    List<String> validate() {
        List<String> problems = new ArrayList<>();

        if (database.isBlank()) {
            problems.add("A database is required.");
        }
        if (collection.isBlank() && needsCollection()) {
            problems.add("A collection is required for " + operation.label() + ".");
        }

        switch (operation) {
            case INSERT_ONE, INSERT_MANY -> {
                if (documents.isEmpty()) {
                    problems.add("At least one document to insert is required.");
                }
                if (operation == MongoOperation.INSERT_ONE && documents.size() > 1) {
                    problems.add("Insert One was given " + documents.size() + " documents. Use Insert Many.");
                }
            }
            case UPDATE_ONE, UPDATE_MANY -> {
                if (update.isEmpty()) {
                    problems.add("An update is required, such as {\"$set\": {\"status\": \"ACTIVE\"}}.");
                } else if (!isUpdateOperatorDocument(update)) {
                    problems.add("An update must use operators such as $set, $inc or $push. A plain document "
                            + "would replace the matched document entirely — if that is what you want, use "
                            + "Replace One, which says so.");
                }
            }
            case REPLACE_ONE -> {
                if (documents.isEmpty()) {
                    problems.add("A replacement document is required.");
                } else if (isUpdateOperatorDocument(documents.get(0))) {
                    problems.add("A replacement must be a plain document, not update operators. Use Update "
                            + "One for $set and friends.");
                }
            }
            case AGGREGATE -> {
                if (pipeline.isEmpty()) {
                    problems.add("An aggregation pipeline is required.");
                }
                problems.addAll(validatePipeline());
            }
            case DISTINCT -> {
                if (field.isBlank()) {
                    problems.add("A field is required for Distinct.");
                }
            }
            case BULK_WRITE -> {
                if (documents.isEmpty()) {
                    problems.add("At least one bulk operation is required.");
                }
            }
            case CREATE_INDEX -> {
                if (BsonJson.document(configuration.find("keys").orElse(null), "keys", value -> value)
                        .isEmpty()) {
                    problems.add("Index keys are required, such as {\"email\": 1}.");
                }
            }
            case DROP_INDEX -> {
                if (field.isBlank() && BsonJson
                        .document(configuration.find("keys").orElse(null), "keys", value -> value).isEmpty()) {
                    problems.add("An index name in 'field', or its keys in 'keys', is required.");
                }
            }
            case RENAME_COLLECTION -> {
                if (configuration.getString("targetCollection", "").isBlank()) {
                    problems.add("A target collection name is required.");
                }
            }
            case EXECUTE_COMMAND -> {
                if (options.isEmpty() && filter.isEmpty()) {
                    problems.add("A command document is required, in 'options'.");
                }
            }
            default -> {
                // Reads need only a filter, which may legitimately be empty.
            }
        }

        if (skip < 0) {
            problems.add("Skip cannot be negative.");
        }
        return problems;
    }

    /** Collection-less operations: those that act on the database itself. */
    private boolean needsCollection() {
        return switch (operation) {
            case LIST_COLLECTIONS, EXECUTE_COMMAND, TEST_CONNECTION, CREATE_COLLECTION -> false;
            default -> true;
        };
    }

    /**
     * Checks the pipeline's shape before the server sees it.
     *
     * <p>Each stage must be a single {@code $}-prefixed key. The failure this catches is a stage written as
     * {@code {"match": {...}}} — no dollar — which the server rejects with a message about an unrecognised
     * field, several layers into a pipeline the operator then has to re-read.
     */
    private List<String> validatePipeline() {
        List<String> problems = new ArrayList<>();
        for (int index = 0; index < pipeline.size(); index++) {
            Document stage = pipeline.get(index);
            if (stage.size() != 1) {
                problems.add("Pipeline stage " + (index + 1) + " must have exactly one operator, but has "
                        + stage.size() + ": " + String.join(", ", stage.keySet()) + ".");
                continue;
            }
            String operator = stage.keySet().iterator().next();
            if (!operator.startsWith("$")) {
                problems.add("Pipeline stage " + (index + 1) + " names '" + operator
                        + "', which is not a stage operator. Stages start with $, as in $match or $group.");
            }
        }
        return problems;
    }

    /** @return whether every key is an update operator, which is what separates an update from a replacement */
    private static boolean isUpdateOperatorDocument(Document document) {
        if (document.isEmpty()) {
            return false;
        }
        return document.keySet().stream().allMatch(key -> key.startsWith("$"));
    }

    /** The update operators MongoDB accepts, for documentation and for the schema's description. */
    static Set<String> updateOperators() {
        return Set.of("$set", "$unset", "$inc", "$mul", "$min", "$max", "$currentDate", "$rename",
                "$addToSet", "$pop", "$pull", "$pullAll", "$push", "$setOnInsert", "$bit");
    }

    MongoOperation operation() {
        return operation;
    }

    String database() {
        return database;
    }

    String collection() {
        return collection;
    }

    Document filter() {
        return filter;
    }

    Document projection() {
        return projection;
    }

    Document sort() {
        return sort;
    }

    Document update() {
        return update;
    }

    Document options() {
        return options;
    }

    List<Document> documents() {
        return documents;
    }

    List<Document> pipeline() {
        return pipeline;
    }

    String field() {
        return field;
    }

    int skip() {
        return skip;
    }

    /** @return the requested limit, capped at the document ceiling; the ceiling when none was asked for */
    int effectiveLimit() {
        return limit > 0 ? Math.min(limit, maxDocuments) : maxDocuments;
    }

    int maxDocuments() {
        return maxDocuments;
    }

    long maxTimeMillis() {
        return maxTimeMillis;
    }

    long maxResultBytes() {
        return maxResultBytes;
    }

    boolean upsert() {
        return upsert;
    }

    boolean ordered() {
        return ordered;
    }

    boolean useTransaction() {
        return useTransaction;
    }

    String outputVariable() {
        return outputVariable;
    }

    NodeConfiguration configuration() {
        return configuration;
    }

    /** A description safe to log: what ran and where, never the filter, which is data. */
    @Override
    public String toString() {
        return operation.name().toLowerCase(Locale.ROOT) + " " + database
                + (collection.isBlank() ? "" : "." + collection);
    }

    /** Mutable while reading a configuration, immutable afterwards. */
    private static final class Builder {
        private MongoOperation operation;
        private String database = "";
        private String collection = "";
        private Document filter = new Document();
        private Document projection = new Document();
        private Document sort = new Document();
        private Document update = new Document();
        private Document options = new Document();
        private List<Document> documents = List.of();
        private List<Document> pipeline = List.of();
        private String field = "";
        private int skip;
        private int limit;
        private int maxDocuments = DEFAULT_MAX_DOCUMENTS;
        private long maxTimeMillis = DEFAULT_MAX_TIME_MILLIS;
        private long maxResultBytes = DEFAULT_MAX_RESULT_BYTES;
        private boolean upsert;
        private boolean ordered = true;
        private boolean useTransaction;
        private String outputVariable = "result";
        private NodeConfiguration configuration;
    }
}
