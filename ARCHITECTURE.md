# Workflow Engine — Architecture

Java 17 · Spring Boot 4.0.x · Spring Data MongoDB · Maven multi-module · REST

---

## 1. Guiding principle

The core engine knows exactly six abstractions and nothing else:

```
WorkflowPlugin · WorkflowNodeExecutor · PluginManager
PluginRegistry · NodeExecutionContext · NodeExecutionResult
```

It has **zero** knowledge of SendGrid, Slack, REST, SQL or LLMs. Every integration is a
JAR built against `workflow-plugin-sdk`, uploaded at runtime, stored in MongoDB GridFS,
loaded into an isolated `ClassLoader`, and registered as a node type. No core rebuild,
no restart.

There is no `switch (nodeType)` and no `if (type.equals("SENDGRID"))` anywhere in the
engine. Node dispatch is a registry lookup; plugin dispatch is a registry lookup plus a
version resolve.

---

## 2. Module structure

```
workflow-engine/                        (parent pom, dependencyManagement)
├── workflow-plugin-sdk/                the stable, public plugin API
│   └── no Spring, no MongoDB, no Jackson — pure Java 17
├── workflow-engine-core/               the Spring Boot application
└── plugins/
    ├── sendgrid-plugin/                sample: SENDGRID_EMAIL
    ├── restapi-plugin/                 sample: REST_API_CALL
    └── slack-plugin/                   sample: proves "add a plugin later"
```

The SDK is deliberately dependency-free. That is what keeps the contract stable while the
engine's internals change, and it is what makes parent-first delegation of the API
packages safe (see §7).

Plugin modules declare the SDK as `provided` so the API classes are never duplicated
inside a plugin JAR.

---

## 3. Core package layout (`com.orchpilot.workflow`)

| Package | Responsibility |
|---|---|
| `controller` | REST edge. DTOs in, DTOs out. No business logic. |
| `service` | Use-case orchestration: workflow CRUD, publish, execute, plugin lifecycle, secrets, audit. |
| `repository` | Spring Data Mongo repositories + `MongoTemplate` for atomic claims. |
| `model` | `@Document` persistence models (immutable-ish, no Lombok). |
| `dto` | Request/response records. Never leak `model` types over HTTP. |
| `execution` | `WorkflowExecutionEngine`, `WorkflowExecutionContext`, retry, recovery. |
| `node` | `WorkflowNodeExecutor` contract, registry, four built-in executors. |
| `plugin` | `PluginManager`, `PluginRegistry`, `PluginClassLoader`, GridFS storage, validation, discovery, SDK context implementations. |
| `expression` | Safe SpEL evaluator (`SimpleEvaluationContext`, no `T()`, no beans, no constructors). |
| `variable` | `VariableStore`, `VariableResolver`, `VariableMapper`. |
| `scheduler` | Cluster-safe cron poller driven by workflow triggers. |
| `event` | Internal + external event bus, event-triggered workflow starts. |
| `exception` | Typed exceptions + `@RestControllerAdvice`. |
| `config` | Properties, Mongo/GridFS, executors, indexes, OpenAPI, admin API key filter. |
| `utility` | Path walking, hashing, JAR helpers, JSON helpers. |

---

## 4. Class responsibilities (the ones that matter)

### Execution

| Class | Responsibility |
|---|---|
| `WorkflowExecutionEngine` (iface) | `start`, `resume`, `cancel`, `pause`. Single entry point for **all** execution modes. |
| `DefaultWorkflowExecutionEngine` | Iterative graph walk. Loads the pinned workflow version, resolves an executor per node, applies retry/error policy, persists after every node, publishes events. Never recurses (no stack overflow on long workflows). |
| `WorkflowExecutionContext` | Live, thread-confined view of one execution: variable store, node outputs, current node, log sink, cancellation flag. |
| `NodeRetryTemplate` | Attempt loop honouring `RetryPolicy` (max attempts, fixed/exponential backoff, retryable predicate). |
| `ExecutionRecoveryService` | On startup, re-queues executions left `RUNNING` by a crashed instance (stale heartbeat + atomic claim). |
| `ExecutionStateStore` | The only writer of `workflow_executions`. Optimistic locking via `@Version`. |

### Nodes

`WorkflowNodeExecutor` is the single contract:

```java
String getNodeType();
NodeExecutionResult execute(WorkflowNode node, WorkflowExecutionContext ctx);
```

* `StartNodeExecutor` — seeds input + declared workflow variables, validates single start.
* `FormNodeExecutor` — applies `inputMapping`, and either returns `WAITING` (parks the
  execution with a pending-form descriptor) or, if the submission is already present,
  applies `outputMapping` and returns `SUCCESS`. Sync and async in one implementation.
* `DecisionNodeExecutor` — evaluates conditions in order against the safe evaluator,
  returns the winning branch in `NodeExecutionResult.selectedBranch()`; supports a
  `defaultBranch`.
* `EndNodeExecutor` — collects declared outputs, sets terminal status, publishes
  `WorkflowCompletedEvent`.
* `PluginNodeExecutor` — the *only* bridge to plugins. Resolves `pluginId@version`,
  acquires an execution lease, builds an SDK `NodeExecutionContext`, applies the
  idempotency guard, records `plugin_executions`, releases the lease.

`WorkflowNodeRegistry` resolves `WorkflowNode → WorkflowNodeExecutor` through an ordered
list of `NodeExecutorResolver`s (built-in map first, plugin resolver second). Registration
and lookup are `ConcurrentHashMap`-backed and safe under concurrent load/unload.

### Plugins

| Class | Responsibility |
|---|---|
| `PluginManager` | Lifecycle: `install`, `load`, `activate`, `deactivate`, `unload`, `reload`. Serialised per plugin id with a striped lock. |
| `PluginRegistry` | In-memory `pluginId → version → PluginHandle`. Also the node-type index used by `/api/nodes`. |
| `PluginHandle` | One loaded version: descriptor, instance, classloader, state, **active-execution counter**, draining flag, workspace dir. |
| `PluginClassLoader` | Child-first `URLClassLoader` with a parent-first allowlist (JDK + SDK API + SLF4J). Closeable. |
| `GridFsPluginStorage` | JAR bytes in GridFS, keyed by `pluginId:version`, with SHA-256. |
| `PluginJarValidator` | Zip integrity, size cap, manifest, declared main class present & assignable, id/version match, duplicate version, optional checksum/signature. |
| `PluginDiscoveryService` | Finds the plugin class: `Workflow-Plugin-Class` manifest attribute → `ServiceLoader` → explicit `mainClass` from upload metadata. |
| `DefaultPluginContext` | The narrow surface plugins get. Never the `ApplicationContext`. |

---

## 5. MongoDB collections

| Collection | Purpose | Key indexes |
|---|---|---|
| `workflows` | Current draft/published head of each workflow | `name`, `status` |
| `workflow_versions` | Immutable published snapshots (executions pin one) | unique `(workflowId, version)` |
| `workflow_executions` | Execution state incl. variables, node history | `workflowId`, `status`, `(status, heartbeatAt)`, `pendingForm.formId` |
| `workflow_execution_logs` | Append-only structured log per execution | `(executionId, sequence)` |
| `workflow_plugins` | Plugin metadata head | `_id = pluginId`, `status` |
| `workflow_plugin_versions` | One doc per version: main class, node definitions, permissions, GridFS id, checksum | unique `(pluginId, version)` |
| `plugin_executions` | Per-plugin-call audit: request/response (redacted), duration, status | `executionId`, unique `idempotencyKey` (sparse) |
| `workflow_schedules` | Cron triggers, `nextRunAt` claim field | `nextRunAt`, unique `(workflowId, triggerId)` |
| `workflow_secrets` | AES-GCM encrypted secret values | `_id = name` |
| `workflow_audit_log` | Who did what to plugins/workflows | `(entityType, entityId)`, `at` |
| `form_definitions` | Editable form heads, as the designer saves them | `name`, `status` |
| `form_definition_versions` | Immutable published form snapshots (tasks pin one) | unique `(formDefinitionId, version)` |
| `human_tasks` | Work waiting for a person | unique `(workflowExecutionId, nodeId, attempt)`, `(assigneeUserId, status)`, `(candidateGroupIds, status)` |
| `human_task_history` | Append-only trail of what happened to each task | `taskId` |
| `groups`, `group_members` | Group-based workflow authorization | `name`, `(userId, groupId)` |
| `users`, `refresh_tokens`, `login_attempts`, `security_audit_logs` | Authentication | see `SECURITY.md` |

GridFS bucket `plugin_jars` holds the JAR binaries. Metadata documents hold only the
GridFS id + checksum, never the bytes.

---

## 5a. Human tasks

A form node parks the execution and raises a `HumanTask`. The two are separate documents on
purpose: an inbox filters by assignee, by candidate group and by due date, and none of those
are indexable inside a nested object on a document keyed by execution. A task also outlives
the wait — a completed task is the record of who approved what — while the execution's
pending signal is cleared the moment it resumes.

```
FormNodeExecutor.execute()
        │
        ├─ no submission ─▶ FormNodeBinding.resolve()      pinned published form version
        │                   TaskAssignmentResolver.resolve() names → user and group ids
        │                   HumanTaskService.createOrReuse() idempotent on (exec, node, attempt)
        │                   return WAITING, payload carries taskId
        │
        └─ submission ────▶ FormVariableMapper.mapFormDataToVariablePaths()
                            context.variables().set(path, value)   one path at a time
                            return SUCCESS
```

Completion runs in the other direction:

```
POST /api/tasks/{id}/complete
        │
        ├─ TaskAuthorizationService.canComplete()   assignee only, never an administrator
        ├─ FormNodeBinding.validateOrThrow()        against the pinned version → 422
        ├─ task COMPLETED + submittedData saved     ← arbitration point, before the resume
        └─ ExecutionService.submitSignal()          engine re-enters the node, maps, continues
```

Two things about that order. The task is saved **before** the execution is resumed, because
optimistic locking on the task is what stops two people submitting the same approval and
running the next node twice, and because a stored approval with a stalled workflow is
recoverable (`POST /api/tasks/{id}/retry-resume`) while a resumed workflow with no record of
who approved it is not. And the field-to-variable mapping happens in the **node executor**,
not in the task controller, so that `POST /api/executions/{id}/form` — which exists and is
what an integration uses — goes through the same server-side mapping rather than writing raw
payload keys into the workflow.

Two classes of authorization apply, and they answer different questions. `WorkflowPermission`
answers "may this account touch that workflow"; `TaskAuthorizationService` answers "is this
task theirs". Holding `WORKFLOW_EXECUTE` lets somebody start a hundred runs and entitles them
to see none of the approval tasks those runs raise for other people.

---

## 6. Plugin lifecycle

```
POST /api/plugins/upload  (multipart: file + metadata JSON)
        │
        ├─ 1. PluginJarValidator.validateArchive()      zip ok? size ok? manifest ok?
        ├─ 2. PluginDiscoveryService.probe()            in a throw-away classloader
        ├─ 3. PluginJarValidator.validateDescriptor()   id/version/type/nodes/duplicates
        ├─ 4. GridFsPluginStorage.store()               bytes + sha256
        ├─ 5. upsert workflow_plugins / insert workflow_plugin_versions  (status INSTALLED)
        ├─ 6. PluginManager.load()                      real PluginClassLoader
        │        instantiate → initialize(PluginContext) → collect NodeDefinitions
        ├─ 7. PluginRegistry.register(handle)           node types become resolvable
        └─ 8. status ACTIVE + audit + PluginLifecycleEvent
                    ↓
        GET /api/nodes now includes the plugin's node types
```

Unload is the reverse, and is the part most implementations get wrong:

```
deactivate  → handle.startDraining()      no new leases granted
            → await active leases == 0    up to plugin.unload-grace (default 30s)
            → cancel or fail stragglers per policy
unload      → registry.unregister(id, version)
            → instance.destroy()          plugin releases its own resources
            → classLoader.close()
            → drop hard references, delete workspace dir
            → System.gc() hint only in tests; never relied on in prod
```

Leak avoidance: no plugin class is ever cached in a core static map; the handle is the
only strong reference; `ThreadLocal`s are not exposed to plugins; the HTTP client handed
to plugins is core-owned, not plugin-created; and the workspace directory is per
`pluginId:version` so reload never reuses a locked file (important on Windows).

**Reload** = load new version alongside → flip default → drain old → unload old. Running
executions keep the version they started on.

---

## 7. ClassLoader isolation — and its limits

`PluginClassLoader` is child-first with a parent-first allowlist:

```
parent-first: java.* javax.* jakarta.* jdk.* sun.* com.sun.* org.w3c.* org.xml.*
              org.slf4j.*  com.orchpilot.workflow.sdk.*      ← the shared contract
child-first:  everything else (the plugin and its bundled dependencies)
```

Bundled `lib/*.jar` entries inside the plugin JAR are extracted to the workspace and added
to the classpath, so a plugin can ship its own Jackson/HTTP library version without
colliding with the engine's.

**State plainly: this is dependency isolation, not a security sandbox.** A plugin runs in
the engine JVM with the engine's privileges. It can call `System.exit`, spawn threads,
read files the process can read, open sockets, allocate until OOM, and use reflection to
reach fields it was not given. The Java `SecurityManager` that used to constrain this is
deprecated for removal (JEP 411) and is not a viable answer in Java 17+.

What the design *does* enforce, and what it *cannot*:

| Control | Enforced by | Real strength |
|---|---|---|
| Dependency/version isolation | child-first classloader | strong |
| API surface restriction | `PluginContext` only | strong for accidental misuse, bypassable by reflection |
| Secret scoping | `ScopedSecretProvider` + declared scopes | strong for cooperative code |
| Network allowlist | `RestrictedHttpClient` | only if the plugin uses the provided client |
| CPU/memory/thread limits | none in-JVM | **absent** |
| Filesystem confinement | none in-JVM | **absent** |
| `System.exit` prevention | none (no SecurityManager) | **absent** |

**Therefore:** trusted, reviewed, signed plugins → in-process is fine and fast.
Third-party or tenant-supplied plugins → run them out of process. The `PluginNodeExecutor`
+ `PluginContext` seam is exactly where a remote transport drops in: an
`OutOfProcessPluginHandle` that speaks to a sidecar JVM or container over gRPC/HTTP gives
you real cgroup CPU/memory limits, seccomp, a read-only rootfs and network policy, and
kill-ability. §Security in the README expands on this.

Checksum + optional JAR signature verification, an upload API behind an admin key, an
activate/deactivate kill switch, per-plugin permissions, and full audit + execution
history are the compensating controls for the in-process mode.

---

## 8. Execution flow

```
REST / cron / event / manual
        │  (all four call the same engine — no duplicated execution logic)
        ▼
ExecutionService.start(workflowId, input, mode)
        │  resolve PUBLISHED workflow_version  → pin (workflowId, version)
        │  create workflow_executions doc, status PENDING
        ▼
WorkflowExecutionEngine.run(execution)
        │
        ├─ WorkflowExecutionContext: system + input + declared workflow variables
        ├─ locate START node
        │
        └─ loop:
             resolve executor  ──► WorkflowNodeRegistry
             resolve config    ──► VariableResolver (${...} over config tree)
             execute with      ──► NodeRetryTemplate (RetryPolicy)
             on result:
               SUCCESS  → apply outputMapping, pick next edge
                          (DECISION: edge whose sourcePort == selectedBranch)
               WAITING  → persist status WAITING + pendingForm, return
               SKIPPED  → follow default edge
               FAILED   → ErrorPolicy: RETRY | SKIP | CONTINUE | COMPENSATE | FAIL
             persist node record + variables + heartbeat
             until END node or no outgoing edge
        ▼
status COMPLETED / FAILED / CANCELLED, outputs stored, WorkflowCompletedEvent published
```

Resume paths: `POST /api/executions/{id}/form` (form submitted),
`POST /api/workflows/{id}/resume`, and automatic recovery at startup. All three converge
on `engine.resume(executionId, resumeInput)`, which rehydrates the context from Mongo and
re-enters the same loop.

---

## 9. Variables and expressions

Four scopes in one addressable namespace:

```
${input.employeeId}          immutable execution input
${workflow.orderId}          mutable workflow scope
${node.form-1.approved}      per-node outputs, addressed by node id
${system.executionId}        engine-provided (executionId, workflowId, now, attempt)
```

* `VariableResolver` — `${...}` interpolation over strings **and recursively over the
  whole configuration tree** (maps, lists). Single-placeholder strings keep their native
  type (`${amount}` stays a number); mixed strings become text.
* `VariableMapper` — applies `inputMapping` / `outputMapping` declaratively.
* `ExpressionEvaluator` — SpEL compiled + cached, evaluated with
  `SimpleEvaluationContext.forPropertyAccessors(MapAccessor)`: property reads only.
  No type references, no bean references, no constructors, no assignment. `amount > 10000`
  and `status == 'APPROVED'` work; `T(java.lang.Runtime).getRuntime()` does not parse.

---

## 10. Idempotency

Every plugin node invocation gets a deterministic key:

```
sha256(executionId : nodeId : pluginId : version : configFingerprint)
```

`plugin_executions` has a unique sparse index on it. Before invoking, `PluginNodeExecutor`
looks for a prior **successful** record with the same key; if found, it replays the stored
outputs instead of calling SendGrid twice. Retries and post-crash resume therefore do not
duplicate side effects. Plugins that need finer granularity get `IdempotencyStore` on the
context.

---

## 11. Execution modes

| Mode | Entry | Notes |
|---|---|---|
| Synchronous | `POST /api/workflows/{id}/execute` | runs on the request thread, bounded by a configurable timeout |
| Asynchronous | `…/execute?async=true` | submits to `workflowExecutor`, returns `{executionId, RUNNING}` |
| Scheduled | trigger `{type: SCHEDULE, cron: "0 0 9 * * *"}` | `WorkflowScheduler` polls, claims via atomic `findAndModify` on `nextRunAt` → safe with N instances |
| Event-driven | trigger `{type: EVENT, eventName: ORDER_CREATED}` + `POST /api/events` | fan-out to every subscribed published workflow |
| Manual | `POST /api/workflows/{id}/execute` with `mode=MANUAL` | same path, different audit attribution |

---

## 12. What is safe to load dynamically

| Safe to hot-load | Requires restart / separate JVM |
|---|---|
| New node types, new plugin versions | changing the SDK's binary contract |
| Plugin config schemas and UI metadata | engine-wide Spring bean graph changes |
| Plugin-bundled third-party libraries | untrusted / tenant code (→ container) |
| Activation, deactivation, rollback | native agents, JVM flags, `-Xmx` |
| Cron and event trigger registration | Mongo connection topology |

---

## 13. Build order

Phase 1 skeleton + workflow CRUD · Phase 2 built-in nodes · Phase 3 engine, context,
variables, registry · Phase 4 SDK + plugin manager/registry/classloader · Phase 5 GridFS +
runtime upload + discovery + activation · Phase 6 SendGrid & REST plugins · Phase 7 async,
scheduler, events, resume, retry · Phase 8 security, tests, Docker, Swagger, docs.
