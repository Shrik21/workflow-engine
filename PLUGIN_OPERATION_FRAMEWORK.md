# Plugin Operation Framework — Architecture

**One plugin = one generic node, with a metadata-driven operation dropdown.**

Status: **REVISED — see §0.0. The recommended approach is now the UI facade, not the full framework.**

---

## 0.0 Revision: the facade approach supersedes §9's plan

The original plan below (nine phases, SDK changes, engine changes, migration tooling) was designed to make the
*engine* operation-aware. After review, that is solving the problem at the wrong layer.

**The requirement is a UI requirement.** "One node per plugin with an operation dropdown" is about what the
user sees in the palette and the property panel. Nothing about it requires the engine to change — and the
engine is where all the risk lives.

### The facade

Keep every plugin's node types exactly as they are. Present them differently:

```
Palette groups catalogue entries by pluginId    →  ONE "Kubernetes" entry, not 45
User drags it                                   →  node created with a default nodeType
Property panel shows an Operation dropdown      →  the 45 nodeTypes, labelled by displayName
User picks "Delete Pod"                         →  patch({ nodeType: 'K8S_DELETE_POD' })
entry() recomputes → SchemaForm re-renders      →  ALREADY WORKS, no change needed
Workflow JSON stores nodeType: K8S_DELETE_POD   →  unchanged from today
```

The whole reactive chain already exists. `node-properties.ts` line 707 computes
`resolveCatalogEntry(node, catalog.entries())` and feeds `entry()?.configurationSchema` to `SchemaForm`.
Changing `nodeType` re-renders the form for free.

### What this costs vs. the nine-phase plan

| | Full framework | **Facade** |
|---|---|---|
| SDK changes | `OperationDefinition`, `getOperations()` | **none** |
| Engine changes | executor, registry, validator, AI tools | **none** |
| Plugin changes | all 12 rewritten | **none** |
| Workflow JSON change | yes → migration + shims | **none** |
| Backward-compat risk | shims mandatory before any collapse | **none — nothing changed** |
| Angular changes | selector + form extensions | selector + palette grouping |
| Phases | 9 | **2** |

### What the facade gives, identical to the full framework

- One node per plugin in the palette — **~180 entries → 12**
- Operation dropdown, searchable and grouped
- Form changes with the selected operation
- Zero Angular per plugin; a new plugin appears with no UI work
- Node shows plugin icon + name + selected operation

### What the facade gives *better*, for free

Per-operation risk, per-operation AI tools and per-operation approval **already work correctly today**, because
each operation is still its own node type. The full framework had to *rebuild* all three (Phases 3–4, the only
medium-risk phases) purely to get back to where the facade starts.

That was the original objection to one-node-per-plugin, and the facade never introduces it.

### What is genuinely lost

1. **Workflow JSON keeps `nodeType: K8S_DELETE_POD`** rather than `operationId: delete-pod`. The spec asked for
   the latter. This is a naming difference in stored config, invisible to users, and arguably more explicit for
   the engine — there is no dispatch step at execution.
2. **No explicit 5-level `RiskLevel`** — only the existing `destructive` boolean. Operations that want finer
   grading publish it in the manifest today (they already do) but the engine acts on the boolean.
3. **No operation `DEPRECATED` lifecycle.** A removed node type shows the existing "plugin does not provide this
   node type" warning instead.

All three are **additive later**, on top of the facade, without redoing it.

### Two design decisions the facade needs

**(a) MongoDB's double dropdown.** MongoDB already has 3 node types *each with its own internal `operation`
field*. Under the facade its dropdown reads "MongoDB Read / Write / Admin", and picking one reveals a second
operation dropdown. That is the one place the facade looks odd.
→ **Decision: leave it.** It is one plugin out of twelve, it is not wrong, and collapsing MongoDB's three nodes
into one is a small independent change that can follow later.

**(b) Configuration when the operation changes.** Switching Delete Pod → List Pods leaves `confirmed: true`
in the configuration.
→ **Decision: carry over keys the new operation's schema declares, drop the rest, and show a one-line note
naming what was dropped.** Keeping orphans pollutes the stored JSON; dropping silently loses work invisibly.

---

## 0. Executive summary — read this first

I audited the codebase before designing. **Roughly 70% of what this specification asks for already exists and
is in production use.** The MongoDB plugin already implements the exact target pattern.

That changes what this project is. It is not "build a metadata-driven plugin framework". It is:

1. **One genuine architectural change** — move risk, permissions and approval from the *node* level to the
   *operation* level. Everything else is blocked behind this, and it is the only part that touches the engine's
   security model.
2. **A consistency migration** — four plugins I built recently went the other way (Kubernetes 45 node types,
   Jira 39, GitHub 36, Excel 27). They need collapsing.
3. **Incremental UI extensions** — dependent dropdowns and more field types.

### What already exists

| Requirement | Status | Where |
|---|---|---|
| Schema-driven form rendering, no per-plugin Angular | ✅ **Done** | `SchemaForm`, `schema-fields.ts` |
| `visibleWhen` conditional field visibility | ✅ **Done** | `schema-fields.ts` — its own doc example is `{ operation: ['FIND_MANY'] }` |
| Operation dropdown inside one node | ✅ **Done** | `MongoDbPlugin`, `VpnPlugin` |
| Plugin metadata → registry → designer | ✅ **Done** | `workflow-plugin.json` → `PluginRegistry` → `NodeApiService` |
| Node stores `pluginId` + config, no UI metadata | ✅ **Done** | `WorkflowNode` |
| Plugin install/update/enable/disable | ✅ **Done** | `DefaultPluginManager`, Plugin Server |
| Metadata caching + invalidation | ✅ **Done** | `PluginRegistry` |
| Runtime re-validation, RBAC, secrets, tenancy | ✅ **Done** | `PluginNodeExecutor`, `ScopedSecretProvider` |
| Import/export stores `pluginId` + config only | ✅ **Done** | `WorkflowPackage` |
| Field types | ⚠️ **8 of ~30** | text, textarea, number, boolean, select, secret-ref, key-value, json |
| **Operation-level risk / permissions / approval** | ❌ **Missing** | *the blocker* |
| **One node per plugin** | ❌ **Inconsistent** | 6 plugins declare 2–45 node types |
| Dependent dropdowns (`optionsEndpoint`, `dependsOn`) | ❌ **Missing** | |
| Operation grouping, search, lifecycle | ❌ **Missing** | |
| Migration tooling for existing workflows | ❌ **Missing** | |

### The proof it already works

`MongoDbPlugin` ships three node types — `MONGODB_READ`, `MONGODB_WRITE`, `MONGODB_ADMIN` — each carrying an
`operation` dropdown (`FIND_ONE`, `FIND_MANY`, `INSERT_ONE`, `UPDATE_MANY`, `DELETE_ONE`, `AGGREGATE`, …) with
`visibleWhen` hiding the fields that do not apply. Its own source comment says why:

> *"A node with one operation selector and every field for every operation on screen at once is a form with
> forty controls, of which six matter. `visibleWhen` is read by the designer's schema form and hides the rest —
> schema-driven, so it works for any plugin that wants it and needed no MongoDB-specific component in the front
> end."*

**So why three nodes instead of one?** Because `destructive` is a per-node-type boolean. Splitting by risk class
is the only way, today, to let `MONGODB_READ` be safe while `MONGODB_ADMIN` requires approval. That workaround
*is* the problem this design removes.

---

## 1. Current architecture

```
Plugin JAR
  └─ WorkflowNodePlugin
       ├─ getNodeDefinitions() → List<NodeDefinition>     ← N node types per plugin
       └─ execute(NodeExecutionContext) → NodeExecutionResult
                                    │
DefaultPluginManager ──► PluginRegistry ──► /api/nodes ──► node palette
                                    │
WorkflowNode{type:PLUGIN, pluginId, pluginVersion, nodeType, configuration}
                                    │
                          PluginNodeExecutor          ← the single bridge
                          ├─ version pinning
                          ├─ lease acquisition
                          ├─ idempotency replay
                          ├─ variable resolution
                          └─ execution recording
                                    │
                          NodeDefinition.destructive() ──► AI approval
```

`NodeDefinition` carries: `nodeType`, `displayName`, `description`, `category`, `icon`,
`configurationSchema`, `idempotent`, `supportsRetry`, `supportsAI`, `destructive`.

**Every behavioural flag is per node type.** That single fact is what forces N nodes per plugin.

### The two things that break at one-node-per-plugin

**1. Approval granularity.** `ToolApprovalPolicy` line 60:

```java
if (supervised && tool.isDestructive() && !approved.contains(name)) { … }
```

`isDestructive()` comes from `definition.destructive()`. Collapse MongoDB to one node and you must choose:
mark it destructive (every `find` needs approval — unusable) or not (every `deleteMany` runs unapproved —
unsafe).

**2. AI tool granularity.** `PluginAIToolAdapter` is **one tool per node type**. Collapse GCP's 10 nodes to one
and the agent sees a single `gcp` tool whose schema is a union of every operation's fields. Tool selection
accuracy collapses; the model must now guess an `operation` enum value instead of picking a named tool.

**Neither is a UI problem. Both are engine problems, and both are solved by the same change.**

---

## 2. Proposed architecture

Promote the operation to a first-class concept the engine understands.

```
Plugin JAR
  └─ WorkflowNodePlugin
       ├─ getNodeDefinitions() → ONE NodeDefinition per plugin
       ├─ getOperations()      → List<OperationDefinition>   ← NEW
       └─ execute(ctx)  ── dispatches on ctx.configuration().operation
                                    │
PluginRegistry ──► operations indexed by (pluginId, operationId)
       │
       ├──► /api/nodes                      → one node per plugin (palette)
       ├──► /api/plugins/{id}/operations     → operation list (dropdown)
       └──► /api/plugins/{id}/operations/{op}→ field schema (dynamic form)
                                    │
WorkflowNode{type:PLUGIN, pluginId, pluginVersion, nodeType, configuration{operation, …}}
                                    │
                          PluginNodeExecutor
                          ├─ resolve operation ────────────► OperationDefinition
                          ├─ validate against ITS schema
                          ├─ check ITS permissions
                          ├─ apply ITS risk level  ────────► approval
                          ├─ apply ITS retry/timeout policy
                          └─ record ITS operationId in audit
                                    │
                    AI tools = one per OPERATION, not per node
```

### Before / after

| | Before | After |
|---|---|---|
| Node types in palette | 45 for Kubernetes | 1 |
| AI tools exposed | 45 | 45 *(unchanged — from operations)* |
| Risk granularity | per node type | **per operation** |
| Permissions | plugin-wide | **per operation** |
| Retry / timeout / idempotency | per node type | **per operation** |
| Angular components per plugin | 0 | 0 *(unchanged)* |
| Workflow JSON | `nodeType: K8S_DELETE_POD` | `nodeType: KUBERNETES`, `configuration.operation: DELETE_POD` |

**The AI agent keeps exactly the granularity it has today.** That is the point of putting operations in the
engine rather than hiding them inside one node's schema.

---

## 3. Models

### `OperationDefinition` (new, in the SDK)

```java
public record OperationDefinition(
    String id,                     // "find-many"
    String name,                   // "Find Many"
    String description,
    String group,                  // "Query" — for grouped dropdowns
    RiskLevel risk,                // READ | LOW | MEDIUM | HIGH | CRITICAL
    Set<String> permissions,       // "MONGODB_READ"
    Map<String, Object> schema,    // JSON-schema fields for THIS operation
    Map<String, Object> output,    // declared output shape
    ExecutionMode mode,            // SYNC | ASYNC | POLLING
    RetryPolicy retry,             // supported, maxRetries, backoff, retryableErrors
    Duration defaultTimeout,
    Duration maximumTimeout,
    boolean idempotent,
    boolean supportsAI,
    Lifecycle lifecycle,           // AVAILABLE | DISABLED | DEPRECATED | REMOVED
    int schemaVersion              // for compatibility checks
) {}
```

`RiskLevel` maps onto the existing `destructive` boolean so **nothing downstream changes**:

```java
public boolean destructive() { return risk == HIGH || risk == CRITICAL; }
```

This is the whole trick. `ToolApprovalPolicy`, `PluginAIToolAdapter` and every approval path keep working
against a boolean; they just get it from the operation instead of the node.

### `FieldDefinition`

Fields stay **JSON Schema** — the format `SchemaBuilder` already emits and `SchemaForm` already renders —
extended with the properties this spec needs. Extending beats replacing: every existing plugin schema stays
valid, and `SchemaForm` degrades gracefully on properties it does not yet understand.

```jsonc
{
  "type": "string",
  "title": "Zone",
  "format": "dynamic-dropdown",        // NEW: field type hint
  "dependsOn": ["connection", "region"],// NEW: reload when these change
  "optionsEndpoint": "gcp/zones",       // NEW: plugin-provided options
  "visibleWhen": { "operation": ["CREATE_VM"] },   // EXISTS
  "requiredWhen": { "operation": ["CREATE_VM"] },  // NEW
  "allowExpression": true               // NEW: ${...} permitted
}
```

### Workflow node — unchanged on disk

```json
{
  "id": "node-123",
  "type": "PLUGIN",
  "pluginId": "orchpilot-github",
  "pluginVersion": "1.0.0",
  "nodeType": "GITHUB",
  "configuration": {
    "operation": "create-pull-request",
    "connection": "github-prod",
    "sourceBranch": "${sourceBranch}",
    "targetBranch": "main"
  }
}
```

`operation` is **ordinary configuration**, not a new top-level field. That means zero changes to `WorkflowNode`,
to persistence, to import/export, or to the portability package format — and an old engine reading a new
workflow still sees a well-formed node.

> **DECIDED — `operation` lives in `configuration`, not as a top-level field.** See §3.1.

### 3.1 Decision: where `operation` lives

**`configuration.operation`. Not a top-level `operationId`.**

| | Top-level `operationId` | `configuration.operation` ✅ |
|---|---|---|
| Migration of stored workflows | required | **none** |
| Change to `WorkflowNode` model | yes | **none** |
| Change to `WorkflowPackage` / export | yes | **none** |
| Variable resolution applies | needs new plumbing | **already does** |
| Old engine reads new workflow | malformed node | **well-formed** |
| Query "which workflows use operation X" | direct field | index on `nodes.configuration.operation` |

The only real argument for a top-level field is queryability, and a Mongo index on
`nodes.configuration.operation` answers it just as well. Every other column favours configuration — and the
specification's own rule is *"store business configuration, not UI configuration."* The operation **is**
business configuration.

### 3.2 Decision: how operation permissions are enforced

**Operations may only declare permissions that already exist in the engine's `Permission` enum. An unknown
name fails validation at plugin install time.**

The alternative — free-form permission strings — quietly requires a *new* grant system: something has to decide
who holds `MONGODB_READ`, store it, and expose it in the admin UI. That is a second authorization system, which
this project explicitly forbids.

Failing at **install** rather than at execution matters: an unknown permission that fails open is a silent hole,
and one that fails closed at runtime is a workflow that breaks in production having passed every test. Rejecting
the JAR tells the plugin author immediately, and adding a genuinely new permission stays an engine change —
which is correct, because granting permissions is an engine concern.

So enforcement has two layers, both existing:

1. **Workflow RBAC** — `canEdit` / `canExecute`, unchanged.
2. **Operation risk → approval** — `ToolApprovalPolicy`, fed per-operation risk instead of per-node.

Declared permissions are the *third*, optional layer, enforced only where the name is real.

### 3.3 Decision: field types — build 12, not 30

The spec lists ~30 field types. Building all of them before a single plugin uses them is speculative work that
will be wrong in places nothing exercises. Build what the twelve existing plugins actually need:

**Have (8):** text · textarea · number · boolean · select · secret-reference · key-value · json
**Add (4):** `connection-selector` · `dynamic-dropdown` · `variable-picker` · `file-reference`

The remaining ~18 (cron, date, time, url, email, table, code-editor, user-selector, …) are added **when a plugin
declares one**. `SchemaForm` already degrades unknown formats to a text control, so an unbuilt type is a plain
input rather than a crash — which makes deferring them safe.

---

## 4. Java SDK

```java
public interface WorkflowNodePlugin {
    List<NodeDefinition> getNodeDefinitions();              // existing
    NodeExecutionResult execute(NodeExecutionContext ctx);  // existing

    /** NEW — default returns empty, so every existing plugin still compiles. */
    default List<OperationDefinition> getOperations() { return List.of(); }
}
```

**A `default` method, exactly as `files()` was.** Binary-compatible: the eleven shipped plugin JARs keep working
untouched, and a plugin adopts operations when it chooses. That approach is already proven in this codebase.

Authoring stays declarative:

```java
OperationDefinition.builder("find-many", "Find Many")
    .group("Query")
    .risk(RiskLevel.READ)
    .permission("MONGODB_READ")
    .schema(SchemaBuilder.object()
        .connectionRef("connection", "Connection", true)
        .string("database", "Database", true)
        .dynamicDropdown("collection", "Collection", "mongodb/collections", "connection", "database")
        .json("filter", "Filter", false)
        .integer("limit", "Limit", false)
        .build())
    .outputArray()
    .idempotent(true)
    .build();
```

---

## 5. Runtime execution

`PluginNodeExecutor` gains one resolution step. **It remains the single bridge** — every trigger type (manual,
scheduled, API, event, form, AI, workflow-to-workflow) already routes through it, so all execution modes are
covered with no separate implementations.

```
resolve plugin (version-pinned)         [exists]
resolve operation from configuration    [NEW]
   ├─ not found      → OPERATION_NOT_FOUND
   ├─ disabled       → OPERATION_DISABLED
   ├─ removed        → OPERATION_VERSION_INCOMPATIBLE
   └─ schemaVersion mismatch → warn, attempt
validate config against operation schema[NEW]
check operation permissions vs RBAC     [NEW]
apply operation risk → approval         [NEW]
acquire lease                           [exists]
idempotency replay                      [exists — now per-operation]
resolve variables + secrets             [exists]
execute with operation timeout/retry    [NEW policy source]
record audit incl. operationId          [extended]
```

Every one of these existed; four now read their inputs from the operation rather than the node.

---

## 6. Angular

**No new plugin-specific components. Ever.** Five reusable additions:

| Component | Purpose |
|---|---|
| `OperationSelector` | searchable, grouped dropdown from `/operations` |
| `DynamicDropdown` | `optionsEndpoint` + `dependsOn` cascading |
| `VariablePicker` | insert `${…}` from workflow vars / previous node outputs |
| `ConnectionSelector` | reuses the existing credential system |
| `OperationBanner` | "operation unavailable in installed version" warning |

`SchemaForm` is **extended, not replaced** — new `format` hints and `requiredWhen`, on top of the `visibleWhen`
it already evaluates.

Node rendering shows plugin icon + name + selected operation, which is a label change in `NodeGlyph`.

---

## 7. Security

Unchanged in structure — every control already exists and is reused:

| Control | Mechanism |
|---|---|
| Authentication | existing JWT filter |
| Authorisation | existing `Permission` enum + `Role` + groups; operations declare permission names |
| Approval | existing `ToolApprovalPolicy`, now fed operation risk |
| Secrets | existing `ScopedSecretProvider`; `secret-reference` fields store names, never values |
| Tenancy | existing nullable `tenantId`; connections stay tenant-scoped |
| Runtime re-validation | `PluginNodeExecutor` validates independently of the UI |

**`visibleWhen` is presentation only and must never be treated as a control** — a hidden field that is set
anyway is still submitted and still validated. The MongoDB plugin's source already says this; it belongs in the
framework docs.

---

## 8. Versioning, migration, backward compatibility

**Three independent layers**, so a plugin update never silently changes behaviour:

- **Plugin version** — `1.0.0`, pinned per node, already enforced
- **Operation lifecycle** — `AVAILABLE → DEPRECATED → REMOVED`; the UI hides non-available operations but a
  stored workflow referencing one still *opens*, with a warning
- **Operation schema version** — an integer; a mismatch warns rather than fails, because failing would break a
  published workflow on a patch release

### Migration: opt-in, never automatic

```
Old:  nodeType: K8S_DELETE_POD, configuration: {…}
New:  nodeType: KUBERNETES,     configuration: { operation: "delete-pod", … }
```

A `PluginNodeMigration` service provides:

1. **Dry run** — report every affected node across every workflow, with no writes
2. **Per-workflow migration** — draft only; the published version is untouched
3. **Compatibility shim** — the collapsed plugin keeps its old node types registered as
   `Lifecycle.DEPRECATED` aliases that map to the new operation, so **existing published workflows keep running
   unchanged**

The shim is what makes this safe. Without it, installing an updated plugin would break every workflow using it
the moment it loads.

> **Production workflows are never auto-migrated.** The spec asks for this and I agree with it strongly enough
> to make it structural: migration writes to drafts only, and publishing stays a human action.

---

## 9. Implementation plan

> **Superseded by §0.0.** The two-phase facade plan below replaces this. The nine-phase plan is kept because
> it documents what the full engine-level framework would cost, which is the argument for not building it yet —
> and because §3.1–3.3's decisions still apply if it is ever revisited.

### The plan to build: two phases, Angular only

**Both phases are implemented.** 33 unit tests in `plugin-operations.spec.ts`; the Angular suite is green
apart from one pre-existing, unrelated `FormNodeConfig` failure. No server change, no workflow JSON change.

**Phase 1 · Palette grouping + operation selector** · ✅ done · *no server changes*

- `node-palette.ts`: group plugin entries by `pluginId` (built-in nodes keep grouping by `category`); one row
  per plugin showing its icon, name and operation count
- On drag: create the node with the plugin's default node type — the first entry, or one the manifest marks
- `node-properties.ts`: an **Operation** dropdown above the schema form, listing that plugin's node types by
  `displayName`, searchable, grouped by `category`
- On change: `patch({ nodeType })` plus the config carry-over rule from §0.0(b)
- `NodeGlyph` / canvas label: plugin name on line 1, operation on line 2
- *Tests:* palette shows one row per plugin; selecting an operation swaps the schema; config carry-over keeps
  declared keys and drops the rest; a node whose plugin is uninstalled still renders its saved values
- *Acceptance:* palette drops from ~180 rows to 12; changing operation changes the form; **no server change,
  no workflow JSON change, every existing workflow opens and runs untouched**

**Phase 2 · Polish** · ✅ done

Operation search and dropdown grouping were built in Phase 1, so Phase 2 became the third item — which turned
out to be larger and more valuable than scoped:

- **Removed-operation recovery.** When a plugin update drops an operation, `resolveCatalogEntry` returns
  nothing, so the panel said *"No loaded plugin provides X"* — misleading, since the plugin is installed and
  healthy — and the operation dropdown disappeared, leaving the author unable to repoint the node without
  editing raw JSON. `operationsFor` now falls back to the node's own `pluginId`, and `operationStatus`
  distinguishes `PLUGIN_MISSING` from `OPERATION_MISSING` so the two get opposite advice. The vanished
  operation stays visible as a disabled option rather than rendering the select blank.
- **Label and plugin-id search.** Filtering moved from catalogue entries to palette rows, so "Excel Handler"
  (a marketplace name appearing in no node type or description) matches, and "sendgrid" still does.
- **Honest narrowed meta.** A row narrowed by search to one operation names it instead of claiming
  "27 operations".

### Optional follow-ups, each independent

| | Value | Cost |
|---|---|---|
| Collapse MongoDB 3 → 1 (removes the double dropdown) | small | S |
| `RiskLevel` 5-level grading in the manifest + engine | medium | M |
| Operation `DEPRECATED` lifecycle | small | M |
| `operationId` in workflow JSON instead of `nodeType` | cosmetic | **L — migration + shims** |

The last row is the whole original plan. Its value is cosmetic; its cost is the highest of anything here.
That asymmetry is the case for the facade.

---

## 9-OLD. Original nine-phase plan (superseded, kept for reference)

Five milestones. Every phase ends with the full reactor green, and nothing is half-migrated at a phase boundary.

> **Ordering correction from the first draft.** I originally put deprecation shims in Phase 8, *after* the
> plugin collapses. That is wrong and would have broken production: the moment a collapsed plugin loads, every
> workflow referencing its old node types fails. The shim is a **prerequisite** for the first collapse, so it
> moves to Phase 6.

### Milestone A — Foundation (invisible, zero behaviour change)

**Phase 1 · SDK — `OperationDefinition`** · size S · risk none

- `workflow-plugin-sdk/…/operation/`: `OperationDefinition`, `RiskLevel`, `ExecutionMode`, `RetryPolicy`,
  `Lifecycle`, builder
- `WorkflowNodePlugin.getOperations()` as a **`default`** returning empty — binary-compatible, exactly as
  `files()` was
- `SchemaBuilder`: `connectionRef`, `dynamicDropdown`, `requiredWhen`
- *Tests:* risk→`destructive()` mapping, builder validation, empty default
- *Acceptance:* all 16 modules green; no plugin changed

**Phase 2 · Registry + API** · size M · risk low

- `PluginRegistry` indexes operations by `(pluginId, operationId)`
- `PluginOperationController`: `GET /api/plugins/{id}/operations`, `…/operations/{opId}`, `…/metadata`
- `PluginJarValidator` validates the manifest `operations` block at install — **including rejecting unknown
  permission names (§3.2)**
- *Tests:* indexing, duplicate operation ids rejected, unknown permission rejected, endpoint contract
- *Acceptance:* endpoints return live data; nothing consumes them yet

### Milestone B — Engine honours operations (the security work)

**Phase 3 · Executor** · size L · risk **medium — security path**

- `PluginNodeExecutor`: resolve operation → validate config against *its* schema → check *its* permissions →
  apply *its* risk, retry, timeout
- New `PluginConfigurationValidator`
- Error codes: `OPERATION_NOT_FOUND`, `OPERATION_DISABLED`, `OPERATION_VERSION_INCOMPATIBLE`,
  `MISSING_REQUIRED_FIELD`, `INVALID_FIELD_VALUE`
- Audit records `operationId` + `operationVersion`
- *Tests, heaviest in the project:* unknown/disabled/removed operation, required-field omission, type mismatch,
  permission denied, per-operation timeout and retry, **hidden-but-set field still validated**
- *Acceptance:* a node with `configuration.operation` executes and validates; nodes without one behave exactly
  as today

**Phase 4 · AI tools per operation** · size M · risk **medium — approval path**

- `AIToolRegistry` builds one tool per *operation*
- `PluginAIToolAdapter` takes `OperationDefinition`; `isDestructive()` reads operation risk
- *Tests:* tool count preserved after a collapse, `find` not destructive while `deleteMany` is, supervised
  agent blocked on the latter and not the former
- *Acceptance:* **agent tool count and risk fidelity identical to today** — this is the phase that proves
  collapsing costs the agent nothing

### Milestone C — Visible end to end

**Phase 5 · Angular** · size M · risk low

- `OperationSelector` (searchable, grouped), `DynamicDropdown` (`dependsOn` cascade), `VariablePicker`,
  `ConnectionSelector`, `OperationBanner`
- `schema-fields.ts`: `requiredWhen` + the four new formats (§3.3)
- `node-properties.ts` wires the selector above the schema form
- *Tests:* selector filtering, cascade reload on parent change, `visibleWhen` + `requiredWhen` evaluation,
  unavailable-operation banner
- *Acceptance:* changing the operation changes the form, with no plugin-specific component

**Phase 6 · Deprecation shim + MongoDB reference collapse** · size M · risk medium

- **Shim first:** a plugin may register old node types as `Lifecycle.DEPRECATED` aliases mapping to
  `(nodeType, operation)`. `PluginNodeExecutor` resolves an alias transparently.
- Then MongoDB 3 → 1: `MONGODB_READ/WRITE/ADMIN` become aliases of `MONGODB` + operation
- *Tests:* **a workflow saved against `MONGODB_READ` still executes unchanged** — the whole point
- *Acceptance:* MongoDB is one node with 11 operations; existing workflows untouched and passing

### Milestone D — Rollout

**Phase 7 · Collapse the rest** · size L but mechanical · risk low

Excel 27→1 · Kubernetes 45→1 · Jira 39→1 · GitHub 36→1 · Docker 14→1 · GCP Compute 10→1.
One plugin per commit, each with its alias set and tests. **~180 node types → 12.**

**Phase 8 · Migration tooling** · size M · risk low

- `PluginNodeMigration`: dry-run report across all workflows; per-workflow rewrite **to drafts only**
- Never touches a published version; publishing stays a human action
- *Acceptance:* dry run writes nothing; migration leaves the published version byte-identical

### Milestone E — Documentation

**Phase 9 · Docs** · size M · risk none — plugin development guide, field-type reference, migration runbook.

### What I would ship first

**Milestones A–C (Phases 1–6).** That is a complete, provable vertical slice: the engine understands
operations, the agent keeps its granularity, the UI renders them, and one real plugin is collapsed with its
existing workflows still running. Phase 7 is then repetition with a proven pattern, and it can be spread out.

---

## 10. The one thing I want to flag before building

**I argued against this design in four plugin READMEs I wrote for you.** The Excel one says:

> *"A single 'Excel Handler' node with an operation dropdown would have been the other design and is worse: one
> risk flag for everything (so the agent could not tell a read from a delete), and fifty fields on one form."*

That objection was correct **given a node-level risk flag** — which is exactly the constraint this design
removes. With risk on the operation:

- the agent still sees one tool per operation, with per-operation risk → **objection 1 resolved**
- `visibleWhen` shows only the selected operation's fields → **objection 2 resolved**

So I am not reversing myself under pressure; the premise changed. The palette also gets dramatically better —
12 nodes instead of 180.

**The one cost that does not go away:** a plugin's node is only as discoverable as its operation list. A user
scanning the palette for "delete pod" will no longer find it there; they find "Kubernetes" and then search
within it. `OperationSelector` search (spec §"Operation Search") is what makes that acceptable, so it is not
optional — it is load-bearing, and it is in Phase 5.

---

## Appendix — worked examples

### MongoDB (reference)

```
MongoDB  ▸ Query   → Find One · Find Many · Count · Distinct · Aggregate    [READ]
         ▸ Write   → Insert One · Insert Many · Update One · Update Many    [MEDIUM]
         ▸ Delete  → Delete One · Delete Many                               [HIGH]
```
Three node types collapse to one; the READ/WRITE/ADMIN split disappears because risk now lives per operation.

### REST API

```
REST API ▸ GET · POST · PUT · PATCH · DELETE
```
Already one node with a `method` field — becomes `operation`, gaining per-method risk.

### GCP (three plugins → one node each, or one merged)

```
GCP ▸ Compute    → Create VM [HIGH] · Start · Stop · Restart · Delete VM [CRITICAL] · List
    ▸ Kubernetes → Create Cluster [HIGH] · Get · Delete Cluster [CRITICAL] · Scale Node Pool
    ▸ Storage    → Create Bucket · Upload · Download · Delete Object
```
`group` in `OperationDefinition` drives that hierarchy in the dropdown.

### GitHub

```
GitHub ▸ Repository → Get · Create · Create Branch · Delete Branch [HIGH]
       ▸ Files      → Get · Create · Update · Delete [HIGH] · Commit
       ▸ Pull Req.  → Create · Merge [HIGH] · Comment
       ▸ Actions    → Run Workflow · Get Status
```

### Excel

```
Excel ▸ Read      → Read Sheet · Read Workbook · Get Cell · Metadata
      ▸ Shape     → Search · Filter · Sort · Validate · Transform · Compare · Report
      ▸ Write     → Create · Write · Append · Update · Set Cell · Delete Row [HIGH]
      ▸ Sheets    → Create · Rename · Copy · Delete Sheet [HIGH]
      ▸ Convert   → to/from JSON · to/from CSV
```
27 node types → 1 node, 27 operations, same AI tool count, same risk fidelity.
