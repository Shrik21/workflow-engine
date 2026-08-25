package com.orchpilot.workflow.plugins.mongodb;

import com.mongodb.ReadPreference;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.ClientSession;
import com.mongodb.client.DistinctIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.DeleteManyModel;
import com.mongodb.client.model.DeleteOneModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateManyModel;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs one MongoDB operation and describes what it did.
 *
 * <h2>Outputs are shaped for the next node</h2>
 *
 * Everything is published twice: once nested under the operator's chosen output variable, so a single mapping
 * line promotes the whole result, and once as flat dotted keys, so a mapping can pick one field. Neither
 * shape is a compromise — the first is what somebody wants when the next node needs the document, the second
 * is what they want when it needs the id.
 *
 * <h2>Cursors are drained under a limit, always</h2>
 *
 * Every read applies both a document limit and a server-side time limit. The limit is not a convenience: an
 * unbounded {@code find} against a large collection is a valid query whose result does not fit in the engine's
 * heap, and the failure mode is an {@code OutOfMemoryError} in the engine rather than a failed workflow step.
 */
final class MongoOperations {

    private MongoOperations() {
    }

    /** What an operation produced: the outputs to publish, and a one-line summary for the log. */
    record Outcome(Map<String, Object> outputs, String summary) {
    }

    /**
     * Runs the request.
     *
     * @param client  the shared client for this connection
     * @param request the resolved request
     * @return the outputs and a summary
     */
    static Outcome run(MongoClient client, MongoNodeRequest request) {
        MongoDatabase database = client.getDatabase(request.database());

        return switch (request.operation()) {
            case TEST_CONNECTION -> testConnection(client, database);

            case FIND_ONE -> findOne(collection(database, request), request);
            case FIND_MANY -> findMany(collection(database, request), request);
            case COUNT -> count(collection(database, request), request);
            case DISTINCT -> distinct(collection(database, request), request);
            case AGGREGATE -> aggregate(collection(database, request), request);

            case INSERT_ONE -> insertOne(collection(database, request), request);
            case INSERT_MANY -> insertMany(collection(database, request), request);
            case UPDATE_ONE, UPDATE_MANY -> update(collection(database, request), request);
            case REPLACE_ONE -> replaceOne(collection(database, request), request);
            case DELETE_ONE, DELETE_MANY -> delete(collection(database, request), request);
            case BULK_WRITE -> bulkWrite(client, collection(database, request), request);

            case LIST_COLLECTIONS -> listCollections(database, request);
            case COLLECTION_STATS -> collectionStats(database, request);
            case CREATE_COLLECTION -> createCollection(database, request);
            case RENAME_COLLECTION -> renameCollection(database, request);
            case DROP_COLLECTION -> dropCollection(database, request);
            case LIST_INDEXES -> listIndexes(collection(database, request), request);
            case CREATE_INDEX -> createIndex(collection(database, request), request);
            case DROP_INDEX -> dropIndex(collection(database, request), request);
            case EXECUTE_COMMAND -> executeCommand(database, request);
        };
    }

    private static MongoCollection<Document> collection(MongoDatabase database, MongoNodeRequest request) {
        return database.getCollection(request.collection());
    }

    // ------------------------------------------------------------------------------------------ read

    private static Outcome findOne(MongoCollection<Document> collection, MongoNodeRequest request) {
        FindIterable<Document> find = collection.find(request.filter())
                .projection(orNull(request.projection()))
                .sort(orNull(request.sort()))
                .maxTime(request.maxTimeMillis(), TimeUnit.MILLISECONDS);
        applyHintAndCollation(find, request);

        Document document = find.first();

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("found", document != null);
        values.put("document", document == null ? null : normalise(document));
        if (document != null) {
            // The document's own fields, so ${result.email} works without going through 'document'. This is
            // the shape somebody expects after asking for one record.
            values.putAll(normalise(document));
        }
        return new Outcome(publish(request, values),
                document == null ? "matched nothing" : "matched one document");
    }

    private static Outcome findMany(MongoCollection<Document> collection, MongoNodeRequest request) {
        FindIterable<Document> find = collection.find(request.filter())
                .projection(orNull(request.projection()))
                .sort(orNull(request.sort()))
                .skip(request.skip())
                // One more than asked for, so 'hasMore' is a fact rather than a guess.
                .limit(request.effectiveLimit() + 1)
                .maxTime(request.maxTimeMillis(), TimeUnit.MILLISECONDS);
        applyHintAndCollation(find, request);

        List<Map<String, Object>> items = drain(find, request);
        boolean hasMore = items.size() > request.effectiveLimit();
        if (hasMore) {
            items = items.subList(0, request.effectiveLimit());
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("items", items);
        values.put("count", items.size());
        values.put("hasMore", hasMore);
        values.put("skip", request.skip());
        values.put("limit", request.effectiveLimit());
        if (request.configuration().getBoolean("includeTotalCount", false)) {
            // A second round trip, and a full count over the filter: only when asked for.
            values.put("totalCount", collection.countDocuments(request.filter()));
        }
        return new Outcome(publish(request, values),
                items.size() + " document(s)" + (hasMore ? ", more available" : ""));
    }

    private static Outcome count(MongoCollection<Document> collection, MongoNodeRequest request) {
        com.mongodb.client.model.CountOptions options = new com.mongodb.client.model.CountOptions()
                .maxTime(request.maxTimeMillis(), TimeUnit.MILLISECONDS);
        if (request.skip() > 0) {
            options.skip(request.skip());
        }
        long count = collection.countDocuments(request.filter(), options);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", count);
        return new Outcome(publish(request, values), count + " document(s)");
    }

    private static Outcome distinct(MongoCollection<Document> collection, MongoNodeRequest request) {
        // BsonValue, not Object: a field's distinct values may be of any BSON type, and the driver has no
        // codec for Object — asking for one fails at execution with "can't find a codec", which says nothing
        // about the field being queried. BsonValue is the one type that can hold whatever comes back.
        DistinctIterable<BsonValue> distinct = collection
                .distinct(request.field(), request.filter(), BsonValue.class)
                .maxTime(request.maxTimeMillis(), TimeUnit.MILLISECONDS);

        List<Object> values = new ArrayList<>();
        try (var cursor = distinct.iterator()) {
            while (cursor.hasNext() && values.size() < request.maxDocuments()) {
                values.add(plain(cursor.next()));
            }
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("values", values);
        outputs.put("count", values.size());
        return new Outcome(publish(request, outputs), values.size() + " distinct value(s)");
    }

    private static Outcome aggregate(MongoCollection<Document> collection, MongoNodeRequest request) {
        var aggregate = collection.aggregate(request.pipeline())
                .maxTime(request.maxTimeMillis(), TimeUnit.MILLISECONDS)
                // Lets the server spill to disk rather than failing at its 100 MiB in-memory stage limit,
                // which is the usual reason a $group over a large collection fails at all.
                .allowDiskUse(request.configuration().getBoolean("allowDiskUse", false));

        List<Map<String, Object>> items = new ArrayList<>();
        try (var cursor = aggregate.iterator()) {
            while (cursor.hasNext() && items.size() < request.maxDocuments()) {
                items.add(normalise(cursor.next()));
            }
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("items", items);
        values.put("count", items.size());
        return new Outcome(publish(request, values), items.size() + " result(s)");
    }

    // ----------------------------------------------------------------------------------------- write

    private static Outcome insertOne(MongoCollection<Document> collection, MongoNodeRequest request) {
        InsertOneResult result = collection.insertOne(request.documents().get(0));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("insertedId", identifier(result.getInsertedId()));
        values.put("insertedCount", 1);
        return new Outcome(publish(request, values), "inserted 1 document");
    }

    private static Outcome insertMany(MongoCollection<Document> collection, MongoNodeRequest request) {
        InsertManyResult result = collection.insertMany(request.documents(),
                new com.mongodb.client.model.InsertManyOptions().ordered(request.ordered()));

        List<Object> ids = new ArrayList<>();
        result.getInsertedIds().values().forEach(id -> ids.add(identifier(id)));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("insertedIds", ids);
        values.put("insertedCount", ids.size());
        return new Outcome(publish(request, values), "inserted " + ids.size() + " document(s)");
    }

    private static Outcome update(MongoCollection<Document> collection, MongoNodeRequest request) {
        UpdateOptions options = new UpdateOptions().upsert(request.upsert());
        UpdateResult result = request.operation() == MongoOperation.UPDATE_ONE
                ? collection.updateOne(request.filter(), request.update(), options)
                : collection.updateMany(request.filter(), request.update(), options);

        return new Outcome(publish(request, updateValues(result)),
                "matched " + result.getMatchedCount() + ", modified " + result.getModifiedCount());
    }

    private static Outcome replaceOne(MongoCollection<Document> collection, MongoNodeRequest request) {
        UpdateResult result = collection.replaceOne(request.filter(), request.documents().get(0),
                new ReplaceOptions().upsert(request.upsert()));

        return new Outcome(publish(request, updateValues(result)),
                "replaced " + result.getModifiedCount() + " document(s)");
    }

    private static Map<String, Object> updateValues(UpdateResult result) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("matchedCount", result.getMatchedCount());
        values.put("modifiedCount", result.getModifiedCount());
        values.put("upsertedId", identifier(result.getUpsertedId()));
        return values;
    }

    private static Outcome delete(MongoCollection<Document> collection, MongoNodeRequest request) {
        DeleteResult result = request.operation() == MongoOperation.DELETE_ONE
                ? collection.deleteOne(request.filter())
                : collection.deleteMany(request.filter());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("deletedCount", result.getDeletedCount());
        return new Outcome(publish(request, values), "deleted " + result.getDeletedCount() + " document(s)");
    }

    /**
     * A batch of writes, optionally inside a transaction.
     *
     * <h2>The transaction is here and nowhere else</h2>
     *
     * A transaction lives for the duration of this one node. That is the whole of the transaction support, and
     * it is deliberate: a session held open across workflow steps would keep a server-side transaction alive
     * while a human task waited for somebody to come back from lunch, hold locks for that long, and be
     * abandoned by the server's own transaction lifetime anyway — and if the engine restarted mid-workflow,
     * there would be nothing left to commit with. "Insert customer, update account, insert audit record,
     * commit" is expressible here as one node with three operations, which is atomic in the way the spec
     * asked for and survives a restart because it either happened or did not.
     */
    private static Outcome bulkWrite(MongoClient client, MongoCollection<Document> collection,
                                     MongoNodeRequest request) {
        List<WriteModel<Document>> models = new ArrayList<>();
        for (Document entry : request.documents()) {
            models.add(model(entry));
        }
        com.mongodb.client.model.BulkWriteOptions options =
                new com.mongodb.client.model.BulkWriteOptions().ordered(request.ordered());

        BulkWriteResult result;
        if (request.useTransaction()) {
            try (ClientSession session = client.startSession()) {
                result = session.withTransaction(() -> collection.bulkWrite(session, models, options));
            }
        } else {
            result = collection.bulkWrite(models, options);
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("insertedCount", result.getInsertedCount());
        values.put("matchedCount", result.getMatchedCount());
        values.put("modifiedCount", result.getModifiedCount());
        values.put("deletedCount", result.getDeletedCount());
        values.put("upsertedCount", result.getUpserts().size());
        values.put("transactional", request.useTransaction());
        List<Object> upsertedIds = new ArrayList<>();
        result.getUpserts().forEach(upsert -> upsertedIds.add(identifier(upsert.getId())));
        values.put("upsertedIds", upsertedIds);

        return new Outcome(publish(request, values), models.size() + " bulk operation(s)"
                + (request.useTransaction() ? ", in a transaction" : ""));
    }

    /**
     * Reads one entry of a bulk write.
     *
     * <p>The shape is MongoDB's own — {@code {"updateOne": {"filter": {}, "update": {}}}} — so a pipeline
     * copied from the shell or from the documentation works unchanged.
     */
    private static WriteModel<Document> model(Document entry) {
        if (entry.size() != 1) {
            throw new IllegalArgumentException("Each bulk operation must name exactly one action, such as "
                    + "insertOne or updateMany. Found: " + String.join(", ", entry.keySet()));
        }
        String action = entry.keySet().iterator().next();
        Document body = entry.get(action, new Document());

        return switch (action.toLowerCase(Locale.ROOT)) {
            case "insertone" -> new InsertOneModel<>(body.get("document", new Document()));
            case "updateone" -> new UpdateOneModel<>(body.get("filter", new Document()),
                    body.get("update", new Document()),
                    new UpdateOptions().upsert(body.getBoolean("upsert", false)));
            case "updatemany" -> new UpdateManyModel<>(body.get("filter", new Document()),
                    body.get("update", new Document()),
                    new UpdateOptions().upsert(body.getBoolean("upsert", false)));
            case "replaceone" -> new ReplaceOneModel<>(body.get("filter", new Document()),
                    body.get("replacement", new Document()),
                    new ReplaceOptions().upsert(body.getBoolean("upsert", false)));
            case "deleteone" -> new DeleteOneModel<>(body.get("filter", new Document()));
            case "deletemany" -> new DeleteManyModel<>(body.get("filter", new Document()));
            default -> throw new IllegalArgumentException("'" + action + "' is not a bulk write action. Use "
                    + "insertOne, updateOne, updateMany, replaceOne, deleteOne or deleteMany.");
        };
    }

    // ----------------------------------------------------------------------------------------- admin

    private static Outcome listCollections(MongoDatabase database, MongoNodeRequest request) {
        List<String> names = new ArrayList<>();
        database.listCollectionNames().forEach(names::add);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("collections", names);
        values.put("count", names.size());
        return new Outcome(publish(request, values), names.size() + " collection(s)");
    }

    private static Outcome collectionStats(MongoDatabase database, MongoNodeRequest request) {
        // collStats through runCommand: the helper was removed from the driver, and $collStats is an
        // aggregation stage with a different shape on different server versions.
        Document stats = database.runCommand(new Document("collStats", request.collection()));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", stats.get("count"));
        values.put("sizeBytes", stats.get("size"));
        values.put("storageSizeBytes", stats.get("storageSize"));
        values.put("averageObjectSizeBytes", stats.get("avgObjSize"));
        values.put("indexCount", stats.get("nindexes"));
        return new Outcome(publish(request, values), "collection statistics");
    }

    private static Outcome createCollection(MongoDatabase database, MongoNodeRequest request) {
        String name = request.collection().isBlank()
                ? request.configuration().getString("targetCollection", "")
                : request.collection();
        database.createCollection(name);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("collection", name);
        return new Outcome(publish(request, values), "created collection " + name);
    }

    private static Outcome renameCollection(MongoDatabase database, MongoNodeRequest request) {
        String target = request.configuration().getString("targetCollection", "");
        database.getCollection(request.collection()).renameCollection(
                new com.mongodb.MongoNamespace(request.database(), target));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("collection", target);
        values.put("previousCollection", request.collection());
        return new Outcome(publish(request, values), "renamed to " + target);
    }

    private static Outcome dropCollection(MongoDatabase database, MongoNodeRequest request) {
        database.getCollection(request.collection()).drop();

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("collection", request.collection());
        values.put("dropped", true);
        return new Outcome(publish(request, values), "dropped collection " + request.collection());
    }

    private static Outcome listIndexes(MongoCollection<Document> collection, MongoNodeRequest request) {
        List<Map<String, Object>> indexes = new ArrayList<>();
        collection.listIndexes().forEach(index -> indexes.add(normalise(index)));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("indexes", indexes);
        values.put("count", indexes.size());
        return new Outcome(publish(request, values), indexes.size() + " index(es)");
    }

    private static Outcome createIndex(MongoCollection<Document> collection, MongoNodeRequest request) {
        Document keys = BsonJson.document(request.configuration().find("keys").orElse(null), "keys",
                value -> value);
        Document configured = request.options();

        IndexOptions options = new IndexOptions();
        if (configured.getBoolean("unique", false)) {
            options.unique(true);
        }
        if (configured.getBoolean("sparse", false)) {
            options.sparse(true);
        }
        if (configured.containsKey("name")) {
            options.name(configured.getString("name"));
        }
        if (configured.containsKey("expireAfterSeconds")) {
            // A TTL index deletes documents on the server's own schedule, with nothing in the workflow to
            // record it. Worth knowing when reading the node back.
            options.expireAfter(configured.get("expireAfterSeconds", Number.class).longValue(),
                    TimeUnit.SECONDS);
        }
        if (configured.containsKey("partialFilterExpression")) {
            options.partialFilterExpression(configured.get("partialFilterExpression", Document.class));
        }
        options.background(configured.getBoolean("background", false));

        String name = collection.createIndex(keys, options);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("indexName", name);
        return new Outcome(publish(request, values), "created index " + name);
    }

    private static Outcome dropIndex(MongoCollection<Document> collection, MongoNodeRequest request) {
        String name = request.field();
        if (!name.isBlank()) {
            collection.dropIndex(name);
        } else {
            collection.dropIndex(BsonJson.document(request.configuration().find("keys").orElse(null), "keys",
                    value -> value));
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("indexName", name);
        values.put("dropped", true);
        return new Outcome(publish(request, values), "dropped index " + (name.isBlank() ? "by keys" : name));
    }

    private static Outcome executeCommand(MongoDatabase database, MongoNodeRequest request) {
        Document command = request.options().isEmpty() ? request.filter() : request.options();
        Document reply = database.runCommand(command, ReadPreference.primary());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reply", normalise(reply));
        values.put("ok", reply.get("ok"));
        return new Outcome(publish(request, values),
                "ran command " + (command.isEmpty() ? "" : command.keySet().iterator().next()));
    }

    private static Outcome testConnection(MongoClient client, MongoDatabase database) {
        Document ping = database.runCommand(new Document("ping", 1));
        Document buildInfo = safeBuildInfo(client);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("connected", ping.get("ok") != null);
        values.put("database", database.getName());
        values.put("serverVersion", buildInfo.getString("version"));
        return new Outcome(values, "connected to MongoDB "
                + String.valueOf(buildInfo.getString("version")));
    }

    /** buildInfo needs a privilege a restricted user may not have; the connection is still fine without it. */
    private static Document safeBuildInfo(MongoClient client) {
        try {
            return client.getDatabase("admin").runCommand(new Document("buildInfo", 1));
        } catch (RuntimeException ex) {
            return new Document("version", "unknown");
        }
    }

    // ---------------------------------------------------------------------------------------- shared

    /**
     * Publishes one result under the operator's chosen name.
     *
     * <h2>Nested, and never with a dot in a key</h2>
     *
     * {@code {"result": {"insertedId": …}}}, not {@code {"result.insertedId": …}}. The engine publishes these
     * into the execution's variable store and persists that store as a MongoDB document, and Spring Data
     * refuses a map key containing a dot — so a flat dotted key throws while <em>saving the execution</em>,
     * after this plugin has already written to the database. The workflow then sits in RUNNING at whatever
     * node last persisted cleanly, with the write done and nothing to show it.
     *
     * <p>Nothing is lost by nesting: {@code VariableMapper} resolves a dotted output name into a structure,
     * so an output mapping of {@code result.insertedId} reads this exactly as before.
     *
     * <p>{@code success} is added at the top level too: the commonest thing a decision node branches on is
     * whether the step worked, and making that reachable without knowing the output variable's name saves
     * every workflow the same lookup.
     */
    private static Map<String, Object> publish(MongoNodeRequest request, Map<String, Object> values) {
        Map<String, Object> nested = new LinkedHashMap<>(values);
        nested.put("success", true);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put(request.outputVariable(), nested);
        outputs.put("success", true);
        return outputs;
    }

    /** Drains a cursor, stopping at the document limit and at the result-size ceiling. */
    private static List<Map<String, Object>> drain(FindIterable<Document> find, MongoNodeRequest request) {
        List<Map<String, Object>> items = new ArrayList<>();
        long bytes = 0;
        try (var cursor = find.iterator()) {
            while (cursor.hasNext()) {
                Document document = cursor.next();
                // An estimate, not a measurement: the point is to stop before the heap does, and paying for
                // an exact size on every document would cost more than the protection is worth.
                bytes += document.toString().length();
                if (bytes > request.maxResultBytes()) {
                    throw new ResultTooLargeException(items.size(), request.maxResultBytes());
                }
                items.add(normalise(document));
                if (items.size() > request.effectiveLimit()) {
                    break;
                }
            }
        }
        return items;
    }

    /** Raised when a read's documents exceed the configured ceiling, before the engine's heap notices. */
    static final class ResultTooLargeException extends RuntimeException {
        ResultTooLargeException(int soFar, long limitBytes) {
            super("The result exceeded " + limitBytes + " bytes after " + soFar + " documents. Narrow the "
                    + "filter, project fewer fields, or page through the results with 'page' and 'pageSize'.");
        }
    }

    private static void applyHintAndCollation(FindIterable<Document> find, MongoNodeRequest request) {
        Document hint = BsonJson.document(request.configuration().find("hint").orElse(null), "hint",
                value -> value);
        if (!hint.isEmpty()) {
            find.hint(hint);
        }
        Document collation = BsonJson.document(request.configuration().find("collation").orElse(null),
                "collation", value -> value);
        if (!collation.isEmpty()) {
            find.collation(com.mongodb.client.model.Collation.builder()
                    .locale(collation.getString("locale"))
                    .caseLevel(collation.getBoolean("caseLevel", false))
                    .numericOrdering(collation.getBoolean("numericOrdering", false))
                    .build());
        }
    }

    private static Bson orNull(Document document) {
        return document == null || document.isEmpty() ? null : document;
    }

    /**
     * Turns a BSON document into values a workflow can carry.
     *
     * <p>An {@code ObjectId} becomes its hexadecimal string and a {@code Date} an ISO-8601 instant, because
     * what happens to these next is a JSON serialisation into an execution record, a comparison in a decision
     * node, or an interpolation into an email. A driver type reaching any of those produces
     * {@code ObjectId{"64f..."}} in the middle of a sentence.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> normalise(Document document) {
        Map<String, Object> plain = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            plain.put(entry.getKey(), normaliseValue(entry.getValue()));
        }
        return plain;
    }

    private static Object normaliseValue(Object value) {
        if (value instanceof Document nested) {
            return normalise(nested);
        }
        if (value instanceof org.bson.types.ObjectId id) {
            return id.toHexString();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof org.bson.types.Decimal128 decimal) {
            return decimal.bigDecimalValue();
        }
        if (value instanceof List<?> list) {
            List<Object> values = new ArrayList<>(list.size());
            for (Object entry : list) {
                values.add(normaliseValue(entry));
            }
            return values;
        }
        return value;
    }

    /**
     * A BSON value as something a workflow can carry.
     *
     * <p>Same destination as {@link #normalise(Document)}, reached from the other direction: this is for
     * values the driver hands back already wrapped, where that one is for a decoded document.
     */
    private static Object plain(BsonValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.asString().getValue();
        }
        if (value.isBoolean()) {
            return value.asBoolean().getValue();
        }
        if (value.isInt32()) {
            return value.asInt32().getValue();
        }
        if (value.isInt64()) {
            return value.asInt64().getValue();
        }
        if (value.isDouble()) {
            return value.asDouble().getValue();
        }
        if (value.isDecimal128()) {
            return value.asDecimal128().getValue().bigDecimalValue();
        }
        if (value.isObjectId()) {
            return value.asObjectId().getValue().toHexString();
        }
        if (value.isDateTime()) {
            return java.time.Instant.ofEpochMilli(value.asDateTime().getValue()).toString();
        }
        if (value.isArray()) {
            List<Object> values = new ArrayList<>();
            value.asArray().forEach(entry -> values.add(plain(entry)));
            return values;
        }
        if (value.isDocument()) {
            Map<String, Object> document = new LinkedHashMap<>();
            value.asDocument().forEach((key, entry) -> document.put(key, plain(entry)));
            return document;
        }
        return value.toString();
    }

    /** An inserted or upserted id, as a string where it is an ObjectId. */
    private static Object identifier(BsonValue value) {
        if (value == null) {
            return null;
        }
        if (value.isObjectId()) {
            return value.asObjectId().getValue().toHexString();
        }
        if (value.isString()) {
            return value.asString().getValue();
        }
        if (value.isNumber()) {
            return value.asNumber().longValue();
        }
        return value.toString();
    }
}
