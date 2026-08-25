# Writing a Workflow Plugin

Everything the engine can do beyond the four built-in node types comes from a plugin. This is the whole
guide: one dependency, one interface, one JAR, one upload.

---

## 1. Add the SDK

```xml
<dependency>
    <groupId>com.orchpilot.workflow</groupId>
    <artifactId>workflow-plugin-sdk</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

**`provided` is not optional.** The engine's plugin class loader delegates `com.orchpilot.workflow.sdk.*`
to its own parent, so the SDK classes must come from the engine. If you bundle a copy, your
`NodeExecutionResult` is a different class from the engine's and every call fails with a
`ClassCastException` that looks impossible to debug.

The SDK has no transitive dependencies at all — no Spring, no Jackson, no HTTP client. That is
deliberate: anything it depended on would become a version every plugin author had to live with.

---

## 2. Implement `WorkflowNodePlugin`

```java
public class SlackPlugin implements WorkflowNodePlugin {

    private volatile PluginContext context;

    @Override public String getId()          { return "slack"; }      // stable across versions
    @Override public String getName()        { return "Slack Plugin"; }
    @Override public String getVersion()     { return "1.0.0"; }      // must match the upload
    @Override public String getDescription() { return "Posts messages to Slack"; }
    @Override public PluginType getPluginType() { return PluginType.NODE; }

    @Override public void initialize(PluginContext pluginContext) {
        this.context = pluginContext;                                  // safe to retain
    }

    @Override public void destroy() {
        // Release everything you allocated. A thread left running here pins your class
        // loader forever and leaks it on every reload.
    }

    @Override public List<NodeDefinition> getNodeDefinitions() {
        return List.of(NodeDefinition.builder("SLACK_MESSAGE")
                .displayName("Send Slack Message")
                .category("Communication")
                .icon("message")
                .description("Posts a message to a channel")
                .configurationSchema(SchemaBuilder.object()
                        .secretRef("botTokenSecret", "Bot token secret name", true)
                        .string("channel", "Channel", true)
                        .text("text", "Message", true)
                        .build())
                .outputVariables("ok", "ts")
                .idempotent(false)      // see §6
                .build());
    }

    @Override public NodeExecutionResult execute(NodeExecutionContext ctx) {
        String token = context.secrets().require(ctx.configuration().requireString("botTokenSecret"));
        String body = Json.write(Map.of(
                "channel", ctx.configuration().requireString("channel"),
                "text", ctx.configuration().requireString("text")));

        HttpResponseView response = context.http().execute(
                HttpRequestSpec.post("https://slack.com/api/chat.postMessage", body)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .build());

        if (!response.isSuccess()) {
            boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
            return NodeExecutionResult.failure("SLACK_HTTP_" + response.statusCode(),
                    "Slack returned " + response.statusCode(), retryable);
        }
        Map<String, Object> parsed = Json.parseObject(response.body());
        return NodeExecutionResult.success(Map.of("ok", true, "ts", parsed.get("ts")));
    }
}
```

Three interfaces exist; pick by what you are contributing:

| Interface | For | Extra methods |
|---|---|---|
| `WorkflowNodePlugin` | node types on the canvas | `getNodeDefinitions`, `execute` |
| `ActionPlugin` | callable operations that are not nodes, e.g. a "test connection" button | `getSupportedActions`, `invoke` |
| `TriggerPlugin` | something that watches the outside world and emits events | `start`, `stop` |

---

## 3. Package it

```xml
<build>
  <finalName>${project.artifactId}-${project.version}</finalName>
  <plugins>
    <plugin>
      <artifactId>maven-jar-plugin</artifactId>
      <configuration>
        <archive>
          <manifestEntries>
            <Workflow-Plugin-Class>com.acme.SlackPlugin</Workflow-Plugin-Class>
            <Workflow-Plugin-Id>slack</Workflow-Plugin-Id>
            <Workflow-Plugin-Version>${project.version}</Workflow-Plugin-Version>
            <Workflow-Plugin-Api-Version>1</Workflow-Plugin-Api-Version>
          </manifestEntries>
        </archive>
      </configuration>
    </plugin>
  </plugins>
</build>
```

The engine finds your class by, in order: the `Workflow-Plugin-Class` manifest attribute, a
`META-INF/services/com.orchpilot.workflow.sdk.plugin.WorkflowPlugin` entry, or a `mainClass` supplied
with the upload. Prefer the manifest — it is explicit and cannot be got accidentally wrong.

**Bundling your own dependencies.** Put them under `lib/` inside your JAR:

```
slack-plugin-1.0.0.jar
├── com/acme/SlackPlugin.class
├── lib/okhttp-4.12.0.jar
└── lib/kotlin-stdlib-1.9.0.jar
```

The engine extracts them and adds them to your class path. Because the loader is child-first, your
versions win for your code and stay invisible to the engine and to every other plugin. Two plugins can
use incompatible versions of the same library.

---

## 4. Upload it

```bash
curl -X POST http://localhost:8080/api/plugins/upload \
  -H "X-Admin-Api-Key: $WORKFLOW_ADMIN_API_KEY" \
  -F "file=@target/slack-plugin-1.0.0.jar" \
  -F "allowedHosts=slack.com,*.slack.com" \
  -F "secretScopes=slack." \
  -F "activate=true"
```

That is the whole deployment. No restart, no rebuild of the engine. `GET /api/nodes` now includes
`SLACK_MESSAGE` with your configuration schema, and a workflow can use it immediately.

`allowedHosts` and `secretScopes` are granted by the operator, not declared by you. A plugin cannot
widen its own permissions, which is why they are upload parameters rather than manifest attributes.

---

## 5. What you get, and what you do not

`PluginContext`, handed to you once at `initialize`:

| Accessor | What it gives you |
|---|---|
| `logger()` | tagged with your plugin id and version; secrets you read are redacted from it |
| `settings()` | non-secret, installation-scoped configuration from the upload |
| `secrets()` | scoped, audited credential access; the only sanctioned source |
| `http()` | engine-owned HTTP client with allowlist, timeout and response-size limits |
| `dataStore()` | document storage namespaced to your plugin id |
| `idempotency()` | claim/complete keys for side effects finer-grained than a node |
| `events()` | emit named business events |
| `workspace()` | scratch directory, deleted when your version is unloaded |

Not available, on purpose: the Spring `ApplicationContext`, `MongoTemplate`, engine repositories, the
execution engine, the plugin registry. A plugin that could reach those could start workflows behind the
engine's back, rewrite execution state, or read every other plugin's data.

---

## 6. Rules that will bite you if you ignore them

**Be thread-safe.** One instance serves every concurrent execution. Per-execution state in an instance
field is a data race between two workflows. The only field you should have is the context.

**Never retain `NodeExecutionContext`.** It belongs to one attempt. Its configuration is that attempt's
resolved configuration; keeping it means acting on stale data.

**Return failures, don't throw them.** `NodeExecutionResult.failure(code, message, retryable)` lets the
engine apply your node's retry and error policy. An escaped exception is converted to a failure, but
loses the chance to mark itself retryable. Distinguish honestly: a 429 or a 503 is retryable, a 400 is
not, and retrying it only burns the retry budget.

**Declare `idempotent` accurately.** It is the single most consequential line in your node definition:

* `idempotent(false)` — the engine installs its guard. A retry or a post-crash resume replays your
  recorded outputs instead of calling you again. Correct for sending email, posting a message, charging
  a card.
* `idempotent(true)` — no guard; you may be called again. Correct for reads, and **required** for
  anything used in a polling loop, where every iteration is deliberately a repeat. If you need
  conditional deduplication, declare `true` and use `context.idempotency()` yourself with a config flag
  — that is what the REST API plugin does.

For non-idempotent nodes, also pass `ctx.idempotencyKey()` to the downstream provider when it supports
one. That covers the window where your call succeeded but the engine died before recording it.

**Clean up in `destroy()`.** Threads, connection pools, timers, watchers, file handles. Anything still
alive holds your class loader, and every reload then leaks another one. Name your threads after your
plugin id so a leak is diagnosable.

**Never log a credential.** The engine redacts secret values it handed you on a best-effort basis, but
that is a safety net, not a licence.

---

## 7. Testing

Your `execute` method takes an interface, so a unit test is ordinary Java: implement
`NodeExecutionContext` and `PluginContext` with stubs, or use a mocking library, and assert on the
returned `NodeExecutionResult`. No engine, no Spring, no database.

For an end-to-end check, upload the JAR to a locally running engine, publish a one-node workflow, and
execute it. `GET /api/plugins/{pluginId}/executions` shows exactly what your plugin sent and received,
with secrets redacted.

---

## 8. Versioning

The plugin id is stable; the version is not. Install as many versions as you like side by side:

```
sendgrid
├── 1.0.0   ← a workflow pinned here keeps this behaviour forever
├── 1.1.0
└── 2.0.0   ← the default, serving nodes that do not pin a version
```

A workflow node that sets `pluginVersion` is honoured exactly. Uploading a new version never changes
what a pinned node does, and `POST /api/plugins/{id}/default-version` chooses what unpinned nodes get.

Bump `Workflow-Plugin-Api-Version` only if you rebuild against a new major SDK. The engine refuses
plugins outside the API range it supports rather than loading them and failing later.
