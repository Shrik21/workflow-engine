# GCP VPC / Network Plugin

`orchpilot-gcp-network` — VPCs, subnets, firewall rules, routes, Cloud Routers, Cloud NAT and VPC peering as
OrchPilot workflow nodes, plus a whole-network inspection operation for the AI Agent.

32 operations. One row in the designer palette.

---

## 1. Project structure

```
plugins/gcp-network-plugin/
├── pom.xml
├── README.md
├── examples/
│   ├── provision-vpc.json            VPC → subnets → firewall → router → NAT → inspect
│   └── audit-and-teardown.json       inspect → human approval → delete
└── src/
    ├── main/
    │   ├── java/com/orchpilot/plugin/gcp/network/
    │   │   ├── GcpNetworkPlugin.java          entry point: catalogue, execute, audit
    │   │   ├── NetworkOperations.java         the 32-operation dispatch
    │   │   ├── NodeSchemas.java               each operation's configuration form
    │   │   ├── model/
    │   │   │   └── NetworkOperation.java      operations, risk levels, permissions
    │   │   ├── client/
    │   │   │   ├── ComputeClient.java         Compute Engine v1 REST + operation polling
    │   │   │   ├── GoogleCredentials.java     service-account JSON → signing key
    │   │   │   └── GoogleTokenSource.java     JWT-bearer exchange, cached
    │   │   ├── service/
    │   │   │   └── NetworkResources.java      GCP JSON → workflow-shaped output
    │   │   ├── validation/
    │   │   │   ├── CidrValidator.java         CIDR checks before the round trip
    │   │   │   └── FirewallExposure.java      administrative-port exposure assessment
    │   │   └── exception/
    │   │       └── GcpNetworkException.java   error codes and retryability
    │   └── resources/META-INF/
    │       └── workflow-plugin.json           manifest: 32 nodes, capabilities, permissions
    └── test/java/com/orchpilot/plugin/gcp/network/
        ├── GcpNetworkPluginTest.java          25 end-to-end tests over a scripted API
        ├── ValidationTest.java                26 CIDR and firewall-exposure tests
        └── support/
            ├── FakeHttpClient.java
            └── TestSupport.java
```

---

## 2. Maven dependencies

Only the SDK, and it is `provided` — the engine supplies it at runtime, so the shipped JAR carries nothing but
this plugin's own classes.

```xml
<dependency>
    <groupId>com.orchpilot.workflow</groupId>
    <artifactId>workflow-plugin-sdk</artifactId>
    <scope>provided</scope>
</dependency>
```

### Why there is no `google-cloud-compute`

**This is a deliberate deviation from the specification, which asked for the official Google Cloud Java SDK.**

That library is not resolvable in this build environment. Maven runs offline here and the local repository
contains none of `google-cloud-compute`, `google-cloud-core`, `gax`, `google-auth-library-oauth2-http`,
`protobuf-java`, `grpc-api` or `proto-google-common-protos` — I checked each one before choosing. A plugin
depending on it could not be compiled at all.

The replacement is the Compute Engine **v1 REST API**, reached through the engine's own allow-listed
`PluginHttpClient`, with the standard service-account **JWT-bearer** exchange signed by the JDK's RS256. This
is not a new pattern: the `gcp-compute-instance` and `gcp-kubernetes` plugins in this repository already work
exactly this way. It also avoids loading the largest dependency tree of any plugin here into the engine's JVM
behind an isolating class loader.

If the SDK later becomes available and you want to switch, `ComputeClient` is the only class that would change.

---

## 3. Plugin metadata

`src/main/resources/META-INF/workflow-plugin.json`, with `${plugin.id}` and `${project.version}` filtered in at
build time so the manifest can never disagree with the POM.

| Field | Value |
|---|---|
| `pluginId` | `orchpilot-gcp-network` |
| `version` | `1.0.0` |
| `sdkVersion` | `1.0.0` |
| `engineCompatibility` | `>=1.0.0 <2.0.0` |
| `pluginType` | `NODE` |
| `category` | `GCP` |
| `supportsAI` | `true` |
| `nodes` | 32 |
| `capabilities` | 32 (`gcp.network.create`, `gcp.subnet.create`, …) |

> **Version bumps:** `DefaultPluginManager.probe()` reads the version from the *compiled Java constant*, not the
> POM. If you bump this plugin's version, change `GcpNetworkPlugin.PLUGIN_VERSION` **and** the POM together, or
> the upload is rejected with "the upload declares version X but the archive reports Y".

---

## 4. Operations

Risk drives the `destructive` flag on the node, which is what the approval policy reads.
`destructive = HIGH || CRITICAL`.

### VPC networks

| Operation | Node type | Capability | Risk | Permission |
|---|---|---|---|---|
| Create VPC | `GCP_NET_CREATE_VPC` | `gcp.network.create` | HIGH | `GCP_NETWORK_CREATE` |
| Get VPC | `GCP_NET_GET_VPC` | `gcp.network.get` | READ | `GCP_NETWORK_READ` |
| List VPCs | `GCP_NET_LIST_VPCS` | `gcp.network.list` | READ | `GCP_NETWORK_READ` |
| Update VPC | `GCP_NET_UPDATE_VPC` | `gcp.network.update` | HIGH | `GCP_NETWORK_UPDATE` |
| Delete VPC | `GCP_NET_DELETE_VPC` | `gcp.network.delete` | **CRITICAL** | `GCP_NETWORK_DELETE` |

### Subnets

| Operation | Node type | Capability | Risk | Permission |
|---|---|---|---|---|
| Create Subnet | `GCP_NET_CREATE_SUBNET` | `gcp.subnet.create` | MEDIUM | `GCP_SUBNET_CREATE` |
| Get Subnet | `GCP_NET_GET_SUBNET` | `gcp.subnet.get` | READ | `GCP_SUBNET_READ` |
| List Subnets | `GCP_NET_LIST_SUBNETS` | `gcp.subnet.list` | READ | `GCP_SUBNET_READ` |
| Update Subnet | `GCP_NET_UPDATE_SUBNET` | `gcp.subnet.update` | HIGH | `GCP_SUBNET_UPDATE` |
| Delete Subnet | `GCP_NET_DELETE_SUBNET` | `gcp.subnet.delete` | **CRITICAL** | `GCP_SUBNET_DELETE` |

### Firewall rules

| Operation | Node type | Capability | Risk | Permission |
|---|---|---|---|---|
| Create Firewall Rule | `GCP_NET_CREATE_FIREWALL` | `gcp.firewall.create` | HIGH | `GCP_FIREWALL_CREATE` |
| Get Firewall Rule | `GCP_NET_GET_FIREWALL` | `gcp.firewall.get` | READ | `GCP_FIREWALL_READ` |
| List Firewall Rules | `GCP_NET_LIST_FIREWALLS` | `gcp.firewall.list` | READ | `GCP_FIREWALL_READ` |
| Update Firewall Rule | `GCP_NET_UPDATE_FIREWALL` | `gcp.firewall.update` | HIGH | `GCP_FIREWALL_UPDATE` |
| Delete Firewall Rule | `GCP_NET_DELETE_FIREWALL` | `gcp.firewall.delete` | **CRITICAL** | `GCP_FIREWALL_DELETE` |

### Routes

| Operation | Node type | Capability | Risk | Permission |
|---|---|---|---|---|
| Create Route | `GCP_NET_CREATE_ROUTE` | `gcp.route.create` | HIGH | `GCP_ROUTE_CREATE` |
| Get Route | `GCP_NET_GET_ROUTE` | `gcp.route.get` | READ | `GCP_ROUTE_READ` |
| List Routes | `GCP_NET_LIST_ROUTES` | `gcp.route.list` | READ | `GCP_ROUTE_READ` |
| Delete Route | `GCP_NET_DELETE_ROUTE` | `gcp.route.delete` | **CRITICAL** | `GCP_ROUTE_DELETE` |

### Cloud Router

| Operation | Node type | Capability | Risk | Permission |
|---|---|---|---|---|
| Create Cloud Router | `GCP_NET_CREATE_ROUTER` | `gcp.router.create` | MEDIUM | `GCP_ROUTER_CREATE` |
| Get Cloud Router | `GCP_NET_GET_ROUTER` | `gcp.router.get` | READ | `GCP_ROUTER_READ` |
| List Cloud Routers | `GCP_NET_LIST_ROUTERS` | `gcp.router.list` | READ | `GCP_ROUTER_READ` |
| Delete Cloud Router | `GCP_NET_DELETE_ROUTER` | `gcp.router.delete` | **CRITICAL** | `GCP_ROUTER_DELETE` |

### Cloud NAT

NAT is not a resource of its own in GCP — it lives in its router's `nats[]`, so every NAT operation is a
read-modify-write `PATCH` of the router.

| Operation | Node type | Capability | Risk | Permission |
|---|---|---|---|---|
| Create Cloud NAT | `GCP_NET_CREATE_NAT` | `gcp.nat.create` | HIGH | `GCP_NAT_CREATE` |
| Get Cloud NAT | `GCP_NET_GET_NAT` | `gcp.nat.get` | READ | `GCP_NAT_READ` |
| Update Cloud NAT | `GCP_NET_UPDATE_NAT` | `gcp.nat.update` | HIGH | `GCP_NAT_UPDATE` |
| Delete Cloud NAT | `GCP_NET_DELETE_NAT` | `gcp.nat.delete` | **CRITICAL** | `GCP_NAT_DELETE` |

### VPC peering

| Operation | Node type | Capability | Risk | Permission |
|---|---|---|---|---|
| Create VPC Peering | `GCP_NET_CREATE_PEERING` | `gcp.peering.create` | HIGH | `GCP_PEERING_CREATE` |
| Get VPC Peering | `GCP_NET_GET_PEERING` | `gcp.peering.get` | READ | `GCP_PEERING_READ` |
| List VPC Peerings | `GCP_NET_LIST_PEERINGS` | `gcp.peering.list` | READ | `GCP_PEERING_READ` |
| Delete VPC Peering | `GCP_NET_DELETE_PEERING` | `gcp.peering.delete` | **CRITICAL** | `GCP_PEERING_DELETE` |

### Inspection

| Operation | Node type | Capability | Risk | Permission |
|---|---|---|---|---|
| Inspect Network | `GCP_NET_INSPECT` | `gcp.network.inspect` | READ | `GCP_NETWORK_INSPECT` |

---

## 5. One node in the designer

The specification asks for **one plugin = one workflow node**. That is what an author sees: the palette shows a
single **GCP Network** row, and the property panel offers all 32 operations as a searchable dropdown.

Underneath, each operation is its own node type, because two other things the specification asks for are
per-node-type in this engine and cannot be expressed any other way:

- **Risk levels.** `destructive` is a flag on a node type and the approval policy reads it
  (`ToolApprovalPolicy`). A single node type for all 32 operations would force one answer for both *List VPCs*
  and *Delete VPC* — either every read needs approval, or no deletion does.
- **Per-operation AI tools.** `PluginAIToolAdapter` publishes one tool per node type. Collapsing to one would
  give the agent a single `gcp-network` tool with the union of every field, instead of the
  `gcp.subnet.create` / `gcp.firewall.delete` catalogue the specification lists.

The split is invisible to the author and load-bearing for safety. No plugin-specific Angular component exists or
is needed — the form comes from the schema each operation publishes.

---

## 6. SDK integration

Everything runs on interfaces that already existed. Nothing new was created.

| Concern | Existing thing used |
|---|---|
| Node contract | `WorkflowNodePlugin`, `NodeDefinition`, `NodeExecutionContext`, `NodeExecutionResult` |
| Configuration form | `SchemaBuilder` (including `advanced`, `describeOptions`, `withDefault`) |
| HTTP | `PluginHttpClient` — host allow-list enforced by the engine |
| Credentials | `PluginContext.secrets()` — the audited, log-redacting scoped secret provider |
| Audit | `PluginContext.dataStore()` |
| Logging | `PluginContext.logger()` |
| Variables | Resolved by the engine before the plugin sees the configuration |
| Authorization | The engine's own permission checks; `PluginNodeExecutor` is still the only bridge |
| AI | `supportsAI` / `destructive` on the node definition |

There is **no** new authentication system, authorization system, plugin registry, plugin server, workflow
engine or AI Agent.

### Credentials

The workflow stores the **name** of a secret, never a key:

```json
"connection": "gcp.prod.network"
```

The service-account JSON is resolved at execution through `PluginContext.secrets().require(...)` and exchanged
for a short-lived access token at Google's token endpoint. The private key, the token and the service-account
email never enter the workflow document, its variables, node output, the logs, or the AI Agent's context — two
tests assert exactly that (`keyNeverReachesOutput`, `keyNeverReachesCompute`).

---

## 7. Validation

### CIDR — before anything is sent

`CidrValidator` runs locally, so a typo produces a precise message instead of a generic GCP
`INVALID_ARGUMENT` after a round trip.

- **Host bits set.** `10.0.0.5/24` is rejected, and the message names `10.0.0.0/24` as what was probably meant.
  GCP silently treats it as the whole network, which is how this mistake survives to production.
- **Prefix range.** A subnet must be `/29`–`/8`. Smaller than `/29` GCP cannot allocate; larger than `/8` is
  almost always a typo.
- **Malformed input.** `10.0.0.0`, `/33`, `10.0.0.300/24`, `not-a-cidr` — all rejected as `GCP_INVALID_CIDR`.
- **Match ranges are looser.** `0.0.0.0/0` and `10.0.0.1/32` are legitimate firewall sources and route
  destinations, and are correctly refused as *subnet* ranges.
- **Secondary ranges** are validated too, and the error names the offending range.
- **IPv6** is passed through to GCP rather than half-validated here.

### Firewall exposure

`FirewallExposure` assesses every rule before it is written, against 10 administrative ports — SSH, RDP,
Telnet, MySQL, PostgreSQL, MongoDB, Redis, and so on.

A finding is raised only when *all three* hold: direction is `INGRESS`, action is `ALLOW`, and the source
includes `0.0.0.0/0`. So an egress rule, a deny rule, or a rule sourced from `10.0.0.0/8` is not flagged, and
neither is `tcp:443` — a web server is what a web server is for.

It handles the cases that hide exposure:

- **Ranges.** `1-65535` is expanded, so SSH inside it is found.
- **No ports.** A `tcp` entry with no ports means *every* port, and is reported as `all`.
- **Protocol `all`** from the internet is flagged.
- **Every port, not just the first.** `22,3389,5432` produces three findings.

When there are findings, the operation **refuses** unless `confirmed` is true, and the finding text says which
service and why. When it does proceed, the findings travel in the node's output as `securityFindings` and
`exposesAdministrativeAccess` — never silently blocked, and never silently allowed.

### Dependencies

Deleting a VPC lists **every** blocker at once — subnets, firewall rules, custom routes, routers and peerings —
rather than one per failed run. Compute's own `default-route-*` entries are excluded, since they disappear with
the network and treating them as dependents would make deletion impossible.

**Dependents are never deleted for you.** The error says so explicitly.

### Two confirmation gates

Risky operations carry `requireConfirmation` (default `true`) and `confirmed`. This is deliberately separate
from the node's `destructive` flag:

- `destructive` makes a **supervised AI Agent** seek approval.
- `confirmed` applies to **any** caller, so a hand-built workflow cannot delete a VPC because a variable
  resolved to something unexpected.

Either gate alone has a bypass. Wire `confirmed` to an upstream `APPROVAL` node — see
`examples/audit-and-teardown.json`.

---

## 8. Permission mapping

Each operation publishes a permission name (the tables in §4). These appear in the manifest's
`permissions_declared` and in each node's description, so an administrator can see what a workflow will need.

**Enforcement stays with the engine and with GCP IAM.** This plugin does not implement an authorization system
— the specification forbids creating one, and there would be no way to make a second one authoritative anyway.
The service account's IAM roles are the real boundary; when a role is missing, GCP says which permission it
wanted and the plugin surfaces that message verbatim.

Suggested IAM: **Compute Network Admin** (`roles/compute.networkAdmin`) for the mutating operations,
**Compute Network Viewer** (`roles/compute.networkViewer`) for a read-only connection. Firewall operations
additionally need **Compute Security Admin** (`roles/compute.securityAdmin`).

---

## 9. Error handling

| Code | Cause | Retryable |
|---|---|---|
| `GCP_INVALID_CIDR` | A CIDR failed local validation | no |
| `GCP_INVALID_ARGUMENT` | A configuration value is wrong or missing | no |
| `GCP_CONFIRMATION_REQUIRED` | A destructive operation, or a rule exposing an administrative port, without `confirmed` | no |
| `GCP_NETWORK_HAS_DEPENDENCIES` | Something still references the resource | no |
| `GCP_RESOURCE_NOT_FOUND` | HTTP 404 | no |
| `GCP_RESOURCE_ALREADY_EXISTS` | HTTP 409, or a duplicate NAT name | no |
| `GCP_PERMISSION_DENIED` | HTTP 403 — message names the missing IAM permission | no |
| `GCP_AUTHENTICATION_FAILED` | The service-account key or token exchange failed | no |
| `GCP_QUOTA_EXCEEDED` | HTTP 429 | **yes** |
| `GCP_API_UNAVAILABLE` | HTTP 5xx | **yes** |
| `GCP_OPERATION_TIMEOUT` | A long-running operation did not finish in time | **yes** |
| `GCP_OPERATION_FAILED` | The operation completed with an error | no |

Google's own message is extracted from `error.errors[0].message` and preserved, because it is usually the
useful part — it names the exact IAM permission, or the exact conflicting resource.

**A failed long-running operation is reported as a failure.** "Accepted" is not "exists", and a workflow's next
node depends on the difference.

---

## 10. Test cases

51 tests, all passing.

```
Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
```

`GcpNetworkPluginTest` (25) drives the plugin end-to-end over a scripted Compute API — no GCP account, no
network:

| Area | Covered |
|---|---|
| Plugin loading & metadata | all 32 nodes published, one category, `supportsAI` |
| Operation discovery | node types resolve; unknown types fail cleanly |
| Risk & idempotency flags | `destructive` and `idempotent` per operation |
| Create / Get / List / Delete VPC | including custom-mode default and long-running operation polling |
| Failed GCP operation | reported as failure, not success |
| Dependency validation | all blockers named at once; default routes excluded; clean VPC deletes |
| Confirmation gate | refused *before* any call is made |
| Create subnet | secondary ranges, private Google access |
| CIDR validation | rejected locally with zero HTTP requests |
| Firewall security warning | public SSH refused; confirmed proceeds with the finding in the output; web rule needs nothing |
| Create NAT | written into the router's `nats[]`; duplicate refused; `MANUAL_ONLY` needs addresses |
| Network inspection | counts across every resource type; partial result when one type is forbidden |
| GCP API errors | 404 / 403 / 429 → codes and retryability |
| Credential safety | key absent from output and from every non-token request |

`ValidationTest` (26) covers CIDR validation and firewall exposure directly, including the cases that hide
exposure (port ranges, empty ports, protocol `all`).

```bash
mvn -o -pl plugins/gcp-network-plugin test
```

---

## 11. Build

Maven runs **offline** in this environment; the `-o` flag is required.

```bash
mvn -o -pl plugins/gcp-network-plugin clean package
```

Full reactor (all 17 modules):

```bash
mvn -o -DskipTests package
```

---

## 12. JAR

```
plugins/gcp-network-plugin/target/orchpilot-gcp-network-plugin-1.0.0.jar
```

The SDK is `provided`, so the JAR contains only this plugin's classes and its `META-INF/workflow-plugin.json`.

---

## 13. Installation

1. Build the JAR (§11).
2. Store the service-account key as a secret whose name begins with `gcp.` — for example `gcp.prod.network`.
   The value is the **full service-account JSON**. It is stored by the engine's secret store; nothing about it
   goes into any workflow.
3. Upload the JAR through **Settings → Plugins → Upload**, declaring version `1.0.0`.
4. The palette gains one **GCP Network** row under the GCP category.

Grant the service account `roles/compute.networkAdmin` (and `roles/compute.securityAdmin` for firewall work),
or `roles/compute.networkViewer` for a read-only connection.

---

## 14. Example workflow JSON

- [`examples/provision-vpc.json`](examples/provision-vpc.json) — custom-mode VPC → app and data subnets (with
  GKE secondary ranges) → internal-only firewall rule → Cloud Router → Cloud NAT → inspection. Shows
  `outputMapping` feeding the next node, and a firewall rule that needs no confirmation because it exposes
  nothing.
- [`examples/audit-and-teardown.json`](examples/audit-and-teardown.json) — list firewall rules → inspect →
  **human approval** → delete VPC, with `confirmed` wired to the approval's result. Shows both gates working
  together.

---

## 15. AI Agent usage

The agent sees one tool per operation, named by capability, with the operation's schema as its parameters and
its `destructive` flag deciding whether a supervised run needs approval.

**Investigating** — reads, so no approval:

> *"Why can't the instances in `app-subnet` reach the internet?"*
>
> The agent calls `gcp.network.inspect` on the VPC, sees `counts.nats = 0` alongside a subnet with no external
> addresses, and reports that there is no Cloud NAT — rather than guessing.

**Auditing** — still reads:

> *"Which firewall rules in `prod-vpc` are open to the internet?"*
>
> `gcp.firewall.list` returns the rules with their exposure assessed, so the agent can name the rule, the
> service and the port.

**Changing something** — destructive, so a supervised agent stops for approval:

> *"Delete the sandbox VPC."*
>
> `gcp.network.delete` is `CRITICAL`. `ToolApprovalPolicy` holds it for approval. Even once approved, the node
> still refuses unless `confirmed` is set, and refuses again if anything still references the network — listing
> every blocker at once.

**What the agent never gets:** the service-account key, the access token, or a way to reach a resource its
connection's IAM roles do not already allow. The credential is resolved inside the node, after the agent's
decision, and never enters the agent's context.

---

## Known limitations

- **No Google Cloud SDK** (§2) — REST instead, by necessity in this environment.
- **Permission names are declared, not enforced by this plugin** (§8) — GCP IAM and the engine are the
  boundary.
- **Terraform is not used**, per the specification. Every operation is a direct API call.
- **IPv6 ranges** are passed to GCP rather than validated locally beyond basic shape.
- **Shared VPC** (host/service project attachment) is not covered by these 32 operations.
