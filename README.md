# Workflow Engine

An extensible workflow execution platform. Java 17, Spring Boot 4, Spring Data MongoDB, Maven.

Workflows are graphs of nodes. The engine implements four node types itself; **everything else arrives at
runtime as a plugin JAR** — validated, stored in MongoDB GridFS, loaded into an isolated class loader, and
registered as a usable node type without a rebuild or a restart.

```
                      REST API
                          │
                 ┌────────┴────────┐
        WorkflowService      ExecutionService
                          │
              WorkflowExecutionEngine
                          │
              WorkflowNodeRegistry
                 ┌────────┴────────┐
          Built-in nodes      Plugin nodes
          START  FORM          SENDGRID_EMAIL
          DECISION  END        REST_API_CALL
                               SLACK_MESSAGE
                               …anything uploaded later
                          │
              Variable / Context Manager
                          │
                Execution Persistence
                          │
                       MongoDB
```

The core engine contains no reference to SendGrid, Slack, REST, SQL or any LLM. There is no
`switch (nodeType)` and no `if (type.equals("SENDGRID"))` anywhere in it.

* [ARCHITECTURE.md](ARCHITECTURE.md) — modules, classes, collections, plugin lifecycle, execution flow
* [PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md) — how to write and ship a plugin
* [workflow-engine-ui/](workflow-engine-ui/README.md) — Angular console: designer, task inbox, plugin manager
* [examples/](examples/) — complete workflow definitions

---

## Quick start

### With Docker

```bash
export WORKFLOW_ADMIN_API_KEY="$(openssl rand -hex 24)"
export WORKFLOW_SECRETS_KEY="$(openssl rand -base64 32)"
docker compose up --build
```

Brings up MongoDB, the engine and the Angular console:

* console: <http://localhost:4200>
* API docs: <http://localhost:8080/swagger-ui.html>

### Locally

Needs JDK 17+, Maven 3.9+, and a MongoDB on `localhost:27017`.

```bash
mvn clean install
```

```bash
export MONGODB_URI="mongodb://localhost:27017/workflow_engine"
export WORKFLOW_ADMIN_API_KEY="local-dev-key"
export WORKFLOW_SECRETS_KEY="$(openssl rand -base64 32)"
java -jar workflow-engine-core/target/workflow-engine.jar
```

`mvn install` also builds the three sample plugin JARs under `plugins/*/target/`.

For the console, in a second terminal:

```bash
cd workflow-engine-ui && npm install && npm start
```

It serves on <http://localhost:4200> and proxies `/api` to the engine on 8080.

---

## The demonstration

This is the flow the platform exists to support. Every step below works against a running engine with no
code change and no restart.

### 1. Built-in nodes are available immediately

```bash
curl -s localhost:8080/api/nodes | jq -r '.[] | "\(.source)\t\(.nodeType)"'
```

```
BUILT_IN    DECISION
BUILT_IN    END
BUILT_IN    FORM
BUILT_IN    START
```

### 2. Store the credential the plugin will need

The API key never goes into a workflow definition. It is stored encrypted and referenced by name.

```bash
curl -X PUT localhost:8080/api/secrets/sendgrid.apiKey \
  -H "X-Admin-Api-Key: $WORKFLOW_ADMIN_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"value":"SG.your-real-key","description":"SendGrid production key","allowedPlugins":["sendgrid"]}'
```

### 3. Upload the plugin JAR to the running engine

```bash
curl -X POST localhost:8080/api/plugins/upload \
  -H "X-Admin-Api-Key: $WORKFLOW_ADMIN_API_KEY" \
  -F "file=@plugins/sendgrid-plugin/target/sendgrid-plugin-1.0.0.jar" \
  -F "allowedHosts=api.sendgrid.com" \
  -F "secretScopes=sendgrid." \
  -F "activate=true"
```

The engine validates the archive, probes it for its identity in a throwaway class loader, stores the bytes
in GridFS with a SHA-256, writes its metadata, loads it in a dedicated class loader, initialises it and
registers its node types. Response:

```json
{
  "pluginId": "sendgrid", "version": "1.0.0", "status": "ACTIVE", "loaded": true,
  "nodeTypes": ["SENDGRID_EMAIL"],
  "allowedHosts": ["api.sendgrid.com"], "secretScopes": ["sendgrid."],
  "sha256": "9f2c…", "mainClass": "com.orchpilot.workflow.plugins.sendgrid.SendGridPlugin"
}
```

### 4. The new node type is in the catalogue

```bash
curl -s localhost:8080/api/nodes | jq -r '.[] | "\(.source)\t\(.nodeType)"'
```

```
BUILT_IN    DECISION
PLUGIN      SENDGRID_EMAIL     ← appeared without a restart
BUILT_IN    END
BUILT_IN    FORM
BUILT_IN    START
```

`GET /api/nodes/SENDGRID_EMAIL` returns the configuration schema the plugin published, so a designer can
render its property panel. **A front end never needs a release when a plugin is added.**

### 5. Create and publish a workflow that uses it

```bash
WF=$(curl -s -X POST localhost:8080/api/workflows \
  -H 'Content-Type: application/json' \
  -d @examples/employee-approval-workflow.json | jq -r .id)

curl -X POST localhost:8080/api/workflows/$WF/publish
```

Publishing validates the graph and snapshots it into an immutable version. Executions pin that version,
so editing the workflow afterwards cannot change a run already in flight.

### 6. Execute it

```
Start ──► Form ──► Decision ──┬── approved ──► SendGrid ──► End
                              ├── escalate ──► Escalation Form ──► Decision
                              └── rejected ──► End
```

```bash
EX=$(curl -s -X POST localhost:8080/api/workflows/$WF/execute \
  -H 'Content-Type: application/json' \
  -d '{"input":{"employeeId":"E-42","employeeName":"Priya","amount":15000,
                "managerEmail":"manager@example.com"}}' | jq -r .executionId)
```

The form node parks the execution and holds no thread:

```json
{ "status": "WAITING",
  "pendingSignal": { "nodeId": "form-1", "formId": "employeeApproval",
                     "payload": { "prefill": { "employeeId": "E-42", "amount": 15000 } } } }
```

Submit it, hours or days later, from any instance in the cluster:

```bash
curl -X POST localhost:8080/api/executions/$EX/form \
  -H 'Content-Type: application/json' \
  -d '{"data":{"approved":true,"comments":"looks fine"}}'
```

```json
{ "status": "COMPLETED", "currentNodeId": "end-approved",
  "output": { "approved": true, "comments": "looks fine", "notificationId": "…" } }
```

### 7. Add a second, entirely new integration — still no restart

`slack-plugin` is a separate module that nothing in the engine references.

```bash
curl -X PUT localhost:8080/api/secrets/slack.botToken \
  -H "X-Admin-Api-Key: $WORKFLOW_ADMIN_API_KEY" \
  -H 'Content-Type: application/json' -d '{"value":"xoxb-…"}'

curl -X POST localhost:8080/api/plugins/upload \
  -H "X-Admin-Api-Key: $WORKFLOW_ADMIN_API_KEY" \
  -F "file=@plugins/slack-plugin/target/slack-plugin-1.0.0.jar" \
  -F "allowedHosts=slack.com,*.slack.com" \
  -F "secretScopes=slack." -F "activate=true"

curl -s localhost:8080/api/nodes | jq -r '.[].nodeType' | grep SLACK
```

`SLACK_MESSAGE` is now usable. `examples/order-fulfilment-workflow.json` uses it together with
`REST_API_CALL`, both from plugins, in an event-triggered and cron-triggered workflow.

### 8. Turn a plugin off without touching any workflow

```bash
curl -X POST "localhost:8080/api/plugins/slack/deactivate?version=1.0.0" \
  -H "X-Admin-Api-Key: $WORKFLOW_ADMIN_API_KEY"
```

New executions stop being admitted, in-flight ones are drained, the class loader is closed, and
`SLACK_MESSAGE` disappears from the catalogue. Workflows that use it now fail with
`PLUGIN_NOT_AVAILABLE` rather than executing stale code. Reactivate to restore it.

---

## Node types

### Built-in

| Type | Behaviour |
|---|---|
| `START` | Single entry point. Seeds workflow variables from declared defaults and from the execution input; a default never overwrites a caller-supplied value. |
| `FORM` | Human-in-the-loop. Raises a task for a person, returns `WAITING` and parks the execution; or completes immediately if the submission is already present. Same code path for synchronous and asynchronous use. See [Human tasks](#human-tasks). |
| `DECISION` | Evaluates conditions in declaration order; first match wins. Returns the branch name, and the engine follows the edge whose `sourcePort` matches. Supports a `defaultBranch`. |
| `END` | Completes the workflow, assembles its result, publishes the completion event. |

### Plugin (samples in this repository)

| Node type | Plugin | Notes |
|---|---|---|
| `SENDGRID_EMAIL` | `sendgrid-plugin` | Declared non-idempotent, so the engine replays a recorded send instead of sending twice on a retry; also passes the idempotency key to SendGrid. |
| `REST_API_CALL` | `restapi-plugin` | GET/POST/PUT/PATCH/DELETE, headers, query parameters, JSON or raw body, bearer token from a secret. Publishes `response.status`, `response.body`, `response.headers` and a parsed `json` object. Opt-in deduplication. |
| `SLACK_MESSAGE` | `slack-plugin` | Handles Slack's HTTP 200 with `"ok": false` envelope, which a naive integration reports as success. |

---

## Human tasks

A `FORM` node is a step where a person decides something. Configure it with the form to show
and who should see it:

```json
{
  "id": "approve",
  "type": "FORM",
  "formId": "<form definition id>",
  "configuration": {
    "formVersion": 2,
    "taskName": "Approve salary change for ${input.employee}",
    "taskDescription": "Check the numbers against the budget.",
    "candidateGroups": ["Finance approvers"],
    "priority": "HIGH",
    "dueInSeconds": 86400,
    "expiresInSeconds": 604800
  }
}
```

`assignee`, `candidateUsers` and `candidateGroups` accept usernames, user ids or group names,
with `${...}` placeholders resolved against the execution's variables first. A task naming one
person starts `ASSIGNED`; one offered to a group starts `OPEN` and is claimed. Whatever cannot
be resolved is reported on the task and in the execution log rather than guessed at — in
particular, an unresolvable assignee leaves the task unassigned rather than falling back to
whoever started the run, which on an approval workflow would hand the requester their own
approval.

`dueInSeconds` is advisory: the task is flagged overdue and a reminder is sent, and it stays
completable. `expiresInSeconds` is enforced: the task is marked `EXPIRED` and the execution is
cancelled. Both are checked by a scheduler that every instance runs, which is safe because
expiring an already-expired task does nothing.

What the form collects is written to the workflow variables **the form declares**, not the
keys the browser sent:

```
field "salary" → variable employee.salary (DOUBLE)   submitted "141000" → 141000.0
field "urgent" → variable approval.urgent (BOOLEAN)  submitted "true"   → true
key   "isAdmin"                                       → matches no field, reaches nothing
```

The server loads the pinned form version from MongoDB, validates against it, and performs the
mapping itself. That is why an unknown key cannot invent a variable and why a client cannot
nominate where its input lands. Both entrances — the task API and
`POST /api/executions/{id}/form` — go through the same code, so an integration posting
directly is validated exactly as the console is.

Only the assignee may submit. An administrator with `TASK_ADMIN` can cancel and reassign but
deliberately **cannot** complete somebody else's task: writing another person's name against
an approval they never gave makes the record worthless, and that record is the only reason to
build human tasks rather than a REST call. Reassigning it to yourself first works and leaves a
`REASSIGNED` line in the history saying so.

---

## Variables and expressions

Four scopes in one addressable namespace:

```
${input.employeeId}          immutable execution input
${workflow.orderId}          mutable workflow scope, the default write target
${node.form-1.approved}      per-node outputs, addressed by node id
${system.executionId}        engine-provided
```

* A whole-string placeholder keeps its type: `"${amount}"` yields the number `15000`, not `"15000"`.
* A missing variable is left as a literal `${foo}` rather than becoming an empty string, so the mistake
  is visible in the execution record instead of silently sending email to nobody.
* `$${literal}` escapes.
* Subscripts and quoted keys work: `${response.items[0].sku}`, `${workflow.'order.id'}`.

Decision expressions are SpEL evaluated in a `SimpleEvaluationContext` with property accessors only. Type
references, constructors, bean references and assignment do not work, and are rejected at publish time
with a readable message rather than at 3 a.m. with a stack trace:

```
amount > 10000                        ✓
status == 'APPROVED'                  ✓
country == 'INDIA' and tier == 'GOLD' ✓
T(java.lang.Runtime).getRuntime()     ✗ rejected at publish time
new java.io.File('/etc/passwd')       ✗ rejected at publish time
```

---

## Execution modes

All five run through the same `WorkflowExecutionEngine`. The mode determines who creates the execution and
on which thread it runs, and nothing else.

| Mode | How |
|---|---|
| Synchronous | `POST /api/workflows/{id}/execute` |
| Asynchronous | `POST /api/workflows/{id}/execute?async=true` → `202` with `{executionId, status: RUNNING}` |
| Scheduled | trigger `{"type":"SCHEDULE","cron":"0 0 9 * * *","timezone":"Asia/Kolkata"}` |
| Event-driven | trigger `{"type":"EVENT","eventName":"ORDER_CREATED"}` + `POST /api/events` |
| Manual | `POST /api/workflows/{id}/execute` with `{"mode":"MANUAL"}` |

**Cron is cluster-safe without a leader election.** Every replica polls, and claiming a due schedule is a
single conditional update on `nextRunAt`; exactly one replica wins, so N replicas fire a cron once. Fire
times missed while the cluster was down are skipped rather than replayed in a burst.

---

## Reliability

**Resume after restart.** State is written at every node boundary. An execution left `RUNNING` by a
crashed instance is detected by a stale heartbeat, claimed with a conditional write that names the previous
owner, and resumed at the last completed node. A separate heartbeat task means a node that takes four
minutes is not mistaken for an abandoned one.

**Idempotency.** Every plugin node invocation gets a deterministic key from
`sha256(executionId : nodeId : pluginId : version : configFingerprint)`, with a unique index on it in
`plugin_executions`. For nodes declared non-idempotent, a retry or a post-crash resume finds the prior
successful record and replays its outputs instead of sending a second email. The configuration
fingerprint sorts map keys, so a round trip through MongoDB cannot change the key.

**Retry and error policy** are separate questions, configured separately:

```json
"retry":  { "enabled": true, "maxAttempts": 3, "backoffMillis": 5000, "backoffMultiplier": 2.0 },
"errorPolicy": "COMPENSATE",
"compensationNodeId": "release-reservation"
```

Only failures the node marked retryable are retried; a 400 fails once. Error policies: `FAIL_WORKFLOW`
(default), `SKIP`, `CONTINUE` (publishes the error as node output so a later decision can branch on it),
`COMPENSATE` (runs the compensation node, then fails).

---

## API

### Workflows
```
POST   /api/workflows                    create (DRAFT)
GET    /api/workflows                    list, filter by status or name
GET    /api/workflows/{id}
PUT    /api/workflows/{id}               replace; a published workflow returns to DRAFT
DELETE /api/workflows/{id}
POST   /api/workflows/{id}/publish       validate + snapshot an immutable version
POST   /api/workflows/{id}/validate      validate without publishing
POST   /api/workflows/{id}/archive
POST   /api/workflows/{id}/execute       ?async=true, ?version=N
GET    /api/workflows/{id}/executions
POST   /api/workflows/{id}/pause|resume|cancel     bulk, over every in-flight execution
```

### Executions
```
GET    /api/executions                   list, filter by workflow or status
GET    /api/executions/{id}
GET    /api/executions/{id}/logs
GET    /api/executions/{id}/pending      what a WAITING execution is waiting for
POST   /api/executions/{id}/form         submit and resume
POST   /api/executions/{id}/resume|pause|cancel
```

### Forms
```
GET    /api/forms/field-types            the field catalogue the designer renders from
GET    /api/forms                        list, filter by status or name
POST   /api/forms                        create (DRAFT)
GET    /api/forms/{id}
PUT    /api/forms/{id}                   replace; a published form returns to DRAFT
POST   /api/forms/{id}/validate          publishable? every problem, not the first
POST   /api/forms/{id}/publish           snapshot an immutable version tasks pin
GET    /api/forms/{id}/versions
GET    /api/forms/{id}/versions/{n}
POST   /api/forms/{id}/clone
DELETE /api/forms/{id}
```

### Tasks
```
GET    /api/tasks?bucket=mine|available|all      bucket=all needs TASK_VIEW_ALL
GET    /api/tasks/counts                         the inbox tab counts
GET    /api/tasks/{id}                           form, prefill, capabilities, history
GET    /api/tasks/{id}/history
POST   /api/tasks/{id}/claim                     409 if somebody got there first
POST   /api/tasks/{id}/release                   discards the draft with the assignment
POST   /api/tasks/{id}/draft                     partial input; not validated, not mapped
POST   /api/tasks/{id}/complete                  422 lists every field that needs attention
POST   /api/tasks/{id}/reassign                  admin, or the current holder delegating
POST   /api/tasks/{id}/cancel                    withdraws the task and cancels the run
POST   /api/tasks/{id}/retry-resume              admin: re-send a recorded submission
GET    /api/users/available                      the assignee picker: names and ids only
```

No endpoint here takes a user id. The task is named in the path, the person is the bearer
token, and the server compares the two: changing the id in the URL to somebody else's task
answers 404, and `{"userId": "someone-else"}` in a body is a field nothing reads.

### Plugins — administrative key required
```
POST   /api/plugins/upload               multipart: file + metadata or form fields
GET    /api/plugins
GET    /api/plugins/{id}
GET    /api/plugins/{id}/versions
GET    /api/plugins/{id}/executions      what the plugin actually sent, redacted
POST   /api/plugins/{id}/activate?version=
POST   /api/plugins/{id}/deactivate?version=
POST   /api/plugins/{id}/reload?version=
POST   /api/plugins/{id}/default-version?version=
DELETE /api/plugins/{id}?version=
```

### Nodes, events, secrets
```
GET    /api/nodes                        built-in + every loaded plugin node type
GET    /api/nodes/categories
GET    /api/nodes/{nodeType}             configuration schema
POST   /api/events                       emit a business event
GET    /api/secrets                      names and metadata; never values   (admin key)
PUT    /api/secrets/{name}                                                  (admin key)
DELETE /api/secrets/{name}                                                  (admin key)
GET    /api/secrets/status
```

OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`, health at `/actuator/health`.

---

## Security

**Uploading a plugin is uploading executable code into the engine's JVM.** Treat
`/api/plugins/**` as a code-deployment endpoint, because that is what it is.

### What is enforced

| Control | Mechanism |
|---|---|
| Administrative authentication | shared key on `/api/plugins/**` and `/api/secrets/**`; the engine warns loudly at startup if unset |
| Dependency isolation | child-first class loader per plugin version |
| API surface restriction | plugins get `PluginContext` only — never the `ApplicationContext`, `MongoTemplate`, repositories, engine or registry |
| Credential scoping | operator-granted secret prefixes, plus a per-secret plugin allowlist; both must agree |
| Network allowlist | per-plugin host allowlist, no redirects, timeout and response-size ceilings |
| Data isolation | plugin document storage namespaced by plugin id; equality-only, capped queries |
| Log and record hygiene | secret values a plugin reads are redacted from logs, execution records and plugin request/response records |
| Integrity | SHA-256 recorded at upload and re-verified every time the JAR is staged; optional mandatory checksum and JAR signature |
| Kill switch | deactivate drains and unloads without touching any workflow |
| Accountability | full audit trail of installs, activations, deletions and secret reads, plus per-invocation history |

### What class loader isolation is not

**It is dependency isolation, not a security sandbox.** A loaded plugin runs in the engine JVM with the
engine's privileges. It can open sockets, read any file the process can read, start threads, call
`System.exit`, allocate until OOM, and use reflection to reach fields it was never given. The
`SecurityManager` that once constrained this is deprecated for removal (JEP 411) and is not a viable
answer on Java 17.

| | In-process (this implementation) |
|---|---|
| Dependency / version isolation | **strong** |
| API surface restriction | strong against accident, bypassable by reflection |
| Secret scoping, host allowlist | strong for cooperative code, bypassed by a plugin that opens its own socket |
| CPU, memory, thread limits | **absent** |
| Filesystem confinement | **absent** |
| `System.exit` prevention | **absent** |

### Therefore

* **Trusted, reviewed, signed plugins → in-process is correct.** Fast, simple, and the controls above are
  proportionate. Turn on `require-checksum` and `require-signature`.
* **Third-party or tenant-supplied plugins → run them out of process.** Nothing else is honest. Put each
  plugin in its own container and you get what the JVM cannot give you: cgroup CPU and memory limits,
  seccomp, a read-only root filesystem, network policy, and the ability to kill it.

The seam for that already exists. `PluginNodeExecutor` and `PluginContext` are the only places that touch
a plugin; an `OutOfProcessPluginHandle` speaking gRPC to a sidecar would replace them without the engine,
the node executors, the registry or any workflow definition changing. That is why the boundary is drawn
where it is.

### Safe to hot-load, and not

| Safe to load at runtime | Needs a restart or a separate JVM |
|---|---|
| new node types, new plugin versions | a breaking change to the SDK's binary contract |
| plugin configuration schemas and UI metadata | engine-wide Spring bean graph changes |
| plugin-bundled third-party libraries | untrusted or tenant code (→ container) |
| activation, deactivation, rollback | native agents, JVM flags, heap sizing |
| cron and event trigger registration | MongoDB connection topology |

---

## Configuration

Everything is environment-driven; no credential is defaulted.

| Variable | Purpose |
|---|---|
| `MONGODB_URI` | connection string; **never hardcode credentials** |
| `WORKFLOW_ADMIN_API_KEY` | required for plugin and secret endpoints |
| `WORKFLOW_SECRETS_KEY` | base64 AES key for secrets at rest — `openssl rand -base64 32` |
| `PLUGIN_WORKSPACE_DIR` | writable directory for staged plugin JARs |
| `WORKFLOW_INSTANCE_ID` | optional; derived from the hostname when unset |

Full tunables, with the reasoning behind each default, in
[`application.yml`](workflow-engine-core/src/main/resources/application.yml).

### MongoDB collections

`workflows` · `workflow_versions` · `workflow_executions` · `workflow_execution_logs` ·
`workflow_plugins` · `workflow_plugin_versions` · `plugin_executions` · `plugin_data` ·
`plugin_idempotency` · `workflow_schedules` · `workflow_secrets` · `workflow_audit_log`, plus the
`plugin_jars` GridFS bucket.

---

## Testing

```bash
mvn test                            # 142 unit tests, no Docker required
mvn verify -Pintegration-tests      # adds the Testcontainers integration tests (needs a Docker daemon)
```

Integration tests sit behind a profile rather than being bound to `verify`, so `mvn install` never requires
a Docker daemon — a CI stage that only needs to produce an artifact should not need one.

Unit tests cover the JSON codec, result and schema value types, path navigation, the safe expression
evaluator (including that dangerous constructs are refused), variable resolution and scoping, all four
built-in node executors, the retry template, permission and redaction guards, class loader isolation,
lease and drain semantics, version resolution, and **the complete plugin lifecycle** — install, probe,
isolated load, register, execute, drain, unload, reactivate, reload, delete — against the real manager,
validator, class loader, registry and node executor, with only MongoDB and GridFS substituted.

Integration tests (`@Tag("integration")`, run by `mvn verify`) drive the same flows over HTTP against a
real MongoDB: workflow authoring, validation, publishing, synchronous and asynchronous execution, form
parking and resumption, idempotent starts, and the full plugin lifecycle including that a deactivated
plugin makes dependent workflows fail with a clear error.

---

## Project layout

```
workflow-engine/
├── workflow-plugin-sdk/          the stable public API; no Spring, no Mongo, no Jackson
├── workflow-engine-core/         the Spring Boot application
│   └── com.orchpilot.workflow/
│       ├── controller/  service/  repository/  model/  dto/
│       ├── execution/            engine, context, retry, recovery
│       ├── node/                 contract, registry, four built-in executors
│       ├── plugin/               manager, registry, class loader, GridFS storage, contexts
│       ├── forms/                form definitions, versions, validation, field→variable mapping
│       ├── task/                 human tasks: assignment, claim, submission, history, deadlines
│       ├── auth/  access/        authentication, and group-based workflow authorization
│       ├── expression/  variable/  scheduler/  event/  exception/  config/  utility/
├── plugins/
│   ├── sendgrid-plugin/          SENDGRID_EMAIL
│   ├── restapi-plugin/           REST_API_CALL
│   └── slack-plugin/             SLACK_MESSAGE — proof that a new integration needs no core change
├── workflow-engine-ui/           Angular 20 console; renders node types from GET /api/nodes, so a
│                                 plugin uploaded at runtime needs no front-end release
├── examples/                     complete workflow definitions
├── Dockerfile  docker-compose.yml
├── ARCHITECTURE.md  PLUGIN_DEVELOPMENT.md
```

---

## The console

The Angular front end is built around the same principle as the engine: it hardcodes no node type and no
integration. The designer's palette, its property panels and the node browser are all rendered from
`GET /api/nodes`, so uploading a plugin makes its node type usable immediately.

| Screen | Purpose |
|---|---|
| Workflows | Inventory, with the live published version shown next to the status |
| Designer | Palette, canvas with drag-and-connect, schema-driven property panel |
| Executions | Every run; timeline showing retries, chosen branches and per-node errors |
| Tasks | My tasks, Available and (with `TASK_VIEW_ALL`) All. Claim, fill in, save a draft, submit, reassign, withdraw, and read the history |
| Forms | Inventory of forms, and a designer: palette from the server's field catalogue, drag-and-drop canvas, field-to-variable mapping, and a Preview that uses the same renderer as the task runtime |
| Node types | Everything this engine can currently execute, with each published schema |
| Plugins | Install, activate, deactivate, reload, roll back, invocation history |
| Secrets | Write-only credential management |
| Events | Emit an event, with its subscribers listed |

Details and design decisions in [workflow-engine-ui/README.md](workflow-engine-ui/README.md).
