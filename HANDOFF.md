# Handoff

A working context document for continuing this project on another machine. Written to be uploaded at the start
of a new session so nothing below has to be rediscovered.

Last updated: 2026-08-14.

---

## 1. What this project is

A workflow automation platform, plus a plugin registry microservice currently being built alongside it.

```
workflow-engine/                     Maven reactor, 7 modules
├── workflow-plugin-sdk/             stable public API plugins compile against
│                                    no Spring, no Mongo, no Jackson, on purpose
├── workflow-engine-core/            the workflow service        :8080
├── plugin-server/                   the plugin registry         :8085   ← in progress
├── plugins/sendgrid-plugin/         SENDGRID_EMAIL
├── plugins/restapi-plugin/          REST_API_CALL
├── plugins/slack-plugin/            SLACK_MESSAGE
└── workflow-engine-ui/              Angular 20 console          :4200
```

Stack: Java 17, Spring Boot 4.0.7, Spring Framework 7, Spring Security 7, Spring Data MongoDB 5, Maven,
Angular 20, MongoDB with GridFS.

**Not a git repository.** The directory is plain files. Copy the whole tree to the new machine, or put it under
version control first. `target/`, `node_modules/`, `.env.local` and `plugin-cache/` do not need copying.

---

## 2. What is complete and verified

Everything in this section was exercised against a running engine and a real MongoDB, not just compiled.

**Workflow engine.** Clean-architecture engine with a runtime Java plugin platform: child-first
`PluginClassLoader` per plugin version, GridFS JAR storage, load/unload/reload with execution draining, node
registry with no per-type branching, safe SpEL evaluation, idempotency on
`workflowExecutionId + nodeId`, retry and error policies, all five execution modes, resume after restart.
Verified by uploading three real plugin JARs to a running engine and watching `/api/nodes` go 4 to 7 with no
restart.

**Authentication.** Argon2id password hashing, JWT access tokens, rotating refresh tokens stored as SHA-256
hashes with reuse detection, RBAC on permissions rather than roles, login throttling, audit logging that
records no secrets.

**Group-based workflow authorization.** Groups hold workflow permissions, users belong to groups, workflows
have `accessGroups`. Effective permission is the intersection of the user's groups and the workflow's, then the
union of those groups' permissions, with an owner default set and an ADMIN override. Nothing cached, nothing in
the JWT, so a revocation takes effect immediately.

**Form designer and human tasks.** Form definitions with immutable published versions, 20 field types driven
from a server catalogue, field-to-variable mapping performed server-side, a shared Angular renderer used by
both the designer's Preview and the task runtime. `HumanTask` with claim, release, reassign, draft, submit,
withdraw, deadlines and an append-only history. A form node raises a task, parks the execution, and the
submission resumes it after server-side validation and mapping.

**Plugin registry, all seven phases.** See section 4.

**Email plugin.** An `EMAIL_SEND` node with full SMTP configuration on the node, bundling its own mail library.
See section 4b.

**Test counts.** 490 backend (40 SDK, 317 engine, 66 registry, 67 email plugin — one of which is skipped
unless a real SMTP server is named in the environment) and 139 frontend, plus **19 integration tests**. All
green at handoff.

---

## 3. How to build and run on a new machine

### Prerequisites

MongoDB on `localhost:27017`. Node and npm for the UI. A JDK 17 and Maven for the backend, which the previous
machine did not have on PATH, so a portable toolchain was downloaded into a scratch directory. On a new machine,
check first:

```bash
java -version
mvn -v
```

If either is missing, install a JDK 17 and Maven, or download a portable pair and set `JAVA_HOME` to the JDK
and call `mvn` by absolute path.

On the machine this was last built on, `mvn` was not on PATH but a complete Maven 3.9.12 was already cached at
`~/.m2/wrapper/dists/apache-maven-3.9.12/<hash>/bin/mvn`, and calling it by absolute path worked. The JDK there
is 21, not 17; the reactor targets `release 17`, so it builds and runs, but 17 is what the earlier phases were
verified on. Note that `~/.m2` on that machine had the compiler plugin cached but not surefire's test
dependencies, so the first `mvn test` needed network access even though `mvn compile` did not.

### Build

```bash
mvn clean install
```

Builds all seven modules and runs every test. Takes a few minutes cold.

### Run the workflow engine

```bash
./run-local.ps1
```

Generates `.env.local` with a JWT signing key, an encryption key and a workflow secrets key on first run, then
reuses them. That file is local only and is not in the repo; a new machine generates its own, which means
**anything encrypted on the old machine cannot be read on the new one**. That affects stored workflow secrets,
not workflows, forms, tasks or plugins.

`./run-local.ps1 -BootstrapAdmin` prompts for a first administrator. Needed on a fresh database.

### Run the plugin registry

```bash
java -jar plugin-server/target/plugin-server.jar
```

Starts on 8085 with no environment variables. Its configuration is in
`plugin-server/src/main/resources/application.yml`.

### Run the console

```bash
cd workflow-engine-ui
npm install
npm start
```

Serves on 4200 and proxies `/api` to 8080 via `proxy.conf.json`.

**Ports must be in `workflow.engine.security.allowed-origins`.** 4200 and 4300 are allowed. Serving the UI on
any other port makes login fail with a confusing 403, because browsers send `Origin` on POST even same-origin
and Spring Security's CORS layer rejects an unlisted one.

---

## 4. Plugin registry: state of play

The goal is a separate microservice that stores, versions and distributes plugin JARs, with the workflow service
becoming a client of it rather than managing JARs itself. Seven phases, all of them done bar the parts that
need a Docker daemon to verify.

### Design decisions already made

The split is between **what a plugin is** (identity, main class, node definitions, checksum, archive) which
belongs to the registry, and **what this engine has done with it** (installed, active, granted permissions,
operator settings) which belongs to the workflow service. The engine's existing
`workflow_plugin_versions` conflated both.

- The registry **never loads or executes** an uploaded archive. It parses the declared manifest as data and
  reads the archive index. All class loading stays in the workflow service.
- Plugin versions are keyed `pluginId:version`, so a duplicate upload is a failed insert rather than a
  check-then-write, and concurrent uploads cannot both win.
- The registry stores `requestedPermissions` from the manifest. What a plugin is **granted** is decided by an
  administrator in the workflow service and stored there. A plugin must not be able to grant itself anything.
- A published version is immutable. Only its lifecycle state changes.
- `DEPRECATED` still downloads, because a workflow pinned to it must keep running. `REVOKED` refuses download.
- Latest version excludes pre-releases, so nothing resolves "latest" to `2.0.0-rc.1`.
- Several versions of one plugin coexist locally, because a workflow published against `sendgrid:1.0.0` must
  keep running it after 1.2.0 is installed for something else.
- The engine must stay usable when the registry is down: cached catalogue, installed plugins keep executing.

### Phase 1, done. Skeleton and shared contract

New in the SDK: `SemanticVersion` with real precedence ordering, `VersionRange` for `>=1.0.0 <2.0.0`, and
`PluginManifest` for `META-INF/workflow-plugin.json`. The manifest is **declared** and readable without loading
code; the pre-existing `PluginDescriptor` is **observed** from a running instance. The engine will cross-check
them.

`plugin-server` module: Mongo and GridFS wiring, bound configuration that fails fast with a clean Description
and Action, resource-server security with a `PluginAuthority` model, the platform's `ApiError` shape, Swagger,
health.

### Phase 2, done. Registry core

`Plugin`, `PluginVersion`, `PluginNode`, `PluginDependency`, `PluginStatus`, `VersionOrder`, repositories,
`GridFsPluginStorage`, and the audit event. Upload with manifest extraction and validation, SHA-256, duplicate
rejection, lifecycle transitions, streaming download.

### Phase 3, done. Catalogue and service credentials

`GET /api/plugin-catalog` returning per plugin: identity, `latestVersion`, the latest version's SDK, Java and
engine-compatibility facts, its node metadata with configuration schemas, and a compact row per published
version with `version`, `status`, `checksum`, `fileSize`. The version rows matter: without them a workflow
service would need one request per pinned version, per sync, to validate a publish.

Strong `ETag` with `If-None-Match`, so a steady state costs a 304.

`POST /api/auth/token`, client credentials by HTTP Basic or form fields, 15-minute tokens carrying only the
client's registered authorities and no roles. Secrets stored BCrypt-hashed, verified against a dummy hash for
unknown client ids so the endpoint is not a client-id oracle.

The three sample plugins now ship `META-INF/workflow-plugin.json`, filtered from `${plugin.id}`,
`${plugin.class}` and `${project.version}` so a hand-maintained version cannot drift from the build.

### Phase 4, done. Engine side

- `PluginServerClient` and `RestPluginServerClient`: one token held and renewed a minute before expiry, a 401
  retried exactly once, separate clients for catalogue and download because their timeouts differ.
- `installed_plugins`: several versions per plugin, with granted permissions and settings held locally.
- `plugin_catalog_cache`: persisted, so a cached catalogue survives an engine restart.
- `PluginCompatibilityService`: SDK major line, Java feature version, declared engine range. The SDK's integer
  `PluginApi.VERSION` remains the authoritative gate.
- `PluginStatusService`: computes `NOT_INSTALLED`, `INSTALLED`, `UPDATE_AVAILABLE`, `INCOMPATIBLE`,
  `DEPRECATED`, `REVOKED`, `UNKNOWN_TO_REGISTRY` over the union of both sides. Precedence is deliberate:
  revocation outranks an update, incompatibility outranks an update.
- `GET /api/plugins/status`, `/status/{id}`, `/catalog-health`, `/registry`, `POST /api/plugins/sync`.

Verified with both services running: startup sync, marketplace listing, duplicate upload refused with 409, and
with the registry killed the marketplace still answers from cache while `/api/workflows` and `/api/nodes` stay
200.

### Phase 5, done. Installation

The engine can now install from the registry, and does not open a second loading path to do it.

- `PluginArchiveDownloader` downloads, verifies SHA-256 against the checksum the catalogue publishes, and
  promotes the verified archive into `plugin-cache/<id>/<ver>/`. **A version the catalogue publishes no
  checksum for is refused**, because absence of a checksum is not a weaker guarantee, it is none.
- Verified bytes are handed to the existing `PluginManager.install`, which already validates, identifies the
  archive by instantiating it in a throwaway class loader, stores it in GridFS and loads it. So there is one
  answer to "what versions does this engine have", and the security-sensitive code is not duplicated. The disk
  cache is an operator convenience; wiping it and restarting reloads everything from GridFS, which was tested.
- A plugin is installed with **no allowed hosts and no secret scopes** whatever its manifest requested, and the
  response says so. The registry records what a plugin asked for; an administrator here decides what it gets.
- `PluginUsageService` answers who depends on a version, separating nodes that pin an exact version from those
  resolving through the default. Uninstall and deactivate refuse while a published workflow depends on the
  version, naming the workflows and nodes.
- Update installs alongside, moves the default, then drains the old version. A version that is pinned, or that
  still has executions inside it, stays loaded and the response reports `previousVersionRetained`. Running two
  versions at once is a supported state, not a failed update.
- `plugin_installation_history` records INSTALL, UPDATE, UNINSTALL, ACTIVATE and DEACTIVATE, including the ones
  that failed or were refused, with the verified checksum and a duration.
- Endpoints: `POST /api/plugins/{id}/install`, `/versions/{version}/install`, `/update`,
  `/versions/{version}/activate`, `/versions/{version}/deactivate`,
  `DELETE /api/plugins/{id}/versions/{version}`, `GET /api/plugins/installation-history`. Installing takes
  `PLUGIN_UPLOAD`: it ends the same way an upload does, with third-party code in this JVM.
- `PluginServerUnavailableException` now maps to 503. It previously fell through to the generic handler and
  reported 500 for another service being down.

Verified with both services running, against real archives: install took `/api/nodes` from 4 to 5 with no
restart; a deliberately corrupted checksum in the registry was refused with 422, leaving `INSTALL_FAILED`, no
cached archive and no change to the node registry; a published workflow pinning `slack:1.0.0` refused both
uninstall and deactivate with 409 naming the workflow; updating to a real 1.1.0 retained 1.0.0 because of that
pin; archiving the workflow then allowed the uninstall; a restart with the cache directory deleted reloaded the
plugin from GridFS; and with the registry killed the marketplace still answered while an install failed with
503.

### Phase 6, done. The console

`/plugins` is now the marketplace; the previous local administration table moved to `/plugins/installed` and is
unchanged. `/plugins/:pluginId` is the detail page.

- `MarketplaceApiService` is a small signal store, like `NodeApiService`, because the marketplace, the detail
  page, the designer palette, the canvas and the upgrade dialog all need the same answer to "what is installed
  and what is available". Nothing re-derives status in the browser: the engine decides `UPDATE_AVAILABLE`
  against a precedence rule that excludes pre-releases, and a second implementation would be a second rule.
- The marketplace is one list over both sides, with filter chips and the catalogue's age stated in the header.
  A stale or unreachable registry is reported as a fact, not an error, because installed plugins keep running.
- The install dialog renders the two things Phase 5 deliberately returns and a toast would discard: that a
  plugin is granted **no hosts and no secret scopes** on install, and that an update may **retain the previous
  version** because something still pins it. A 409 refusal renders the blocking workflows as a list.
- The detail page merges registry versions with installed ones, so a version only one side has is visible, and
  shows the installation history including the failures and refusals.
- Designer: the palette flags a node type whose plugin is deprecated, revoked or updatable, as a hint rather
  than a filter, because every entry there is loaded and will execute. The canvas distinguishes `outdated` (a
  pin below the newest installed version) from `unavailable` (nothing provides the type). A **Plugin updates**
  toolbar button opens a dialog that repoints selected nodes, and deliberately leaves the workflow unsaved.
- `StatusPill` learned the marketplace statuses and install states. It was already the one place a status
  colour is decided; without this they all rendered grey.

**A registry bug found and fixed while verifying this.** `PluginRepository.findCatalogueEntries` required a
non-null `latestVersion`. A plugin whose only version is deprecated has none, because latest excludes
deprecated releases, so it vanished from the catalogue entirely and the engine reported it as
`UNKNOWN_TO_REGISTRY` rather than `DEPRECATED`. The fallback in `PluginCatalogEntry.of` written for exactly
that case could never be reached. The query now filters on the plugin's status alone; whether anything is
installable is decided downstream, which is where it was always meant to be.

Verified in a browser against both services: install from the marketplace, a refusal naming the blocking
workflow, an update that retained 1.0.0 and said why, the designer's outdated badge and upgrade dialog, the
repointed workflow publishing as v2, the previously blocked version then removing cleanly, and a plugin
deprecated upstream appearing as deprecated in both the marketplace and the palette.

### Phase 7, done except for what needs Docker

**Publish-time plugin dependency validation.** `PluginPublishPolicy` adds the one thing local state cannot know:
whether the registry has *withdrawn* a plugin. A revoked plugin is installed, ACTIVE and loaded, so every check
the validator already made passes while its publisher is telling every engine to stop using it. Revocation is
therefore the only upstream state that blocks a publish. Deprecation, and a newer version being installed
locally, are warnings: both describe a plugin that works, and refusing on either would make every publish
hostage to somebody else's release schedule. Every check degrades to silence when no catalogue exists, so an
engine with no registry publishes exactly as it did before.

Note on what was **not** built: cross-plugin Maven dependency conflict checking. Each plugin gets its own
child-first class loader, so two plugins bundling different versions of the same library is the case the
architecture already handles. Validating it would be inventing a problem the design solved.

**Docker Compose.** The registry is now a service in `docker-compose.yml` with its own database, and
`plugin-server/Dockerfile` builds it from the repository root because it compiles against the SDK. The engine
depends on it with `condition: service_started`, not `service_healthy`, deliberately: the engine survives a
missing registry, and waiting for health would turn a service it can lose into one it cannot start without.

Two latent defects fixed here: the engine's `Dockerfile` never copied `plugin-server`, so the reactor build
inside the image would fail on a missing module the moment that module was added in Phase 1; and the engine
gained a `plugin-cache` volume, matching the directory Phase 5 writes verified archives to.

**Integration tests: they now run, and they pass.** Three defects were in the way, all consequences of never
having been executed:

1. `AbstractMongoIntegrationTest` registered `spring.data.mongodb.uri`. Boot 4 binds from `spring.mongodb.uri`,
   so the container's URI was ignored and the suite would have connected to whatever local MongoDB it found and
   deleted collections in it. This is the same trap as section 5, in the tests themselves.
2. The tests authenticated with `X-Admin-Api-Key`. No Java code has read that property since authentication
   moved to JWTs, so every request they made would have been a 401. They now build a real `AuthPrincipal`.
3. `maven-failsafe-plugin` ran against the repackaged Boot jar, where application classes live under
   `BOOT-INF/classes`, so test discovery failed with `ClassNotFoundException` before a single test ran. Fixed
   with `<classesDirectory>`.

Also: the suite can now run **without Docker**, by setting `WORKFLOW_IT_MONGODB_URI` to a MongoDB it may write
to and wipe. Opt-in, never a silent localhost default, because these tests delete collections. That is how they
were verified here:

```
WORKFLOW_IT_MONGODB_URI=mongodb://localhost:27017 mvn verify -Pintegration-tests
```

**Still unverified: everything that needs a Docker daemon.** This machine has none, so the Compose file and the
two Dockerfiles have been written and structurally checked but never built or started. They are the one part of
the platform with no runtime evidence behind them. Treat `docker compose up` as untested.

---

## 4b. Email plugin (`plugins/email-plugin`)

An `EMAIL_SEND` node that talks to whatever SMTP server an operator has credentials for, rather than to one
provider's API as the SendGrid plugin does. Host, port, security and authentication are node configuration, with
presets for Gmail, Microsoft 365, Yahoo, Zoho, SendGrid, Amazon SES and Mailgun that fill blanks and never
overwrite a typed value. Two operations: `SEND`, and `TEST_CONNECTION` which performs DNS, TCP, TLS and AUTH and
disconnects without sending, so settings can be proven without mailing a real person.

Angus Mail 2.0.3 is bundled into `lib/` inside the archive, so the engine gains no mail dependency and another
plugin may bundle a different version.

**Decisions worth keeping.**

- **A literal password in the workflow is refused**, not merely discouraged. It would work, which is why: it
  would then be readable by anyone who can read the workflow, would travel in every export, and would sit in the
  version history. `credentialId`, `passwordSecret`, or a `${secret.X}` reference are the three accepted forms.
- **No filesystem attachment source.** A node that could attach a path assembled from a workflow variable is a
  way to read any file the engine can read and post it off site. `WORKFLOW_FILE` and `OBJECT_STORAGE` are
  declared but refused at execution with a message, rather than silently sending an empty file.
- **Authentication failures are never retried.** Repeating a rejected password is how a provider locks an
  account. 4xx replies are retried, 5xx are not, which is what SMTP itself says.
- **No session pooling.** Each send opens a session and closes the transport; a pool would have to be
  invalidated on a password rotation and would hold an authenticated connection to a third party between runs.
- **`§20`/`§21` of the spec asked for `test-connection` and `test-email` REST endpoints.** A plugin contributes
  node types, not HTTP endpoints — `PluginContext` exposes no way to register a controller, which is what stops
  a plugin adding unauthenticated surface to the engine. Delivered as the `TEST_CONNECTION` operation instead.
- **The manifest carries a warning** that SMTP sockets bypass the engine's allowed-hosts guard: that guard
  covers the plugin HTTP client, so this plugin can reach an arbitrary host and port on the engine's network.
  That is what an SMTP node fundamentally is; it is stated so the grant is made knowingly.

**Verified by running it**, not by a green compile. 67 tests, of which the integration tests drive the plugin
against a real SMTP server implemented in the test sources (`FakeSmtpServer`: socket, greeting, EHLO, AUTH, MAIL
FROM, RCPT TO, DATA). One test sends through a real provider and is skipped unless `EMAIL_PLUGIN_SMTP_HOST` and
friends are set. Beyond that, the built JAR was installed through the engine's **own** `JarUtils` and
`PluginClassLoader` in a JVM with no mail library on its application class path: the manifest was read, three
libraries were extracted from `lib/`, the plugin loaded child-first into `plugin-email:1.0.0`,
`jakarta.mail.Session` resolved only inside that loader, `EMAIL_SEND` was contributed, and a message with all
variables resolved reached the server.

A defect surfaced while writing those tests: the SMTP reply code was read only from the caught exception's own
message, but a refused recipient arrives as `SendFailedException("Invalid Addresses")` with the server's reply
on `getNextException()`. No code found meant "permanent", so **every greylisted message would have failed
permanently** — and greylisting works precisely by expecting the sender to try again. `EmailErrors` now walks
the chain.

`plugins/email-plugin/README.md` documents the fields, provider notes and error codes;
`plugins/email-plugin/examples/` holds a full workflow and a configuration for each provider.

---

## 4c. MongoDB plugin (`plugins/mongodb-plugin`)

Three node types — `MONGODB_READ`, `MONGODB_WRITE`, `MONGODB_ADMIN` — with 21 operations behind an operation
selector. Grouped by consequence rather than by method name, because that is the line permissions and risk
divide along; twenty node types would put Find One and Drop Collection side by side in the palette as equals.
Bundles the MongoDB driver 5.6.5 in `lib/`, so it neither uses nor disturbs the engine's own.

**Decisions worth keeping.**

- **Variables are substituted in the parsed structure, not in JSON text.** A customer named `O"Brien` would
  otherwise produce a syntax error at execution, on data-dependent input.
- **A whole-value placeholder is coerced to a number or a boolean; nothing else is guessed.** `age: "30"`
  matches nothing where age is numeric and the query is valid, so nothing says so. A 24-character hex string
  stays a string — MongoDB's own Extended JSON (`{"$oid": "${id}"}`) says ObjectId explicitly.
- **Every read is bounded** by a document limit and `maxTimeMS`, both capped by installation ceilings.
- **An empty filter on a bulk write is refused separately from the confirmation flag.** The usual cause is a
  variable that resolved to nothing; without the guard it deletes the collection and reports success.
- **A plain document as an `update` is refused**, pointing at Replace One. The shell silently replaces, and
  the difference is every field the document had.
- **The ten `MONGODB_*` permissions are not engine authorities.** The engine's `Permission` enum is compiled
  in and a plugin cannot add to it — a platform where installing a plugin invents authorities is one where
  installing a plugin can grant them. They are enforced against the acting user's roles via a mapping in the
  plugin's installation settings, as defence in depth behind `WORKFLOW_EXECUTE`. Unmapped is open; mapped
  refuses executions with no user, since schedules hold no role.
- **Transactions live inside one `BULK_WRITE` node and no longer.** A session held across workflow steps would
  hold locks while a human task waited, be killed by the server's transaction lifetime, and have nothing to
  commit with after an engine restart.
- **`visibleWhen` was added to the shared `SchemaForm`**, not a MongoDB-specific Angular component. A node with
  one operation selector and every field on screen is a form with forty controls, of which six matter; the
  condition is schema-driven so any plugin can use it and the "no front-end release per plugin" seam holds.

**Verified by running it.** 79 unit tests, 23 integration tests against a real MongoDB (`WORKFLOW_IT_MONGODB_URI`,
scratch database `mongodb_plugin_it`, dropped afterwards), and the built JAR loaded through the engine's own
`JarUtils` and `PluginClassLoader` in a JVM with no MongoDB driver on its application class path: four
libraries extracted, `MongoClients` resolving only inside `plugin-mongodb:1.0.0`, an insert and a read with
variables and types intact, and an unconfirmed bulk delete refused.

Two defects the tests caught: `cond ? (int) value : value` has type `long`, so every coerced integer boxed as
a `Long` and reached BSON as an int64; and `distinct(field, filter, Object.class)` fails with "can't find a
codec", which says nothing about the field being queried — it asks for `BsonValue` now.

---

## 4d. Editing a plugin's allowed hosts after install

An administrator can now change a plugin version's allowed hosts (and secret scopes, and the events flag)
without reinstalling. `PUT /api/plugins/{pluginId}/permissions?version={v}` with `{allowedHosts, secretScopes,
eventsEnabled}` persists the change and, when the version is loaded, reloads it so the new allowlist is live at
once — `reload` rebuilds the `PluginContext`, and the HTTP client's allowlist comes from the stored
permissions. The whole set is sent, not a delta, so the editor's empty host list reads as the deny-all it looks
like; the timeout ceilings and the data-store flag are left untouched because the request does not carry them.
Takes `PLUGIN_UPLOAD`, not the lesser activate permission — granting a plugin a host widens its reach exactly
as an install does. In the console it's the **Edit** button in each version's permissions cell on the Plugins
page. Enforced through the same administrator-grants-not-plugin model: a plugin still cannot widen its own.

Note this binds the HTTP client only. The MongoDB and SMTP plugins open their own sockets, so the host list
does not constrain them — the editor says so.

---

## 4e. VPN plugin (`plugins/vpn-plugin`)

A single `VPN` node dispatching to a pluggable `VpnProvider` SPI, with AWS, Azure, GCP and generic
IPsec/OpenVPN/WireGuard providers. A new provider is a class plus one line in `VpnProviderRegistry` — no engine,
node, schema or designer change, which is the whole point.

**The load-bearing honesty decisions, all forced by reality and demanded by the spec:**

- **Control plane, not data plane.** Cloud Site-to-Site VPNs have no "connect" verb (tunnels come up when the
  peer negotiates IKE); host tunnels need a privileged client the engine JVM must not run. So `CONNECT`
  converges and reports, `DISCONNECT` is *refused* by the cloud providers rather than faked, and statuses map
  from real provider states. Nothing claims CONNECTED it cannot prove.
- **Cloud calls go through `PluginHttpClient`, not bundled SDKs.** The archive has zero dependencies. This
  keeps it offline-installable and — the reason it matters here — binds cloud calls to the plugin's allowed
  hosts (the host-editor from 4d). AWS is signed with a hand-written **SigV4** (tested against FIPS/RFC crypto
  vectors); Azure/GCP take an OAuth bearer token from the secret store rather than shipping an unverifiable
  token-minting flow.
- **Generic providers report what they verified.** OpenVPN-over-TCP opens a real socket → "reachable"
  (CONNECTING at most, never CONNECTED). UDP protocols (IPsec/WireGuard) can't be probed without bringing the
  tunnel up, so they validate config and say plainly the tunnel state is not observable — the spec's "do not
  falsely report connectivity" as a method that won't lie.
- **`UNKNOWN` ≠ `FAILED`** in the status model, so a restricted credential doesn't make a workflow tear down a
  healthy connection.
- Credentials come only from the secret store (no node field holds a key); outputs are nested under
  `vpnResult` (the dotted-key persistence trap again); the AWS XML parser refuses DTDs/external entities
  (XXE/SSRF).
- The advanced ops (CREATE/DELETE/ROTATE/UPDATE) are noted as a planned SPI extension — the given
  `VpnProvider` interface has no such methods, so the node returns `VPN_UNSUPPORTED_OPERATION` rather than
  pretending.

**Verified by running it:** 35 unit tests (SigV4 vs. vectors, provider status mappings vs. canned responses,
OpenVPN-over-TCP against a real local socket, node dispatch/wait/timeout/credential-resolution against a fake
provider), plus the built JAR loaded through the engine's own `PluginClassLoader` (zero bundled libs,
`plugin-vpn:1.0.0`, a WireGuard GET_INFO with the private key absent from every output and log line). No test
reaches a real cloud — none could here, and a live account would add nothing to the mappings verified. See
`plugins/vpn-plugin/README.md`.

---

## 5. Environment traps, all hit at least once

These cost real time on the previous machine.


**A dot in a plugin's output key wedges the execution in RUNNING.** Node outputs are published into the
variable store, which is persisted as a MongoDB document, and Spring Data refuses a map key containing a dot
(no `setMapKeyDotReplacement` is configured). So a plugin publishing `result.insertedId` as a literal key throws
while the *execution is being saved* — after the node has already written to the database or sent the mail. The
symptom is a workflow stuck at whatever node last persisted cleanly, with the side effect performed and nothing
recorded, which is nowhere near the cause. Both the email and MongoDB plugins were written this way before it
was noticed. Publish a nested object instead; `VariableMapper` resolves a dotted output name into a structure,
so an output mapping of `result.insertedId` reads it unchanged. `VariableStore.putNodeOutputs` now refuses the
flat form and names the node, and the execution engine turns that into a `NODE_OUTPUT_REJECTED` failure rather
than a hang.

**Cookies are scoped by host and path, and not by port.** A console on `localhost:4300` and one on
`localhost:4200` share a single cookie jar, and both services put their refresh cookie on `/api/auth`. The only
thing keeping the two sessions apart is the cookie name: the platform issues `workflow_refresh_token`, the
registry issues `plugin_registry_refresh`. Giving them the same name makes signing in to either application
silently end the other's session, which presents as a mysterious logout with nothing in either log to explain
it. `RefreshCookiesTest` pins the name, and asserts that the platform's cookie arriving on the same request is
ignored rather than mistaken for ours.

**Spring Boot 4 binds Mongo from `spring.mongodb.*`, not `spring.data.mongodb.*`.** A URI under the old prefix
is ignored silently and the driver falls back to a database called `test`. Everything then works and writes to
the wrong place. Both services log the database they actually connected to at startup for this reason.

**`spring.mongodb.database` wins over the database named in `MONGODB_URI`.** Overriding only the URI to point a
throwaway instance at a scratch database silently keeps writing to `workflow_engine`. Deliberate, and commented
in `application.yml`, but easy to walk into. Set `MONGODB_DATABASE` as well, and check the startup line that
names the database actually connected to.

**Spring Boot 4 ships Jackson 3** (`tools.jackson.core`), not Jackson 2. The core avoids naming Jackson
directly.

**Spring Data does not create repository proxies for interfaces nested inside a container class.** Grouping two
repositories into one file to save a file produces a context that fails to start. Every repository here is
top-level.

**`@ConditionalOnMissingBean` does not work on a component-scanned class.** It is only honoured on a `@Bean`
method. On a `@Component` the condition sees the class itself and the bean excludes itself.

**`RestClient.Builder` is not an auto-configured bean** in Boot 4 with the web starter. Build the client with
`RestClient.builder()`.

**`@ExceptionHandler(Exception.class)` swallows the framework's own 404s.** `NoResourceFoundException` and
friends implement `ErrorResponse` and already know their status; handle them explicitly or an unmapped path
reports a server fault.

**Angular: `[value]` on a `<select>` is applied before its options exist**, so a saved selection renders as
nothing. Use `[selected]` on each option. This affected both the form picker and the shared schema form.

**PowerShell variables are case-insensitive.** `$r` and `$R` are the same variable. Hit twice.

**`Get-Date -UFormat %s` is local time, not the UTC epoch.** It was 19800 seconds ahead here, which made a
"expired" test token look valid and produced a false security finding. Use
`[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()`.

**Quoting an ETag through PowerShell into curl eats the quotes.** A conditional-request test failed three times
for this reason while the server was correct. Prefer a unit test for validator comparison logic.

---

## 6. Security posture and what must change before anything shared

The platform deliberately ships **committed development secrets in `application.yml`**, at the user's explicit
instruction, so it starts with no environment setup. They are strong random values and they are published in
version control, identical in every checkout.

| Setting | Environment override | What it grants |
|---|---|---|
| `security.jwt.secret` | `JWT_SECRET` | forge a token for any user |
| `security.encryption.key` | `APP_ENCRYPTION_KEY` | decrypt stored plugin credentials |
| `workflow.engine.secrets.master-key` | `WORKFLOW_SECRETS_KEY` | decrypt stored workflow secrets |
| `app.bootstrap-admin.password` | `BOOTSTRAP_ADMIN_PASSWORD` | sign in as administrator |
| `plugin.server.client-secret` | `PLUGIN_SERVER_CLIENT_SECRET` | download every plugin archive |
| `plugin-server.security.jwt-secret` | `PLUGIN_SERVER_JWT_SECRET` | mint a token the registry accepts |
| `plugin-server.bootstrap-client.client-secret` | `PLUGIN_SERVICE_CLIENT_SECRET` | download every archive |

`CommittedKeyDetector` in the engine and `CommittedSecretDetector` in the registry name whichever of these are
in use at every start, and **refuse to start** under a `prod`, `production` or `staging` profile.

The two JWT secrets must match between the services, because HS256 is symmetric. The client secret must match
on both sides too.

Standing rules followed throughout, worth keeping: passwords are hashed and never reversible, no secrets in
JWTs, public registration cannot grant ADMIN, frontend permission checks are cosmetic and the server authorises
every request independently, audit records carry field names and never values, and class-loader isolation is
documented as **not** a security sandbox.

**Credentials not recorded here.** The administrator password on the previous machine was rotated away from the
committed development value by the user. It is not written down in this file or anywhere in the repo. On a fresh
database, create an administrator with `./run-local.ps1 -BootstrapAdmin`.

---

## 7. Known gaps and loose ends

- Task notifications are an interface with one logging implementation. No mail transport is configured. The
  intended real implementation is the SendGrid plugin the platform already loads.
- `POST /api/workflows/{id}/clone` does not exist, so `WORKFLOW_CLONE` is enforceable but unused. Version
  endpoints are not separately gated, so `WORKFLOW_VERSION_*` are unattached.
- The workflow designer does not hide Publish and Delete based on `/my-permissions`. Cosmetic; the server
  refuses correctly.
- **Docker is installed on neither machine this project has been developed on.** The Compose file, both service
  Dockerfiles and the UI image have therefore never been built or started. Everything else has runtime evidence
  behind it; this does not.
- The leftovers listed here previously — a `test` database holding three plugin JARs, a `Picker round trip`
  workflow, the `approver1` and `outsider1` accounts, a `Finance approvers` group, and the
  `plugin_registry_p3` / `workflow_engine_p4` scratch databases — belonged to the previous machine's MongoDB
  and do not exist on this one. Nothing needs cleaning up there.
- On the current machine, `workflow_engine` exists but holds only a bootstrap `admin` account and a plugin
  catalogue cache pointing at a registry on 8095 that is no longer running, both created during Phase 5
  verification. The cache refreshes on the next sync. Scratch databases used for verification
  (`plugin_registry_p5`, `workflow_engine_p5`, `plugin_registry_p6`, `workflow_engine_p6`) were dropped
  afterwards.
- **Getting back into the registry when nobody has the admin password.** The first administrator's password is
  generated on a first start with no accounts, printed once to that log, and never printed again — the
  initializer only fires when the `users` collection is empty, so setting `INITIAL_ADMIN_PASSWORD` afterwards
  does nothing. Five wrong attempts lock the account for fifteen minutes. Recovery is an administrative reset:
  write a fresh `{argon2}` hash into `plugin_registry.users`, produced with the same parameters as
  `AuthProperties.Password` (salt 16, hash 32, parallelism 1, 19456 KiB, 2 passes), clear `accountLocked`,
  `lockedUntil` and `failedLoginAttempts`, and leave `mustChangePassword` true. Done once already, on
  2026-08-17.
- The frontend suite runs headless with `CHROME_BIN` pointed at the installed Chrome:
  `CHROME_BIN="/c/Program Files/Google/Chrome/Application/chrome.exe" npx ng test --watch=false --browsers=ChromeHeadless`.

---

## 8. Working preferences established with the user

- **The user runs the workflow engine themselves**, from an Eclipse debug launch on port 8080, using their own
  JDK. Do not start or stop anything on 8080. Diagnose and report instead. A throwaway instance on another port
  for verification is fine.
- Configuration goes in `application.yml`, not OS-level environment variables. Flag the security tradeoff once,
  then comply.
- The user wants work verified by running it, not by a green compile. Several real defects in this project were
  found only by starting the application.
- Deliver a design before implementing something large, then implement phase by phase.

---

## 9. Suggested first prompt on the new machine

> Read HANDOFF.md in the project root. All seven phases of the plugin registry are done, except that nothing
> involving Docker has ever been run: the Compose file and the three Dockerfiles are written but unverified.
> If this machine has a Docker daemon, start there — `docker compose up` and then the integration tests under
> Testcontainers rather than against a local MongoDB.
