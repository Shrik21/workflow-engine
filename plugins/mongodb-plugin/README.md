# MongoDB Plugin

Reads and writes MongoDB from a workflow, with the connection configured on the node.

- **Node types:** `MONGODB_READ`, `MONGODB_WRITE`, `MONGODB_ADMIN`
- **Operations:** 21, selected on the node
- **Artifact:** `mongodb-plugin-1.0.1.jar`
- **Runtime:** Java 17, Workflow Plugin SDK 1.0.0, MongoDB Java driver 5.6.5 (bundled)

---

## Why three nodes and not twenty

The operations group by consequence, not by method name. A read cannot lose data; a write changes documents;
an administrative operation changes the shape of the database. That is the line permissions divide along and
the line an operator scanning a workflow cares about. Twenty node types would put `Find One` and
`Drop Collection` side by side in the palette as equals, each one a separate thing to grant, deprecate and
document.

| Node | Operations |
| --- | --- |
| `MONGODB_READ` | `FIND_ONE`, `FIND_MANY`, `COUNT`, `DISTINCT`, `AGGREGATE` |
| `MONGODB_WRITE` | `INSERT_ONE`, `INSERT_MANY`, `UPDATE_ONE`, `UPDATE_MANY`, `REPLACE_ONE`, `DELETE_ONE`, `DELETE_MANY`, `BULK_WRITE` |
| `MONGODB_ADMIN` | `TEST_CONNECTION`, `LIST_COLLECTIONS`, `COLLECTION_STATS`, `CREATE_COLLECTION`, `RENAME_COLLECTION`, `DROP_COLLECTION`, `LIST_INDEXES`, `CREATE_INDEX`, `DROP_INDEX`, `EXECUTE_COMMAND` |

The designer shows only the fields that apply to the chosen operation. That is schema-driven — a `visibleWhen`
condition the plugin publishes with its schema — so it needed no MongoDB-specific front-end component and any
plugin can use it.

---

## Build and install

```bash
mvn -pl plugins/mongodb-plugin -am install
```

The archive bundles its own driver under `lib/`:

```
mongodb-plugin-1.0.1.jar
├── META-INF/MANIFEST.MF            Workflow-Plugin-Class / -Id / -Version / -Api-Version
├── META-INF/workflow-plugin.json   node definitions, permissions, settings
├── com/orchpilot/workflow/plugins/mongodb/*.class
└── lib/
    ├── mongodb-driver-sync-5.6.5.jar
    ├── mongodb-driver-core-5.6.5.jar
    ├── bson-5.6.5.jar
    └── bson-record-codec-5.6.5.jar
```

The engine has a MongoDB driver of its own, for its own database. The plugin class loader is child-first, so
the copy here is what this plugin sees and the engine's is untouched — a workflow can talk to an old
deployment with a driver that supports it without constraining what the engine upgrades to.

Upload the JAR to the Plugin Registry and install it from the marketplace, or install it directly:

```bash
curl -X POST http://localhost:8080/api/plugins/mongodb/versions/1.0.1/install -H "Authorization: Bearer $TOKEN"
```

The three node types appear in the designer palette as soon as the plugin loads. No engine restart, no
front-end release.

---

## Connections

Either a connection string or its parts. The URI wins when both are present, and the parts are then ignored
rather than merged — merging would let a host typed in one field silently override one written in the other.

```
mongodb://mongo.internal:27017/customers
mongodb+srv://cluster0.mongodb.net/customers?retryWrites=true
```

| Field | |
| --- | --- |
| `connectionUri` | Full connection string. **Credentials in it are refused** — see below. |
| `host`, `port` | Used when no URI is given. |
| `database` | Overrides the database named in the URI. |
| `username` | Supports variables. |
| `passwordSecret` | The **name** of a secret holding the password. |
| `credentialId` | Names both halves: `<id>.username` and `<id>.password`. |
| `authenticationDatabase` | Where the user is defined. Usually `admin`. |
| `replicaSet` | |
| `tls`, `tlsAllowInvalidHostnames` | |
| `connectionTimeoutMillis` | Default 10000. |
| `socketTimeoutMillis` | Default 30000. |
| `serverSelectionTimeoutMillis` | Default 15000. A failing-over replica set needs a few seconds here. |
| `maxPoolSize`, `minPoolSize` | Default 20 and 0. |

### Credentials are never in the workflow

`mongodb://admin:s3cret@host/db` is **refused**, with a message saying how to split it. Pasting a working URI
is the most natural thing in the world to do, and a working URI usually carries a password — which would then
live in the workflow definition, every export of it, and its version history.

Three accepted forms, in order of preference:

1. `credentialId: "prod-mongo"` — one name in the workflow, both values in the secret store.
2. `passwordSecret: "mongodb.password"` — the name of a secret holding the password.
3. `password: "${secret.MONGO_PASSWORD}"` — a reference the engine expands.

A literal password is refused the same way. The resolved value is held for one execution, hashed into the
client cache key rather than stored in it, and appears in no log line, error message or output.

### Connection pooling

A `MongoClient` is a pool, a topology monitor and a background thread set — the driver's documentation is
unambiguous that it is meant to be long-lived. This plugin holds one per distinct connection, shared across
executions, keyed on every setting that changes what the connection *is* (including a hash of the password, so
a rotated credential produces a new client rather than reusing one authenticated with the old). Clients unused
for 30 minutes are closed; all of them are closed when the engine unloads the plugin.

Test it with `MONGODB_ADMIN` / `TEST_CONNECTION`, which connects, pings and reports the server version without
touching data.

---

## Workflow variables

Every string in a filter, document, pipeline or option goes through the engine's own resolver. There is one
variable system on this platform and it belongs to the engine.

```json
{ "email": "${form.email}", "department": "${user.department}" }
```

**Substitution happens in the parsed structure, not in JSON text.** A customer named `O"Brien` would otherwise
produce a syntax error at execution, on input that depends on the data.

### Types

A placeholder that is the *entire* value is coerced when the resolved text is unambiguously a number or a
boolean — `{"age": "${form.age}"}` becomes `age: 30`, because `age: "30"` matches nothing in a collection
where age is numeric, and that failure is silent. Anything less obvious keeps its type:

| Written | Resolves to | Stored as |
| --- | --- | --- |
| `"${form.age}"` | `30` | int32 |
| `"${form.postcode}"` | `01234` | string — JSON has no number with a leading zero |
| `"${customer.id}"` | `507f1f77bcf86cd799439011` | **string**, not an ObjectId |
| `"order ${id}"` | `order 1001` | string |

A 24-character hex string stays a string on purpose: a value that *looks* like an ObjectId frequently is not
one, and guessing would turn an ordinary identifier into a type the collection does not hold. MongoDB's own
Extended JSON says it explicitly, and is supported:

```json
{ "_id":       { "$oid": "${customer.id}" } }
{ "createdAt": { "$date": "${system.currentTime}" } }
{ "total":     { "$numberDecimal": "${order.total}" } }
```

`$oid`, `$date`, `$numberInt`, `$numberLong`, `$numberDouble` and `$numberDecimal`. `$date` takes ISO-8601 or
epoch milliseconds.

### Outputs

Set `outputVariable` — say `customerResult` — and the node publishes one nested object of that name holding
every field, plus `success` at the top level.

**Nested, never as dotted keys.** An output key containing a dot becomes a field name in the execution
document, which MongoDB will not store — so `customerResult.insertedId` as a literal key throws while the
*execution is being saved*, after the write has already happened, and the workflow sits in RUNNING at whatever
node last persisted cleanly. That is what 1.0.0 did. Nothing is lost by nesting: the engine resolves a dotted
output name into a structure, so the mappings below are unchanged.

Map them on the node, as any plugin node does:

```json
"outputMapping": {
  "customerResult.insertedId": "workflow.customerId",
  "customerResult.items":      "workflow.customers"
}
```

`${workflow.customerId}` is then available to every later node, and an unprefixed `${customerId}` resolves too.

| Operation | Publishes |
| --- | --- |
| `FIND_ONE` | `found`, `document`, and the document's own fields directly |
| `FIND_MANY` | `items`, `count`, `hasMore`, `skip`, `limit`, optionally `totalCount` |
| `COUNT` | `count` |
| `DISTINCT` | `values`, `count` |
| `AGGREGATE` | `items`, `count` |
| `INSERT_ONE` | `insertedId`, `insertedCount` |
| `INSERT_MANY` | `insertedIds`, `insertedCount` |
| `UPDATE_*`, `REPLACE_ONE` | `matchedCount`, `modifiedCount`, `upsertedId` |
| `DELETE_*` | `deletedCount` |
| `BULK_WRITE` | `insertedCount`, `matchedCount`, `modifiedCount`, `deletedCount`, `upsertedCount`, `upsertedIds`, `transactional` |
| `TEST_CONNECTION` | `connected`, `database`, `serverVersion` |

`success` is published at the top level too, so a decision node can branch on it without knowing the output
variable's name. `ObjectId` becomes its hex string and dates become ISO-8601 instants, because what happens to
these next is a JSON serialisation, a comparison, or an interpolation into an email.

---

## Safety

### Every read is bounded

`find({})` against forty million documents is a valid query whose result does not fit in the engine's heap.
Every read carries a document limit and a server-side `maxTimeMS`, both defaulted rather than optional, and
both capped by ceilings an administrator sets. An operator can lower them and cannot raise them past the
ceiling.

| Setting | Default | |
| --- | --- | --- |
| `maxDocumentsDefault` | 1000 | Documents returned when the node does not say. |
| `maxDocumentsCeiling` | 10000 | The most a node may ask for. |
| `maxTimeMillisDefault` | 30000 | |
| `maxTimeMillisCeiling` | 300000 | |
| `maxResultBytes` | 16 MiB | A read is refused past this, before the heap notices. |

Page with `page`/`pageSize`, or `skip`/`limit`. `hasMore` is a fact, not a guess: the query asks for one more
document than it returns.

### Bulk operations are confirmed

`UPDATE_MANY`, `DELETE_MANY`, `REPLACE_ONE`, `BULK_WRITE`, `DROP_COLLECTION`, `RENAME_COLLECTION`,
`DROP_INDEX` and `EXECUTE_COMMAND` require `confirmed: true` on the node. The refusal says what the operation
would do, not merely that confirmation is required — ticking a box without knowing that Replace One drops
every field it does not mention is not confirmation.

**An empty filter on a bulk write is refused separately.** The usual cause is a variable that resolved to
nothing, which collapses the filter to `{}` and deletes the collection's contents while reporting success.
`allowEmptyFilter: true` says the whole collection really is the target.

### Update One is not Replace One

An update whose keys are not operators is refused, pointing at Replace One. A replacement made of operators is
refused, pointing at Update One. In the shell the first of those silently replaces the document, and the
difference is every field it had.

### Permissions

Ten permission names, declared in the manifest and enforced against the acting user's roles:

```
MONGODB_CONNECT   MONGODB_READ    MONGODB_INSERT   MONGODB_UPDATE            MONGODB_DELETE
MONGODB_AGGREGATE MONGODB_INDEX_MANAGE MONGODB_COLLECTION_MANAGE MONGODB_TRANSACTION MONGODB_COMMAND_EXECUTE
```

**These are not engine authorities.** The engine's `Permission` enum is compiled in and a plugin cannot add to
it — deliberately, since a platform where installing a plugin invents authorities is one where installing a
plugin can grant them. Enforcement is therefore in two places:

1. **The engine**, authoritatively: running any node takes `WORKFLOW_EXECUTE`; editing the node that names the
   operation takes `WORKFLOW_EDIT`.
2. **This plugin**, as defence in depth: an administrator maps permissions to roles in the installation
   settings, and the acting user's roles are checked at execution.

```properties
permission.mongodb_delete=ADMIN,DATA_STEWARD
permission.mongodb_command_execute=ADMIN
```

An unmapped permission is open — a plugin that refused everything until configured is one nobody can use, and
the engine's gate is still in front. A **mapped** permission refuses executions with no user at all (schedules
and event triggers), because "this role and nobody else" has to include the timer.

Individual operations can be switched off entirely:

```properties
operation.execute_command.enabled=true    # off by default: the widest reach this plugin has
operation.drop_collection.enabled=false
```

### What is never logged

Filters, documents, credentials, connection strings with credentials in them. A successful execution logs the
operation, the database and collection, the duration and a summary — an execution log is read by more people
than the collection is. Set `logQueries=true` to write filters at debug level; that is data, which is why it
is an administrator's decision.

---

## Errors

| Code | Retried | |
| --- | --- | --- |
| `MONGO_CONFIGURATION_INVALID` | no | Every problem reported at once, not one per run. |
| `MONGO_VALIDATION_ERROR` | no | Malformed query shape, caught before the server sees it. |
| `MONGO_CONFIRMATION_REQUIRED` | no | A bulk or destructive operation without `confirmed`. |
| `MONGO_PERMISSION_DENIED` | no | A mapped permission, or a switched-off operation. |
| `MONGO_AUTHENTICATION_ERROR` | **no** | Repeating a rejected credential is another failed sign-in. |
| `MONGO_CONNECTION_ERROR` | yes | Unless the host does not resolve. |
| `MONGO_TIMEOUT` | socket only | A server-side `maxTimeMS` expiry is not retried: the same slow query follows. |
| `MONGO_DUPLICATE_KEY` | no | A second attempt produces the same duplicate. |
| `MONGO_NAMESPACE_NOT_FOUND` | no | |
| `MONGO_NOT_SUPPORTED` | no | Transactions on a standalone server arrive here. |
| `MONGO_RESULT_TOO_LARGE` | no | Narrow the filter or page through it. |
| `MONGO_TLS_ERROR` | no | |
| `MONGO_QUERY_ERROR` / `MONGO_WRITE_ERROR` | no | |

These pair with the engine's own error policies on the node: `FAIL_WORKFLOW`, `RETRY`, `SKIP`, `CONTINUE` (which
publishes `failed`, `errorCode` and `errorMessage` for a decision node to branch on) and `COMPENSATE`.

An authentication failure also discards the pooled client, so a rotated password does not fail every execution
until the plugin reloads.

---

## Transactions

`BULK_WRITE` with `useTransaction: true`, on a replica set or sharded cluster. Insert a customer, update an
account and insert an audit record in one node, atomically.

**The transaction lives for that one node and no longer.** A session held open across workflow steps would
keep a server-side transaction alive while a human task waited for somebody to come back from lunch, hold its
locks for that long, be killed by the server's own transaction lifetime anyway, and have nothing left to
commit with if the engine restarted mid-workflow. Expressing the unit of atomicity as one node is what makes
it survive a restart: it either happened or it did not.

A standalone server refuses transactions with `MONGO_NOT_SUPPORTED`, which says so.

---

## Health

```java
plugin.health()
// { plugin, version, status, pooledConnections, nodeTypes, driverVersion }
```

Deliberately not a live probe of every configured connection: this plugin has no list of them — a connection
belongs to a node — and pinging every database on a monitoring interval would make that interval into load
somebody else pays for. Use a `TEST_CONNECTION` node for a specific deployment.

---

## Tests

```bash
mvn -pl plugins/mongodb-plugin test                                   # 79 unit tests
WORKFLOW_IT_MONGODB_URI=mongodb://localhost:27017 \
  mvn -pl plugins/mongodb-plugin verify -Pintegration-tests           # + 23 against a real server
mvn -pl plugins/mongodb-plugin verify -Pintegration-tests             # the same, under Testcontainers
```

The integration tests need a MongoDB and are opt-in for that reason. There is **no localhost default**: they
write and drop collections, and a suite that silently connects to whatever MongoDB it finds eventually drops
something that mattered. They confine themselves to a database called `mongodb_plugin_it` and drop only that.

---

## Examples

[`examples/`](examples/) holds a complete workflow and a configuration for each of the scenarios in the
specification: insert, find, update, aggregate, transaction, MongoDB → REST API, form → MongoDB,
MongoDB → decision, and MongoDB → email.
