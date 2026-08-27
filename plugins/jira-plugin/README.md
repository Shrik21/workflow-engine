# Jira Plugin

Jira as an execution capability for OrchPilot workflows and your existing AI Agent — issues, JQL search,
transitions, comments, worklogs, attachments, sprints, boards, versions and components.

- **Plugin id:** `orchpilot-jira` · **Version:** `1.0.0` · **Category:** `PROJECT_MANAGEMENT`
- **Jira Cloud** and **Jira Server / Data Center**
- **Java 17**, no Jira SDK — REST over the engine's plugin HTTP client

## 1. Design

**39 nodes, one per operation.** A single "Jira Operation" node with an operation dropdown carries one risk
flag, so the AI Agent could not distinguish a JQL search from a delete and the risk table below could not be
enforced. Per-operation nodes cost **zero** hand-written UI: the designer renders each node from its declared
schema, and each node shows only its own fields — which is what "the form changes with the operation" means here.

**Risk drives approval.** `HIGH` maps to the node's `destructive` flag, so your existing policy/approval engine
gates it:

| Risk | Operations |
|---|---|
| `READ_ONLY` | search, get issue, list projects/comments/users/statuses/transitions, boards, sprints |
| `LOW` | add comment, add worklog, attach text file |
| `MEDIUM` | create/update/assign/transition/clone issue, sprint and version management |
| `HIGH` | **delete issue, delete comment, delete component** |

## 2. Cloud vs Server — handled for you

Three real divergences, absorbed by `JiraClient` so no workflow has to care:

| | Cloud | Server / DC |
|---|---|---|
| REST base | `/rest/api/3` | `/rest/api/2` |
| Rich text | **Atlassian Document Format** (structured JSON) | plain string |
| Auth | Basic `email:apiToken` | Bearer personal access token |
| User identity | `accountId` | `name` |

The ADF one matters most: sending a plain string description to Cloud fails with an opaque `400`. You write
plain text in the node; it is converted per deployment, one paragraph per line, and converted *back* to a string
when read — so `${description}` behaves the same on both.

## 3. Installation

```bash
mvn -o -pl plugins/jira-plugin -am install -DskipTests
```

Upload `orchpilot-jira-plugin-1.0.0.jar` via the Plugin Server, then grant:

- **Secret scopes:** `jira.`
- **Allowed hosts:** `*.atlassian.net` (plus your own host for Server/DC)

> Permissions are not taken from the manifest automatically — pass them at upload, or:
> ```bash
> curl -X PUT ".../api/plugins/orchpilot-jira/permissions?version=1.0.0" \
>   -H "Content-Type: application/json" \
>   -d '{"allowedHosts":["*.atlassian.net"],"secretScopes":["jira."],"eventsEnabled":true}'
> ```

## 4. Credentials

Store one secret named with the `jira.` prefix. Nodes reference it **by name**; the value is read at execution,
audited, and redacted from logs.

- **Cloud** — `email:apiToken` (create at *Atlassian account → Security → API tokens*)
- **Server / DC** — the personal access token alone

**A credential never enters** node configuration, node output, workflow variables, logs, error messages, or the
AI Agent's context. Two tests assert exactly that.

## 5. JQL search

```json
{
  "baseUrl": "https://company.atlassian.net",
  "deployment": "CLOUD",
  "credentialsSecret": "jira.prod",
  "jql": "project = ENG AND issuetype = Bug AND priority = High AND assignee = currentUser()",
  "maxResults": 50
}
```

Output is flattened for branching — no JSON digging in a Decision node:

```json
{
  "total": 12,
  "count": 12,
  "issues": [
    { "issueKey": "ENG-123", "summary": "Login fails", "status": "Open",
      "priority": "High", "assignee": "Vivek",
      "issueUrl": "https://company.atlassian.net/browse/ENG-123" }
  ]
}
```

## 6. Create issue from a Form

```
FORM  →  ${title}, ${description}, ${priority}
   ↓
JIRA_CREATE_ISSUE
   summary     = ${title}
   description = ${description}
   priority    = ${priority}
   ↓
outputs: issueKey = ENG-123, issueId = 10001, issueUrl = …
```

Every field accepts `${…}` from a Form node, a previous node's output, or an AI Agent result.

## 7. Transitions

Give the **name** ("In Progress"), not an id. Transition ids are per-workflow and unguessable, so the plugin
resolves the name against what the issue can do *right now*. If it can't, the error names what was available:

> `JIRA_TRANSITION_NOT_AVAILABLE: Issue ENG-5 cannot transition to 'Done' from its current status. Available now: [To Do, In Progress].`

Use **List Jira Transitions** to discover them dynamically.

## 8. AI Agent usage

Every node sets `supportsAI = true` and carries its capability id in its description, so your **existing** agent
discovers them through your **existing** Plugin Registry — no new agent, no redeployment.

> *"Show me all high-priority bugs assigned to me"* → agent generates
> `project = ENG AND issuetype = Bug AND priority = High AND assignee = currentUser()` → calls
> `jira_search_issues`.

Deletes are `destructive`, so a supervised agent must have them approved. **The agent never receives the API
token** — it selects a tool; the plugin resolves the secret at execution.

## 9. AI bug-fixing flow

This plugin is the Jira half of the flagship loop — the other half is the GitHub plugin already in this repo:

```
JIRA_SEARCH_ISSUES / JIRA_GET_ISSUE     ← find the bug
        ↓
AI AGENT  (analyse description)
        ↓
GITHUB_CREATE_BRANCH → GITHUB_PUT_FILE → GITHUB_CREATE_PULL_REQUEST
        ↓
GITHUB_DISPATCH_WORKFLOW → GITHUB_GET_WORKFLOW_RUN     ← CI
        ↓
DECISION on conclusion
   ├── success → JIRA_ADD_COMMENT + JIRA_TRANSITION_ISSUE ("Done")
   └── failure → AI AGENT (analyse) → GITHUB_PUT_FILE → retry
```

See [`examples/ai-bug-fix-workflow.json`](examples/ai-bug-fix-workflow.json).

## 10. Attachments — the one limit

**Text attachments work; binary does not.** The plugin HTTP client carries `String` bodies, so a log, a report or
JSON attaches fine (a multipart envelope is assembled for it), but an image or PDF cannot survive being carried
as a Java string. For binary, attach from a CI step instead. This is the same platform constraint that keeps
untrusted plugins safe in-process — not an omission.

## 11. Error codes

`JIRA_AUTHENTICATION_FAILED` · `JIRA_PERMISSION_DENIED` · `JIRA_NOT_FOUND` · `JIRA_INVALID_REQUEST` ·
`JIRA_CONFLICT` · `JIRA_TRANSITION_NOT_AVAILABLE` · `JIRA_RATE_LIMITED` *(retryable)* · `JIRA_UNAVAILABLE`
*(retryable)* · `JIRA_MISCONFIGURED`

Jira reports problems in two shapes — top-level `errorMessages` and per-field `errors` — and the per-field one
carries the useful text ("customfield_10010 is required"). Both are extracted, so a 400 tells you which field is
wrong instead of just "400".

## 12. Troubleshooting

| Symptom | Cause |
|---|---|
| `may not read secret …` | Secret scope `jira.` not granted at install — see §3. |
| `must be stored as 'email:apiToken'` | Cloud needs both halves; a bare token is the usual mistake. |
| `400` mentioning `description` | Almost always ADF — set `deployment` correctly; the plugin converts. |
| `JIRA_TRANSITION_NOT_AVAILABLE` | The issue's current status has no such transition; the message lists the real ones. |
| Assignee silently not set | Cloud needs an `accountId`, not an email — resolve it with **Search Jira Users** first. |
| `JIRA_NOT_FOUND` on a real issue | The account cannot see that project — Jira returns 404 rather than 403 for invisible issues. |
