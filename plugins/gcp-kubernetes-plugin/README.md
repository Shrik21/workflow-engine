# GCP Kubernetes Plugin

GKE cluster management **and** Kubernetes workload operations as OrchPilot nodes — behind a provider abstraction
so AWS EKS and Azure AKS can be added later without touching the workflow engine.

- **Plugin id:** `orchpilot-gcp-kubernetes` · **Version:** `1.0.0` · **Category:** `CLOUD_INFRASTRUCTURE`
- **45 nodes**, one per operation · **Java 17**, no Google SDK — REST over the engine's plugin HTTP client
- Reuses the **existing** AI Agent, Plugin Server, Plugin Registry, JWT security and Workflow Engine. Nothing here
  is a second copy of any of them.

## 1. Read this first: what works, and where the boundary is

Two of these are hard platform boundaries, not backlog items. Knowing them up front will save you an incident.

| | Status |
|---|---|
| **GKE cluster & node-pool management** | ✅ Works unconditionally — `container.googleapis.com` is publicly trusted |
| **Kubernetes workloads** (deploy, scale, logs, manifests…) | ✅ Works **when the cluster has a DNS-based control plane endpoint** — see §2 |
| **Pod exec / shell** | ❌ Impossible in this runtime — see §9 |
| **Port-forward, watch streams** | ❌ Same reason as exec; the rollout wait polls instead |
| **Reading Kubernetes Secret values** | ❌ Refused by design — see §8 |

## 2. The cluster endpoint constraint

A GKE cluster's API server presents a certificate signed by **that cluster's own CA** — which is exactly why the
GKE API hands back `masterAuth.clusterCaCertificate`. Trusting it means installing that CA into the client's trust
material, and OrchPilot's plugin HTTP client is a **shared, engine-owned JDK client with no per-plugin TLS hook**.
That is deliberate isolation: a plugin that could swap in its own trust manager could weaken TLS for the whole
engine.

So this plugin connects the way that *is* verifiable — GKE's **DNS-based control plane endpoint**
(`gke-<hash>.<region>.gke.goog`), which Google fronts with a publicly-trusted certificate.

**Enable it once per cluster** (Console → cluster → *Control plane networking* → **DNS-based endpoint**, or):

```bash
gcloud container clusters update prod --location us-central1 --enable-dns-access
```

Without it, workload nodes fail immediately and legibly:

> `K8S_ENDPOINT_NOT_TRUSTED: Cluster 'prod' has no DNS-based control plane endpoint… GKE cluster and node-pool
> operations are unaffected and continue to work.`

An explicit failure, not a twenty-second TLS handshake timeout. `apiServerUrl` overrides discovery for a cluster
fronted by a gateway with its own publicly-trusted certificate; it must be `https://`.

## 3. The provider abstraction

The seam falls where the clouds actually differ — authentication and cluster lifecycle — and nowhere else:

```
KubernetesProvider           ← vendor-specific: credentials + endpoint discovery
   ├─ connect()  → KubernetesApiClient   ← concrete, vendor-NEUTRAL. Zero Google code.
   └─ admin()    → ClusterAdmin          ← interface: clusters, node pools

GkeKubernetesProvider  ✅ shipped
EksKubernetesProvider  ⬜ later — reuses all 37 Kubernetes nodes unchanged
AksKubernetesProvider  ⬜ later
```

`KubernetesApiClient` is a **class, not an interface**, because once you know the endpoint and the bearer token
there is genuinely nothing vendor-specific left to vary. Adding EKS is one provider plus one `ClusterAdmin` — the
engine, the AI Agent, the registry and the security model are untouched.

Resource kinds are a **table**, not a class per kind ([`K8sResource`](src/main/java/com/orchpilot/workflow/plugins/gcp/kubernetes/model/K8sResource.java)):
the Kubernetes API is uniform, so one generic client serves Deployments, Pods, Jobs, Ingresses and the rest.
Adding CronJobs was one line.

## 4. Installation

```bash
mvn -o -pl plugins/gcp-kubernetes-plugin -am package -DskipTests
```

Upload `orchpilot-gcp-kubernetes-plugin-1.0.0.jar`, then grant:

- **Secret scopes:** `gke.`
- **Allowed hosts:** `container.googleapis.com`, `oauth2.googleapis.com`, `*.gke.goog`

> Permissions are **not** taken from the manifest automatically — pass them at upload, or:
> ```bash
> curl -X PUT ".../api/plugins/orchpilot-gcp-kubernetes/permissions?version=1.0.0" \
>   -H "Content-Type: application/json" \
>   -d '{"allowedHosts":["container.googleapis.com","oauth2.googleapis.com","*.gke.goog"],"secretScopes":["gke."],"eventsEnabled":true}'
> ```

The JAR bundles `snakeyaml` in `lib/` (same pattern as the email plugin), so the engine gains no new dependency.

## 5. Credentials

Store the **service-account JSON key** as a secret named with the `gke.` prefix, e.g. `gke.prod`. Nodes reference
it **by name**; the value is read at execution, audited, and redacted from logs.

**IAM** — grant the service account on the project:

| Doing | Role |
|---|---|
| Reading clusters / node pools | `roles/container.clusterViewer` |
| Managing clusters, node pools | `roles/container.admin` |
| Workload operations inside a cluster | `roles/container.developer`, **plus** an RBAC binding in the cluster |

RBAC is enforced by the cluster **independently** of IAM. Bind the service account to a Role or ClusterRole:

```bash
kubectl create clusterrolebinding orchpilot \
  --clusterrole=edit --user=orchpilot@my-project.iam.gserviceaccount.com
```

Prefer a namespace-scoped `RoleBinding` in production — the plugin cannot exceed what RBAC grants, so this is your
real blast-radius control.

**A credential never enters** node configuration, node output, workflow variables, logs, audit records, error
messages, or the AI Agent's context. Two tests assert exactly that, including that the private key never appears in
any outbound request except the token exchange.

## 6. Risk levels and the two gates

`HIGH` and `VERY_HIGH` both map to the node's `destructive` flag, so your **existing** policy/approval engine gates
them.

| Risk | Operations |
|---|---|
| `READ_ONLY` | list/get everything, pod logs, cluster & deployment health, events, validate manifest |
| `LOW` | scale deployment / statefulset, restart deployment |
| `MEDIUM` | create namespace/deployment, update image, rollback, apply manifest, scale node pool |
| `HIGH` | **delete** pod, deployment, service, configmap, secret, job, namespace, node pool, manifest |
| `VERY_HIGH` | **delete GKE cluster** |

Destructive operations pass **two independent gates**:

1. **`destructive`** — a supervised AI Agent must have it approved.
2. **`confirmed`** — a per-node flag that applies to *any* caller, agent or not.

Two gates because either alone has a bypass: a hand-built workflow isn't an agent, and an approved agent shouldn't
delete a namespace because a variable resolved to something unexpected.

```json
{ "requireConfirmation": true, "confirmed": "${approval.granted}" }
```

## 7. AI Agent usage

Every node sets `supportsAI = true` and carries its capability id, so the **existing** agent discovers them through
the **existing** Plugin Registry — no new agent, no redeployment.

The path is always:

```
AI → capability → permission → policy → Kubernetes API
```

never `AI → unrestricted kubectl → cluster`. What the agent sees is a fixed catalogue of typed capabilities. There
is no shell, no kubectl passthrough, and no raw request builder — the agent picks a tool and supplies parameters,
the engine authorises, and only then does the plugin resolve a credential **the agent never sees**.

> *"storefront is failing in prod"* → agent calls `k8s_deployment_health`, then `k8s_list_pods`, then
> `k8s_pod_logs` with `previous=true`, and reports `CrashLoopBackOff` with the stack trace — read-only throughout.
> Rolling back is `MEDIUM`; deleting anything needs approval.

## 8. Kubernetes Secrets

**List Kubernetes Secrets returns names, types and key names. Never values. There is no Get Secret node.**

That is not a configuration option. A Secret's values are credentials, and this plugin's contract is that
credentials do not reach workflow variables, logs, or the model. A test asserts the base64 values never appear in
output.

## 9. Pod exec — why it is refused, not missing

Exec requires a **SPDY/WebSocket protocol upgrade**; the engine's plugin transport is request/response only —
deliberately, because a plugin that can open arbitrary streams from inside the engine is a very different security
proposition.

The node exists so the capability is **visible and audited as refused** rather than silently absent:

> `K8S_EXEC_NOT_SUPPORTED: Executing commands inside a pod is not available… no OrchPilot workflow or AI Agent can
> obtain a shell in a cluster through this plugin.`

It is refused **before** the confirmation gate and before any credential is resolved — confirming it would not
help. For diagnosis use **Get Pod Logs**, **List Events** and **Get Pod**; to run something, apply a Job manifest.

## 10. Manifests

YAML or JSON, single or multi-document (`---`). Parsing untrusted YAML is hardened three ways, because a manifest
can arrive from an AI Agent or a form field:

- **`SafeConstructor`** — `!!java.net.URL`-style type tags cannot instantiate classes (the YAML RCE primitive).
- **Aliases disabled, code points limited** — closes the "billion laughs" expansion.
- **Kind allow-list** — a `ClusterRoleBinding` is *rejected*, not forwarded. The plugin is not a generic conduit
  to the cluster API.

Each of those has a test.

**Validate** is structural and needs no cluster — you can run it before a cluster exists. Add `serverDryRun` to
also have the API server check it. Note the node **succeeds** while reporting `valid: false`; the node did its job,
and the workflow branches on `valid`.

**Apply** is create-or-update: it creates, and on the `409` that means "exists" it re-reads the live
`resourceVersion` and replaces. A stale-version rejection is optimistic concurrency working, not something to route
around.

## 11. Rollouts and rollback

`waitForRollout` polls the Deployment's status, because *"the patch was accepted"* is not *"the new version is
serving"* — a nonexistent image is accepted instantly and then fails in `ImagePullBackOff`. Healthy means ready ==
updated == available == desired; any one of those alone is misleading mid-deploy.

**A rollout that times out fails the node** with the replica counts, so a workflow can branch onto a rollback:

> `K8S_ROLLOUT_TIMEOUT: Deployment 'web' did not become healthy… (0/1 replicas ready). Check pod events and logs —
> an ImagePullBackOff or a failing readiness probe is the usual cause.`

**Rollback** reconstructs what `kubectl rollout undo` does — Kubernetes removed the `rollback` subresource, so the
plugin finds the Deployment's ReplicaSets by selector, takes the newest whose pod template differs from the live
one, and patches that template back.

Scale, image update and restart are all **strategic-merge patches**, not read-modify-write: a patch races with
nothing, and merging `containers` by name is what stops an image update wiping out a sidecar.

## 12. Long-running GKE operations

Creating or deleting a cluster takes minutes, so those nodes **return the operation id rather than blocking a
worker thread** for ten minutes. Poll with a **Get GKE Cluster** node, or just carry on.

## 13. Error codes

`K8S_AUTHENTICATION_FAILED` · `K8S_PERMISSION_DENIED` · `K8S_NOT_FOUND` · `K8S_INVALID_REQUEST` · `K8S_CONFLICT` ·
`K8S_RATE_LIMITED` *(retryable)* · `K8S_UNAVAILABLE` *(retryable)* · `K8S_MISCONFIGURED` ·
`K8S_CONFIRMATION_REQUIRED` · `K8S_ENDPOINT_NOT_TRUSTED` · `K8S_EXEC_NOT_SUPPORTED` · `K8S_ROLLOUT_TIMEOUT` ·
`K8S_INVALID_MANIFEST` · `K8S_ROLLBACK_UNAVAILABLE` · `K8S_CANCELLED`

GKE's `error.message` and Kubernetes' `Status.message` are folded into the same codes, so a Decision node reads
`errorCode == 'K8S_NOT_FOUND'` without caring which API answered.

## 14. Example workflow

[`examples/ai-deploy-and-heal.json`](examples/ai-deploy-and-heal.json) — check cluster health → roll out an image →
on failure, gather pods, logs and warning events → AI Agent diagnoses → roll back or escalate.

```
GKE_CLUSTER_HEALTH → DECISION
        ↓ healthy
K8S_UPDATE_DEPLOYMENT_IMAGE (waits for rollout)
        ↓
DECISION on rolloutStatus
   ├── ROLLED_OUT → done
   └── failed → K8S_LIST_PODS → K8S_POD_LOGS → K8S_LIST_EVENTS
                    ↓
                AI AGENT (diagnose)
                    ↓
             DECISION on shouldRollback
                ├── K8S_ROLLBACK_DEPLOYMENT
                └── escalate
```

## 15. Troubleshooting

| Symptom | Cause |
|---|---|
| `may not read secret …` | Secret scope `gke.` not granted at install — see §4. |
| `K8S_ENDPOINT_NOT_TRUSTED` | The cluster has no DNS endpoint. Enable it — see §2. |
| `K8S_PERMISSION_DENIED` on a workload op, but cluster ops work | IAM is fine, **RBAC is not**. Bind the service account inside the cluster — see §5. |
| `K8S_CONFIRMATION_REQUIRED` | Destructive node without `confirmed: true` — that is the second gate doing its job. |
| `K8S_ROLLOUT_TIMEOUT` | The new pods never became ready. List events and read the previous container's logs. |
| Manifest rejected with "does not manage" | The kind is not on the allow-list — deliberate; see §10. |
| Secret values missing from output | By design, always — see §8. |
| Pod logs truncated | `tailLines` is clamped to 2000 so the result fits in a workflow variable. |

## 16. Tests

40 tests, no GCP account and no network — a scripted HTTP client drives token exchange, cluster lookup and the
Kubernetes call, and a **real RSA key** is generated per run so credential parsing and RS256 JWT signing are
genuinely exercised.

`ManifestConsistencyTest` cross-checks `workflow-plugin.json` against the operation enum on every node type,
capability and risk flag — because nothing at compile time ties them together, and a delete published as
non-destructive would silently skip the approval gate.

```bash
mvn -o -pl plugins/gcp-kubernetes-plugin test
```
