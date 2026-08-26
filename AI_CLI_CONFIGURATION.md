# AI CLI Configuration

Configuring AI command-line tools — the Claude CLI today — that the OrchPilot runtime may run for
troubleshooting, infrastructure assistance and AI-powered error analysis.

---

## What was reused rather than rebuilt

The specification asked for an `AiCliProvider` abstraction behind an "AI Provider Manager". **That architecture
already existed.** `AIModelProvider` + `AIProviderFactory` ([AIProviderFactory.java][factory]) already resolve
12 provider adapters that register themselves simply by being Spring beans, and adding one is a class plus a
constant with no engine change. Building a parallel hierarchy would have been exactly the duplication the
rules forbid.

So this feature adds what genuinely did not exist — process execution, path validation, OS detection, output
parsing — behind a small `AiCliProvider` seam, and reuses everything else:

| Concern | Existing thing used |
|---|---|
| Authentication / authorization | JWT + `@PreAuthorize` + `Permission` enum |
| Tenant isolation | `AuthPrincipal.getTenantId()` |
| Audit | `AuditService` |
| Secrets & encryption | `SecretService` (unused by Claude CLI — it authenticates itself) |
| Execution history | `PluginExecutionRecord` — already carries every context field the analysis needs |
| AI model providers | `AIModelProvider`, `AIProviderFactory`, `AIProviderConnection` — untouched |
| Workflow engine, plugin engine, AI Agent | Untouched |

No new authentication, authorization, workflow, plugin or AI Agent functionality was created.

[factory]: workflow-engine-core/src/main/java/com/orchpilot/workflow/ai/AIProviderFactory.java

---

## The two gates

Pointing the engine at an executable is a stronger capability than configuring an HTTP endpoint. If the
permission model were ever misconfigured, a UI-only switch would be the difference between a wrong API call and
arbitrary code running as the engine's user. So there are two independent gates:

### 1. Host-level master switch — **an addition to the specification**

```yaml
workflow:
  engine:
    ai:
      cli:
        enabled: true          # default: false
        timeout-seconds: 120
        max-output-bytes: 524288
        allowed-directories: []   # empty = any path that passes validation
```

Set by whoever controls the engine's configuration file. **Cannot be changed from the UI, by design.** Turning
it off stops execution everywhere immediately, whatever is stored in MongoDB. The settings page says so
explicitly rather than showing a form whose every button fails.

`allowed-directories` narrows it further for a hardened deployment: a path outside every entry is refused no
matter who configures it.

### 2. RBAC

| Permission | Grants |
|---|---|
| `AI_CLI_VIEW` | See configurations, status and detected version |
| `AI_CLI_CREATE` | Add a configuration; run auto-detection |
| `AI_CLI_UPDATE` | Change a configuration, including its path |
| `AI_CLI_DELETE` | Remove a configuration |
| `AI_CLI_EXECUTE` | Test connection, detect version, send a prompt |
| `AI_ERROR_ANALYSIS` | Ask the AI to explain a failed node |

`ADMIN` holds all of these automatically (`EnumSet.allOf`). **No other role was granted them** — silently
widening an existing role's security reach is an operator's decision, not a migration's. Grant them explicitly
to `WORKFLOW_ADMIN` or a custom role if you want that.

Note that `AI_CLI_EXECUTE` is split from create/update deliberately: an operator may be trusted to point the
engine at an already-vetted binary without also being able to drive it, and an auditor should be able to
confirm what is configured while running nothing at all.

---

## Secure process execution

`SecureProcessRunner` is the **only** place in the codebase that creates a process.

- **No shell, ever.** `ProcessBuilder` receives a pre-split argument list, so the OS executes exactly that
  program with exactly those arguments. There is no string for a shell to reinterpret. `Runtime.exec(userInput)`
  is not used and must not be introduced.
- **Arguments are engine-built.** No endpoint accepts a command or an argument list. A user-supplied prompt
  travels on **stdin**, never as an argument — so an error message beginning with a dash cannot become a flag.
- **Arguments are still checked.** A null byte or line break in an argument is refused. This should be
  unreachable; if it ever fires, a caller has started building arguments from input and that is where it is
  caught.
- **Both streams drained concurrently.** Not an optimisation — a process that fills its stderr pipe while the
  parent reads only stdout deadlocks until the timeout.
- **Bounded output and time.** Capped bytes; destroyed on timeout, forcibly if it ignores the first request.
- **Minimal environment.** An allowlist, not the inherited environment. A CLI needs enough to find its own
  stored login; it does not need the engine's database URI, JWT signing key, or any cloud credential that
  happens to be in the engine's environment.

### Path validation

`ExecutablePathValidator` runs on **every write and again immediately before every execution** — checking once
at write time would be a time-of-check/time-of-use gap, since the allowlist could narrow or the file be replaced
in between.

| Rule | Why |
|---|---|
| No shell metacharacters | Defence in depth — the runner's no-shell guarantee should not be the only thing standing |
| Absolute paths only | A relative path resolves against a working directory the configurer cannot see |
| No `..` | Traversal would make the allowed-directory check meaningless |
| No UNC / `\\server\share` | Whoever controls the share would control what the engine runs |
| Windows extension allowlist | `.cmd`, `.exe`, `.bat`, `.ps1` |
| Known program names | Directories vary per machine; the *program* should not be a surprise |

Shape validation works on the **string** using the *target* OS's rules, not `java.nio.Path`. A configuration may
legitimately be prepared for a host the engine is not running on, and `Paths.get` applies the host's semantics —
a Windows path parsed on a Linux JVM comes back as a relative single-segment name.

---

## Auto-detection

`CliDetector` searches **PATH first**, then common install locations, and reports every candidate rather than
picking one.

- No installation directory is assumed. npm's global prefix moves, distributions disagree about `/usr/bin` vs
  `/usr/local/bin`, and per-user installs land under the home directory.
- PATH is walked **in-process** rather than by running `which` — spawning a shell utility to find out where a
  program is would be a second process on a code path whose whole purpose is caution about spawning processes.
- Windows tries `.cmd` first (an npm-installed CLI is a `.cmd` shim), then `.exe`, `.bat`, bare.
- Detection **executes nothing**. Confirming a path works is Test Connection, which is a separate permission.
- Candidates are **offered, never applied silently** — which one is right is the operator's call.

---

## Platform support

**Windows 10/11.** `.cmd`, `.exe`, `.bat`, and `.ps1` where explicitly configured. Searches `%APPDATA%\npm`,
`%LOCALAPPDATA%\npm`, `%ProgramFiles%\nodejs` and the user profile. Never assumes `/usr/bin/claude`.

**Ubuntu 20.04+ / 22.04+ / 24.04+ and other Linux.** PATH resolution, then `/usr/local/bin`, `/usr/bin`, `/bin`,
`/opt/homebrew/bin`, `/snap/bin`, `~/.local/bin`, `~/.npm-global/bin`, `~/node_modules/.bin`, `~/.bun/bin` — as
candidates, not as an assumption.

**Docker.** The design works inside a container, and the UI says plainly: *the Claude CLI must be installed
inside the OrchPilot runtime container*. A host path is not reachable from within, and the engine does not try —
`AI_CLI_NOT_FOUND` names the container case explicitly, because it is what people lose the most time to.

A configuration targeting a different OS than the host can be **saved** but not **executed**
(`AI_CLI_OS_MISMATCH`), so an operator can prepare an Ubuntu configuration from a Windows workstation.

---

## Error analysis

```
Workflow node → GCP plugin → GCP API → permission error
                                            ↓
                              engine records PluginExecutionRecord
                                            ↓
                          user clicks "Analyse with Claude"  ← always user-initiated
                                            ↓
              curated context → scrubbed → engine-built prompt → Claude CLI (stdin)
                                            ↓
                        response parsed → IAM claims checked → recommendation
```

### What leaves the engine

Only the named fields on `ErrorAnalysisContext` — a record, not a map, so widening it is a visible, reviewable
change rather than "just put the execution record in". **Never** the node's configuration, its inputs or
outputs, the plugin's request or response bodies, or any secret.

The error message additionally passes through `SensitiveTextScrubber`, which masks PEM blocks, bearer tokens,
JWTs, Google/AWS/GitHub/Slack/vendor key shapes, named credential fields and inline URI credentials. This is
pattern-based because the text came from *another* system — there is no known value for `SecretRedactor` to
match. It is a second line; the first is the curated context. Anything it catches is recorded in the audit trail
as `sensitiveTextRemoved: true`, because it means something upstream leaked a credential into a message.

### No invented IAM permissions

Two halves, and only one of them is enforcement:

1. **The prompt** instructs the model to answer only from the given error and to return `null` rather than
   guess. A prompt is a request, not a guarantee.
2. **`GcpIamKnowledge`** checks every claim. It is a curated table of the permissions the plugins in this
   repository can actually raise, checked in so a reviewer can see exactly what the engine will vouch for.

The parser **never repairs a claim, never substitutes its own role, and never drops a warning to make the answer
look cleaner.** `ErrorAnalysis.verified` is false and `warnings` explains why when:

- the permission is not shaped like `service.resource.verb`
- the permission is not in the reference (reported as *unconfirmed*, not *wrong* — GCP has thousands, the table
  has dozens, and a correct answer outside it is entirely possible)
- the role is `roles/owner`, `roles/editor` or similar
- the reference does not link that role to that permission — the message names the ones it does

The table encodes distinctions a plausible-sounding answer gets wrong, e.g. firewall permissions need
`roles/compute.securityAdmin`, **not** `roles/compute.networkAdmin`.

The UI renders `verified` prominently. A view that showed the recommendation without it would present a guess as
a fact — the last place that guarantee can be thrown away.

### No blind permission granting

`AiErrorAnalysisService` **changes nothing**. It does not grant a permission, retry a node, alter a workflow, or
influence whether the engine considers the execution failed. Every analysis endpoint is read-only by
construction. The AI may analyse, explain, recommend and validate; actual IAM modification is not on this path.

---

## REST API

| Method | Path | Permission |
|---|---|---|
| `GET` | `/api/ai/cli/status` | `AI_CLI_VIEW` |
| `GET` | `/api/ai/cli` | `AI_CLI_VIEW` |
| `GET` | `/api/ai/cli/{id}` | `AI_CLI_VIEW` |
| `POST` | `/api/ai/cli` | `AI_CLI_CREATE` |
| `PUT` | `/api/ai/cli/{id}` | `AI_CLI_UPDATE` |
| `DELETE` | `/api/ai/cli/{id}` | `AI_CLI_DELETE` |
| `POST` | `/api/ai/cli/{id}/test` | `AI_CLI_EXECUTE` |
| `GET` | `/api/ai/cli/{id}/version` | `AI_CLI_EXECUTE` |
| `GET` | `/api/ai/cli/detect` | `AI_CLI_CREATE` |
| `POST` | `/api/ai/analysis/executions/{executionId}/nodes/{nodeId}` | `AI_ERROR_ANALYSIS` |
| `POST` | `/api/ai/analysis` | `AI_ERROR_ANALYSIS` |
| `GET` | `/api/ai/analysis/iam/permissions/{permission}` | `AI_ERROR_ANALYSIS` |

Test connection returns, per the specification:

```json
{ "success": true, "version": "1.0.60 (Claude Code)", "path": "…", "operatingSystem": "WINDOWS" }
```

Note the IAM lookup endpoint runs **no AI at all** — when an error already names the missing permission, as
GCP's messages usually do, it answers "which role contains it" directly.

---

## Storage

`aiCliConfigurations` in MongoDB, tenant-indexed:

```json
{
  "name": "Claude CLI - Windows Development",
  "type": "CLAUDE_CLI",
  "enabled": true,
  "defaultConfiguration": true,
  "operatingSystem": "WINDOWS",
  "executablePath": "C:\\Users\\dev\\AppData\\Roaming\\npm\\claude.cmd",
  "status": "CONNECTED",
  "version": "1.0.60 (Claude Code)",
  "tenantId": "acme"
}
```

**No credential of any kind.** The Claude CLI holds its own login for the account the engine runs as, so the
engine neither needs nor wants a copy. If a CLI ever requires a key it is referenced through `secretName` and
resolved via the existing encrypted secret store — the value never lands in this document, an API response, or
an audit record.

`status` and `version` are a **cache of the last check**, not authority — so the settings list renders without
spawning a process per row. Changing the path clears them, because a `CONNECTED` status describing the old
binary would claim a check that never happened.

Multiple configurations are supported. At most one default per tenant, enforced in the service rather than by a
unique index so the promote/demote swap is atomic.

Every read re-checks the tenant after loading by id, and "belongs to someone else" returns the same error as
"does not exist" so an id cannot be probed. A configuration names a path on the engine host; leaking one
discloses how that host is laid out.

---

## UI

```
Settings
  └── AI Configuration              /settings/ai
        ├── Claude CLI              /settings/ai/claude-cli   ← fully implemented
        ├── OpenAI CLI                                        ← listed, no adapter
        ├── Gemini CLI                                        ← listed, no adapter
        └── Ollama CLI                                        ← listed, no adapter
```

A navbar entry, **AI Configuration**, is added under `AI_CLI_VIEW`.

The unavailable providers are **listed rather than hidden**: an operator who cannot see them assumes the
platform is Claude-only and plans around that. Showing the extension point is the honest signal, and it keeps
the page from needing a redesign when the second provider lands.

`ErrorAnalysisPanel` renders a failed node's error with an optional AI explanation beneath. The engine's own
error code and message appear **immediately and unconditionally**; the AI section is additive and, if analysis
fails, the original error is still there. Replacing a real error with an AI summary would trade a fact for a
paraphrase.

Retry emits an event the host page performs through the existing execution API — the original instance resumes,
and no duplicate is created.

---

## Tests

**118 new backend tests**, engine core at 661 total (from 543), full 17-module reactor green.
Angular: 188/189 — the single failure is the pre-existing `FormNodeConfig` spec, unrelated to this work.

| Suite | Tests | Covers |
|---|---|---|
| `ExecutablePathValidatorTest` | 30 | Shell syntax, traversal, UNC, relative paths, wrong OS, unknown programs, allowlist boundaries, TOCTOU re-validation |
| `SecureProcessRunnerTest` | 7 | Real process execution, non-zero exits, enforced timeout, output cap, argument rejection, environment filtering |
| `SensitiveTextScrubberTest` | 10 | PEM keys, tokens, JWTs, vendor keys, named fields, URI credentials; leaves ordinary errors untouched |
| `AnalysisResponseParserTest` | 14 | Fenced JSON, braces in strings, invented permissions, over-broad roles, mismatched roles, risk normalisation |
| `GcpIamKnowledgeTest` | 19 | Least-privilege ordering, the firewall/securityAdmin distinction, unknown ≠ wrong |
| `AiCliConfigurationServiceTest` | 12 | Cross-tenant refusal, null-tenant deployments, single-default rule, status invalidation, audit contents |
| `ClaudeCliProviderTest` | 10 | Version and response parsing, JSON envelopes, error envelopes, truncation |
| `AiErrorAnalysisServiceTest` | 9 | The worked GCP example end to end, credential scrubbing, request bodies not sent, unavailable CLI, audit |
| `CliDetectorTest` | 7 | PATH discovery, deduplication, no invented paths |

---

## Known limitations

- **The CLI must be installed on the engine host** (or inside its container). By design — executing a host
  binary from inside a container is not attempted.
- **The IAM reference is curated, not the live IAM API.** The live API would be authoritative but needs a
  credential, a network call and a permission of its own on every analysis. Unknown permissions are reported as
  unverified rather than rejected.
- **Only `ClaudeCliProvider` is implemented.** The other three types are declared and listed; each needs one
  bean.
- **`AI_ERROR_ANALYSIS` indirectly causes CLI execution**, with an engine-built prompt rather than user text.
  Grant it accordingly.
- **IAM remediation is not implemented.** The specification's *Apply Recommended Fix* flow would require
  calling GCP's `setIamPolicy`, which no plugin in this repository does today — see below.

---

## What was deliberately not built

**The optional IAM remediation flow.** The specification describes an *Apply Recommended Fix* button gated on
permission, group membership, policy, confirmation and audit.

The gating is straightforward and matches patterns already here. The action is not: applying it means calling
`resourcemanager.projects.setIamPolicy`, and **no plugin in this repository can do that** — the GCP Network
plugin covers networking, not IAM. Building an IAM-mutation path as part of a *configuration* feature would put
the single most privileged operation in the estate somewhere nobody would look for it.

What is in place is everything up to that line: the recommendation, its validation, the risk level, the
verified/unverified distinction, and the audit trail. The remaining piece is a GCP IAM plugin operation with its
own confirmation gate and `destructive` flag, which is a separate change and a separate review.

Say if you want that built, and whether it belongs in the existing GCP Network plugin or a new `gcp-iam` one.
