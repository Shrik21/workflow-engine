# Docker Registry Plugin

One capability model over **Docker Hub, AWS ECR, Azure ACR, Google Artifact Registry** and any
**OCI-compatible** registry (Harbor, GHCR, GitLab, Nexus, Artifactory, plain `registry:2`).

- **Plugin id:** `orchpilot-docker-registry` · **Version:** `1.0.0` · **Category:** `CONTAINER_REGISTRY`
- **Java 17**, no cloud SDKs — pure HTTP over the engine's plugin client.

## 1. Architecture

```
RegistryPlugin  (14 nodes — one per operation, each with a Provider dropdown)
      │
      └─ RegistryProviderFactory   ← resolves credentials from the secret store
             │
             └─ ContainerRegistryProvider  (interface)
                     │
                     └─ AbstractRegistryProvider   ← the Docker Registry v2 data plane,
                            │                        shared by ALL five providers
                            ├─ DockerHubRegistryProvider          (+ hub.docker.com for list/search)
                            ├─ AwsEcrRegistryProvider             (SigV4 → GetAuthorizationToken)
                            ├─ AzureAcrRegistryProvider           (AAD → ACR token exchange)
                            ├─ GoogleArtifactRegistryProvider     (service-account JWT → OAuth2)
                            └─ GenericDockerRegistryProvider      (basic / bearer)
```

**Why this abstraction is real and not a facade:** all five registries speak the *identical* `/v2/` API for
catalogues, tags, manifests and digests. Only authentication and repository-management endpoints differ. So the
shared base class genuinely carries the data plane, and a provider is that plus an auth dance — which is why
`GenericDockerRegistryProvider` needs barely 40 lines.

**Adding a sixth registry** is one `ContainerRegistryProvider` implementation plus one enum constant. No change
to the node catalogue, the dispatch path, or anything the AI Agent sees.

## 2. Capabilities

| Capability | Node | Risk |
|---|---|---|
| `container.registry.login` | Registry Login / Test Connection | READ_ONLY |
| `container.registry.listRepositories` | List Registry Repositories | READ_ONLY |
| `container.registry.listTags` | List Image Tags | READ_ONLY |
| `container.registry.listImages` | List Images | READ_ONLY |
| `container.registry.getImage` | Get Image Metadata | READ_ONLY |
| `container.registry.getManifest` | Get Image Manifest | READ_ONLY |
| `container.registry.getDigest` | Get Image Digest | READ_ONLY |
| `container.registry.exists` | Check Image Exists | READ_ONLY |
| `container.registry.search` | Search Images | READ_ONLY |
| `container.registry.createRepository` | Create Registry Repository | MEDIUM |
| `container.registry.retag` | Retag Image | MEDIUM |
| `container.registry.copyTag` | Promote Image Within Registry | MEDIUM |
| `container.registry.deleteImage` | Delete Image / Tag | **HIGH** |
| `container.registry.deleteRepository` | Delete Registry Repository | **HIGH** |

`HIGH` maps to the node's `destructive` flag, so a **supervised** AI Agent must have those approved before it
runs them, through the platform's existing approval policy. Reads run freely.

### Not supported, and why

**`pushImage` / `pullImage` are absent by design.** A plugin's HTTP client carries `String` request and response
bodies under a size ceiling — the same isolation that makes third-party JARs safe to run in-process. Image layers
are multi-hundred-megabyte binary blobs and cannot pass through it.

What replaces them:
- **Pushing**: do it in CI (a GitHub Actions run this platform can dispatch via the GitHub plugin), then use
  **Get Digest** here to verify what landed.
- **Promotion**: use **Promote Image Within Registry**, which re-points a manifest inside one registry. No layer
  moves, it is near-instant, and it **verifies the digest is unchanged** — a stronger guarantee than a
  pull-and-push, which can silently produce different content.

## 3. Installation

```bash
mvn -o -pl plugins/docker-registry-plugin -am install -DskipTests
```

Upload `orchpilot-docker-registry-plugin-1.0.0.jar` through the Plugin Server, then grant its permissions:

- **Secret scopes:** `registry.`
- **Allowed hosts:** `registry-1.docker.io`, `hub.docker.com`, `auth.docker.io`, `*.amazonaws.com`,
  `*.azurecr.io`, `login.microsoftonline.com`, `*.pkg.dev`, `artifactregistry.googleapis.com`,
  `oauth2.googleapis.com` — plus your own host for a private registry.

> Permissions are **not** applied automatically from the manifest. Pass them at upload, or afterwards:
> ```bash
> curl -X PUT ".../api/plugins/orchpilot-docker-registry/permissions?version=1.0.0" \
>   -H "Content-Type: application/json" \
>   -d '{"allowedHosts":["registry-1.docker.io","auth.docker.io","hub.docker.com"],"secretScopes":["registry."],"eventsEnabled":true}'
> ```

## 4. Credentials

Store one secret per registry, named with the `registry.` prefix. A node references the **name**; the value is
read at execution, audited, and redacted from logs. **No credential ever enters node configuration, output,
workflow variables, logs, or the AI Agent's context.**

| Provider | Secret value | Node fields |
|---|---|---|
| **Docker Hub** | `username:access-token` | — |
| **AWS ECR** | `accessKeyId:secretAccessKey` | `accountId`, `region` |
| **Azure ACR** (service principal) | `clientId:clientSecret` | `registryName`, `tenantId`, `clientId` |
| **Azure ACR** (admin user) | `adminUser:password` | `registryName` |
| **Google Artifact Registry** | the service-account **JSON key** | `projectId`, `location`, `repositoryScope` |
| **Generic** | `username:password` (or set `bearerToken`) | `registryUrl` |

## 5. Configuration

Every node shares a provider dropdown and the credential reference; the rest is per-operation. Example
**Get Digest** against ECR:

```json
{
  "provider": "AWS_ECR",
  "credentialsSecret": "registry.ecr.prod",
  "accountId": "123456789012",
  "region": "us-east-1",
  "image": "myapp:1.4.0"
}
```

Same operation on Docker Hub — only the connection block changes:

```json
{
  "provider": "DOCKER_HUB",
  "credentialsSecret": "registry.dockerhub",
  "image": "library/nginx:1.27"
}
```

### Output

```json
{
  "success": true,
  "provider": "AWS_ECR",
  "operation": "GET_DIGEST",
  "repository": "myapp",
  "tag": "1.4.0",
  "digest": "sha256:abc…"
}
```

Failures carry `errorCode`, `message` and `retryable`, normalised across providers so a Decision node can branch
on `errorCode == 'IMAGE_NOT_FOUND'` regardless of which registry answered.

## 6. Error codes

`AUTHENTICATION_FAILED` · `AUTHORIZATION_FAILED` · `NOT_FOUND` · `CONFLICT` · `INVALID_REQUEST` ·
`INVALID_IMAGE` · `RATE_LIMITED` *(retryable)* · `REGISTRY_UNAVAILABLE` *(retryable)* · `NETWORK_ERROR`
*(retryable)* · `OPERATION_NOT_SUPPORTED` · `DIGEST_MISMATCH`

Only genuinely transient failures are marked retryable — a bad credential or a denied permission fails
identically on a retry, so the engine is told not to bother.

## 7. Workflow example — verified promotion

```
Get Digest (dev/myapp:1.5)
      ↓
Promote Image Within Registry  →  prod/myapp:1.5
      ↓
Get Digest (prod/myapp:1.5)
      ↓
Decision:  devDigest == prodDigest  →  Deploy
                                   →  else: Alert
```

Promotion already refuses to proceed if the digest would change (`DIGEST_MISMATCH`); the explicit re-check makes
that guarantee visible in the workflow itself.

## 8. AI Agent usage

Every node sets `supportsAI = true`, so the existing agent discovers them through the existing Plugin Registry —
no agent redeployment, no new agent. Each carries its capability id in its description, so a request like
*"list the images in my ECR repository"* resolves to `container.registry.listImages`.

Deletes are `destructive`, so in supervised mode the agent must have them approved. **The agent never receives a
credential** — it selects a tool, and the plugin resolves the secret at execution.

## 9. Security

- Credentials live only in the secret store, referenced by name.
- Tokens obtained during auth (ECR authorization tokens, ACR refresh tokens, Google access tokens) are held in
  provider fields for one execution and never persisted, logged, or output.
- Error messages carry the registry's own text but never the credential — asserted by tests.
- HTTP is confined to the allow-listed hosts by the engine.
- Every operation writes a metadata-only audit record (operation, capability, risk, provider, repository, user,
  outcome, duration) — never a secret.

## 10. Troubleshooting

| Symptom | Cause |
|---|---|
| `may not read secret …` | Secret scope `registry.` not granted at install — see §3. |
| `AUTHENTICATION_FAILED` on Docker Hub | Use an **access token**, not your account password. |
| `NOT_FOUND` listing repositories on Docker Hub | Docker Hub has no `/v2/_catalog`; a username is required. |
| `OPERATION_NOT_SUPPORTED` for create/search | That provider has no such API; ECR and Artifact Registry support create, Docker Hub supports search. |
| `DIGEST_MISMATCH` on promotion | The target resolved to different content — promotion refused rather than shipping the wrong image. |
| Delete appears to succeed but the tag remains | Most registries need `REGISTRY_STORAGE_DELETE_ENABLED=true`; deletion is by digest and removes every tag pointing at it. |
