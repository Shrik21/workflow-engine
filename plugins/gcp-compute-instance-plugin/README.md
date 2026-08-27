# GCP Compute Instance Plugin

Create, inspect and manage **Google Cloud Compute Engine** VM instances from OrchPilot workflows and the OrchPilot
AI Agent.

- **Plugin id:** `gcp-compute-instance`
- **Version:** `1.0.0`
- **Type:** node plugin (Java 17)
- **Category:** GCP Compute (Cloud / GCP / Compute)

## 1. Overview

Each Compute Engine operation is its own workflow node, so the designer, the engine and the AI Agent can treat
them differently where it matters:

| Node type | AI tool | Risk |
|---|---|---|
| `GCP_COMPUTE_CREATE_INSTANCE` | `gcp_compute_create_instance` | MODIFY |
| `GCP_COMPUTE_GET_INSTANCE` | `gcp_compute_get_instance` | READ_ONLY |
| `GCP_COMPUTE_LIST_INSTANCES` | `gcp_compute_list_instances` | READ_ONLY |
| `GCP_COMPUTE_START_INSTANCE` | `gcp_compute_start_instance` | MODIFY |
| `GCP_COMPUTE_STOP_INSTANCE` | `gcp_compute_stop_instance` | MODIFY |
| `GCP_COMPUTE_RESTART_INSTANCE` | `gcp_compute_restart_instance` | MODIFY |
| `GCP_COMPUTE_RESET_INSTANCE` | `gcp_compute_reset_instance` | MODIFY |
| `GCP_COMPUTE_SUSPEND_INSTANCE` | `gcp_compute_suspend_instance` | MODIFY |
| `GCP_COMPUTE_RESUME_INSTANCE` | `gcp_compute_resume_instance` | MODIFY |
| `GCP_COMPUTE_DELETE_INSTANCE` | `gcp_compute_delete_instance` | **DESTRUCTIVE** |

**No Google Cloud SDK dependency.** The plugin talks to the Compute Engine **REST API** through the engine's
allow-listed plugin HTTP client, and authenticates by minting an OAuth2 token from a service-account key with a
JDK-signed JWT-bearer exchange. Nothing GCP-specific lives in the workflow engine.

## 2. Installation

The build produces a deployable JAR: `orchpilot-gcp-compute-instance-1.0.0.jar`.

```bash
mvn -o -pl plugins/gcp-compute-instance-plugin -am install -DskipTests
```

Upload it through the **Plugin Server** (or the engine's `POST /api/plugins`). At upload, grant the permissions the
plugin declares in its manifest:

- **Allowed hosts:** `compute.googleapis.com`, `oauth2.googleapis.com`
- **Secret scopes:** `gcp.`

The plugin is then installable, startable, stoppable, versioned, upgradable and removable through the Plugin Server
like any other plugin.

## 3. GCP authentication

Authentication uses a **service-account key** (JSON). Store the key as an OrchPilot **secret** — never in workflow
configuration — and reference it by name from each node.

1. In GCP, create a service account and a JSON key.
2. In OrchPilot → **Settings → Secrets**, create a secret whose name starts with `gcp.` (e.g. `gcp.prod.serviceAccount`)
   and paste the JSON key as its value.
3. On each GCP node, set **GCP credentials secret name** to that secret name.

The key is fetched at execution, redacted from logs, and never enters the workflow definition, the node output, or
the AI Agent. Access tokens are cached in memory only.

> Application Default Credentials / Workload Identity are a natural extension when the engine runs on GCP; this
> release ships the service-account-key flow, which works anywhere.

## 4. Required IAM permissions

Grant the service account the least privilege for the operations you use. The predefined
`roles/compute.instanceAdmin.v1` covers all of them; for a read-only agent, `roles/compute.viewer` is enough.

| Operation | IAM permission |
|---|---|
| Create | `compute.instances.create` (+ `compute.disks.create`, `compute.subnetworks.use`, `compute.instances.setServiceAccount` if used) |
| Get / List | `compute.instances.get` / `compute.instances.list` |
| Start / Stop / Reset / Suspend / Resume | `compute.instances.start` / `.stop` / `.reset` / `.suspend` / `.resume` |
| Delete | `compute.instances.delete` |

A missing permission surfaces as a clean `GCP_PERMISSION_DENIED` failure that includes Google's own message
(e.g. `compute.instances.create`), never the credentials.

## 5. Configuration

Common to every node:

- **credentialsSecret** — name of the secret holding the service-account JSON.
- **projectId** — GCP project id (supports `${gcpProjectId}`).
- **zone** — e.g. `asia-south1-a` (optional for List).

Create adds: `instanceName`, `machineType` (default `e2-medium`), boot image (`imageProject` + `imageFamily`
or `imageName`, or a full `image` path), `diskSizeGb` (30), `diskType` (`pd-balanced`), `network` (`default`),
`subnet`, `externalIp` (`EPHEMERAL` | `NONE` | `STATIC`), `startupScript`, `labels`, `tags`, `serviceAccount`,
`deletionProtection`, and `ifExists` (`FAIL` | `USE_EXISTING`).

Long-running operations add: `waitForCompletion` (default true), `timeoutSeconds` (600), `pollIntervalSeconds` (5).
When **waitForCompletion = No**, the node returns the `operationId` immediately without polling.

Every important input accepts a **workflow variable** (`${instanceName}`, `${gcpZone}`, `${machineType}`, …),
resolved by OrchPilot's own resolver — the plugin never sees a raw template.

### Output

```json
{
  "success": true,
  "operation": "CREATE",
  "projectId": "my-project",
  "zone": "asia-south1-a",
  "instanceId": "123456789",
  "instanceName": "orchpilot-vm",
  "status": "RUNNING",
  "selfLink": "https://www.googleapis.com/compute/v1/...",
  "operationId": "operation-..."
}
```

These become workflow variables (`instanceId`, `instanceName`, `status`, `operationId`, `selfLink`, …). Get also
publishes a full `instance` object; List publishes an `instances` array of `{name, zone, status, machineType}`.

## 6. Workflow example

See [`examples/create-vm-workflow.json`](examples/create-vm-workflow.json):

```
Start → Form → GCP Create Instance → Decision
                                       ├── RUNNING → Send Email
                                       └── FAILED  → Notify Slack
                                     → End
```

Because the create output is a normal variable, the Decision node branches on `status == 'RUNNING'` with no custom
code.

## 7. AI Agent usage

Every node sets `supportsAI = true`, so an operator can select it as an agent tool (selection is always explicit).
Delete is `destructive`, so a **supervised** AI Agent must have it approved (via the agent's approval policy or an
upstream human-task node) before it runs; Get and List are read-only.

> User: *"Create a medium Ubuntu VM in my dev project in asia-south1."*
>
> The agent calls `gcp_compute_create_instance` with the project, zone, machine type and Ubuntu image, waits for
> the operation, and returns the instance. **The AI never receives the GCP credentials** — it sees only the tool's
> input schema, and the plugin resolves the service-account key from the secret store at execution.

## 8. Security

- Credentials are stored only in the OrchPilot secret store, referenced by name; never in workflow JSON, variables,
  output, logs, or exposed to the AI.
- The service-account key material is never rendered to a string; access tokens are cached in memory and never
  persisted.
- HTTP is confined to `compute.googleapis.com` and `oauth2.googleapis.com` by the engine's allow-list.
- IAM is never bypassed — insufficient permissions produce `GCP_PERMISSION_DENIED`.
- Delete is gated by confirmation (`confirmed`) and by the AI Agent's destructive-action policy.
- Startup scripts and labels are validated; labels must satisfy GCP's format.
- Every operation writes a metadata-only **audit** record (operation, project, zone, instance, user, timing,
  status, operation id) — never a credential.

## 9. Troubleshooting

| Error code | Meaning / fix |
|---|---|
| `GCP_MISCONFIGURED` | A required field or the credentials secret is missing/invalid. |
| `GCP_AUTHENTICATION_FAILED` | The service-account key or token exchange failed — check the key and `token_uri`. |
| `GCP_PERMISSION_DENIED` | The service account lacks the IAM permission named in the message. |
| `GCP_INSTANCE_NOT_FOUND` | No such instance in that project/zone. |
| `GCP_INSTANCE_EXISTS` | Create with `ifExists=FAIL` found an existing instance; use `USE_EXISTING` to adopt it. |
| `GCP_QUOTA_EXCEEDED` | A quota/rate limit was hit (retryable). |
| `GCP_OPERATION_TIMEOUT` | The operation did not finish within `timeoutSeconds`; raise it or set `waitForCompletion=false`. |
| `GCP_CONFIRMATION_REQUIRED` | Delete needs `confirmed=true` (or `requireConfirmation=false`). |
