package com.orchpilot.workflow.plugins.mongodb;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a MongoDB node does, and what it takes to be allowed to do it.
 *
 * <h2>Three nodes, not twenty</h2>
 *
 * The operations are grouped by what an operator is doing — reading, writing, administering — because that is
 * the axis along which permissions and risk actually divide. Twenty node types would put {@code Find One} and
 * {@code Drop Collection} on the same footing in the palette, each one a separate thing to grant, deprecate
 * and document, when the difference that matters is that one of them cannot lose data and the other can.
 *
 * <h2>Every operation names its permission</h2>
 *
 * The permission is on the operation rather than the node, so a single write node cannot be used to delete by
 * an operator who was only granted inserts. See {@link MongoGuards} for where that is enforced, and for why
 * the engine remains the authority.
 */
enum MongoOperation {

    // ------------------------------------------------------------------------ read
    FIND_ONE("Find One", MongoPermission.READ, Risk.SAFE),
    FIND_MANY("Find Many", MongoPermission.READ, Risk.SAFE),
    COUNT("Count", MongoPermission.READ, Risk.SAFE),
    DISTINCT("Distinct", MongoPermission.READ, Risk.SAFE),
    AGGREGATE("Aggregate", MongoPermission.AGGREGATE, Risk.SAFE),

    // ----------------------------------------------------------------------- write
    INSERT_ONE("Insert One", MongoPermission.INSERT, Risk.SAFE),
    INSERT_MANY("Insert Many", MongoPermission.INSERT, Risk.SAFE),
    UPDATE_ONE("Update One", MongoPermission.UPDATE, Risk.SAFE),

    /** Touches every matching document, so it is confirmed like a bulk delete. */
    UPDATE_MANY("Update Many", MongoPermission.UPDATE, Risk.BULK),

    /**
     * Overwrites a document entirely.
     *
     * <p>Distinct from Update One and deliberately harder to reach by accident: an update with a missing
     * {@code $set} silently becomes a replacement in the shell, and the difference between the two is every
     * field the document had.
     */
    REPLACE_ONE("Replace One", MongoPermission.UPDATE, Risk.BULK),

    DELETE_ONE("Delete One", MongoPermission.DELETE, Risk.SAFE),
    DELETE_MANY("Delete Many", MongoPermission.DELETE, Risk.BULK),
    BULK_WRITE("Bulk Write", MongoPermission.UPDATE, Risk.BULK),

    // ----------------------------------------------------------------------- admin
    LIST_COLLECTIONS("List Collections", MongoPermission.READ, Risk.SAFE),
    COLLECTION_STATS("Collection Stats", MongoPermission.READ, Risk.SAFE),
    CREATE_COLLECTION("Create Collection", MongoPermission.COLLECTION_MANAGE, Risk.SAFE),
    RENAME_COLLECTION("Rename Collection", MongoPermission.COLLECTION_MANAGE, Risk.DESTRUCTIVE),
    DROP_COLLECTION("Drop Collection", MongoPermission.COLLECTION_MANAGE, Risk.DESTRUCTIVE),
    LIST_INDEXES("List Indexes", MongoPermission.READ, Risk.SAFE),
    CREATE_INDEX("Create Index", MongoPermission.INDEX_MANAGE, Risk.SAFE),
    DROP_INDEX("Drop Index", MongoPermission.INDEX_MANAGE, Risk.DESTRUCTIVE),

    /**
     * An arbitrary database command.
     *
     * <p>The most privileged thing this plugin offers: {@code runCommand} reaches everything the connected
     * credential can do, including operations no other node here exposes. It has a permission of its own for
     * that reason, and is off unless an administrator turns it on.
     */
    EXECUTE_COMMAND("Execute Command", MongoPermission.COMMAND_EXECUTE, Risk.DESTRUCTIVE),

    /** Connects, pings, and reports the server version without touching data. */
    TEST_CONNECTION("Test Connection", MongoPermission.CONNECT, Risk.SAFE);

    /** How much damage getting this wrong does, which decides what it takes to run it. */
    enum Risk {
        /** Ordinary. Bounded by the filter, or creates something new. */
        SAFE,

        /** Affects every matching document. Requires the node's explicit confirmation flag. */
        BULK,

        /** Loses data or grants reach beyond this plugin's other operations. Confirmed and permitted. */
        DESTRUCTIVE
    }

    private final String label;
    private final MongoPermission permission;
    private final Risk risk;

    MongoOperation(String label, MongoPermission permission, Risk risk) {
        this.label = label;
        this.permission = permission;
        this.risk = risk;
    }

    String label() {
        return label;
    }

    MongoPermission permission() {
        return permission;
    }

    Risk risk() {
        return risk;
    }

    /** @return whether this operation needs the node's confirmation flag before it will run */
    boolean requiresConfirmation() {
        return risk != Risk.SAFE;
    }

    /**
     * @param value a configured name, in any case, with spaces or dashes
     * @return the operation, or empty when the name is not one — never a default, because guessing which
     *         database operation was meant is not a thing to do
     */
    static Optional<MongoOperation> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String name = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return Arrays.stream(values()).filter(operation -> operation.name().equals(name)).findFirst();
    }

    /** @return every operation name, for the configuration schema's dropdown */
    static Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        for (MongoOperation operation : values()) {
            names.add(operation.name());
        }
        return names;
    }
}
