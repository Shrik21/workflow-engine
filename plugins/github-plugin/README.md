# GitHub Plugin

GitHub integration for OrchPilot workflows and the OrchPilot AI Agent — repositories, branches, files, commits,
pull requests, issues, releases, GitHub Actions and search.

- **Plugin id:** `orchpilot-github`
- **Version:** `1.0.0`
- **Type:** node plugin (Java 17)
- **Category:** GitHub

## 1. Overview

Each GitHub operation is its own workflow node and AI tool, with a per-operation risk level. **No GitHub SDK
dependency** — the plugin calls the GitHub REST API through the engine's allow-listed plugin HTTP client, so no
GitHub code lives in the workflow engine. Works against **GitHub.com** and **GitHub Enterprise Server** (set the
API URL per node).

**36 operations** across:

| Area | Operations |
|---|---|
| Repositories | get, list, create, update, **delete***, fork |
| Branches | list, get, create, **delete*** |
| Files | get, create/update, **delete*** |
| Commits | list, get |
| Pull requests | create, get, list, update, **merge***, review, comment |
| Issues | create, get, list, update, comment |
| Releases | create, list |
| Actions | dispatch workflow, list runs, get run, cancel, re-run |
| Search | repositories, code |

`*` = **destructive** (delete repo/branch/file, merge PR) — a supervised AI Agent must have these approved.

## 2. Installation

The build produces a deployable JAR: `orchpilot-github-plugin-1.0.0.jar`.

```bash
mvn -o -pl plugins/github-plugin -am install -DskipTests
```

Upload it through the Plugin Server (`POST /api/plugins`). Grant the permissions the manifest declares:

- **Allowed hosts:** `api.github.com`, `github.com` (add your Enterprise host for GHES)
- **Secret scopes:** `github.`

> **Permissions must be granted at upload** — the manifest declares them, but the engine only applies what you
> pass. If a node fails with *"may not read secret … Declared secret scopes: none"*, grant them after the fact:
> ```bash
> curl -X PUT ".../api/plugins/orchpilot-github/permissions?version=1.0.0" \
>   -H "Content-Type: application/json" \
>   -d '{"allowedHosts":["api.github.com","github.com"],"secretScopes":["github."],"eventsEnabled":true}'
> ```

## 3. Authentication

Authentication uses a **Personal Access Token** (classic or fine-grained). Store it as an OrchPilot secret — never
in workflow configuration — and reference it by name.

1. Create a PAT at GitHub → **Settings → Developer settings → Personal access tokens**, with the scopes you need
   (see below).
2. In OrchPilot → **Settings → Secrets**, create a secret whose name starts with `github.` (e.g. `github.prod.token`)
   and paste the PAT as its value.
3. On each node, set **GitHub token secret name** to that secret name.

The token is fetched at execution, redacted from logs, and never enters the workflow definition, node output, or
the AI Agent.

> GitHub App authentication (JWT-signed installation tokens) is a natural follow-up using the same JDK-crypto
> pattern the GCP plugin uses; this release ships the PAT flow.

## 4. Required token scopes

| To use | Classic scope |
|---|---|
| Read repos/branches/commits/files/issues/PRs | `repo` (or `public_repo` for public only) |
| Create/delete repos | `repo`, `delete_repo` (delete) |
| GitHub Actions (dispatch/cancel/re-run) | `repo` + `workflow` |
| Read org repositories | `read:org` |

A missing scope surfaces as `GITHUB_PERMISSION_DENIED` with GitHub's own message — never the token.

## 5. Configuration

Common to every node: **credentialsSecret** (the `github.` secret name) and optional **githubApiUrl** (for GHES).
Most operations take **owner** + **repo**; every value accepts a `${variable}`.

Output: `success`, the full `result` object (or `items`/`count` for lists), and lifted top-level fields for easy
variable access — `id`, `number`, `name`, `fullName`, `htmlUrl`, `sha`, `state`, `defaultBranch`, `merged`,
`tagName`, `status`, `conclusion`.

## 6. Workflow example

See [`examples/branch-file-pr-workflow.json`](examples/branch-file-pr-workflow.json):

```
Start → Create Branch → Create/Update File → Create Pull Request → End
```

Each step feeds the next through ordinary variables (the new branch name flows into the file commit and the PR's
head branch).

## 7. AI Agent usage

Every node sets `supportsAI = true`, so an operator can select it as an agent tool (selection is always explicit).
Deletes and PR merges are `destructive`, so a **supervised** agent must have them approved; reads and ordinary
writes run freely. **The AI never receives the token** — it sees only each tool's input schema, and the plugin
resolves the PAT from the secret store at execution.

> User: *"Open an issue in octo/demo titled 'Flaky test' and label it bug."* → the agent calls
> `github_create_issue` with the owner, repo, title and labels; the plugin authenticates from the secret.

## 8. Security

- The token is stored only in the OrchPilot secret store, referenced by name; never in workflow JSON, variables,
  output, logs, or exposed to the AI.
- HTTP is confined to `api.github.com`/`github.com` (plus any GHES host you allow) by the engine's allow-list.
- Scopes/permissions are never bypassed — insufficient scope produces `GITHUB_PERMISSION_DENIED`.
- Rate limits are detected (403 with `X-RateLimit-Remaining: 0`, or 429) and reported as retryable.
- Every operation writes a metadata-only **audit** record (operation, owner/repo, user, timing, status) — never
  the token.

## 9. Troubleshooting

| Error code | Meaning / fix |
|---|---|
| `GITHUB_MISCONFIGURED` | A required field or the credentials secret is missing. |
| `GITHUB_AUTHENTICATION_FAILED` | The token is invalid or expired. |
| `GITHUB_PERMISSION_DENIED` | The token lacks the scope named in the message. |
| `GITHUB_NOT_FOUND` | No such resource, or the token can't see it (private repo without `repo` scope). |
| `GITHUB_VALIDATION_FAILED` | GitHub rejected the request (422) — e.g. branch already exists, invalid field. |
| `GITHUB_RATE_LIMITED` | Rate limit hit (retryable) — retry after it resets. |
