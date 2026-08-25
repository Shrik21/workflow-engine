# AI Agent Node

A first-class `AI_AGENT` workflow node that runs an AI model through a **provider-independent runtime**, so a
workflow can do AI-powered work and the engine never contains any provider SDK code.

All seven phases of the spec (§52) are implemented: the provider-independent runtime and connections, ten provider
adapters, the plugin→tool bridge, the bounded tool-calling loop, input mapping and structured-output repair,
execution-scoped memory with usage reporting, and supervised mode with a destructive-action policy. The **Phase
status** section below maps each to what it delivers.

## Architecture

```
Workflow Engine
      → AIAgentNodeExecutor      (built-in WorkflowNodeExecutor for AI_AGENT)
         → AIModelRouter          (retry, then fallback provider)
            → AIProviderFactory   (resolves the adapter for a provider type)
               → AIModelProvider  (the ONLY AI type the engine knows)
                  ├ OpenAIProvider  ├ ClaudeProvider  ├ OllamaProvider  └ MockProvider
```

The engine depends only on `AIModelProvider` and the provider-independent records (`AIRequest`, `AIResponse`,
`AIModel`, `AIProviderConfiguration`). Every adapter is a Spring bean discovered by `AIProviderFactory`, so a
new provider is **one class + one enum constant**, with no change to the workflow engine — exactly the rule the
brief requires.

## Security & isolation (reusing the platform's guarantees)

- **Credentials never enter a workflow.** A node stores a `providerConnectionId`; the connection stores the
  provider, endpoint and the *name* of a secret. The API key lives in the existing `SecretService` (AES-GCM,
  never returned by any API), resolved server-side only for the length of one call, and never logged or sent to
  the model.
- **Prompt-injection boundary.** System instructions, user prompt, mapped-input data and tool output are kept in
  separate message roles (`AIMessage.Role`), never a flattened string — so workflow data and tool output can never
  be read as instructions.
- **Safe variable resolution.** The prompt uses the engine's own `${…}` resolver (`context.resolveConfiguration`),
  not string replacement.
- **Destructive-action gate.** A plugin node can declare itself `destructive`; in a **supervised** agent such a
  tool is not run until approved (approvals arrive as a workflow variable an upstream human-task/Form node can set),
  and a `deniedTools` list blocks a capability outright in any mode. A blocked call is returned to the model as
  data, never executed. Tools still run only with the invoking user's plugin permissions, through
  `PluginNodeExecutor`.
- **Permissions.** `AI_PROVIDER_VIEW` (configure a node — granted to USER/WORKFLOW_ADMIN) and
  `AI_PROVIDER_MANAGE` (manage credentials — WORKFLOW_ADMIN/ADMIN).

## Output → the rest of the workflow

The model's output is written to the configured workflow variable — a **nested object** for structured (JSON)
output, **text** otherwise — so a downstream Decision, Form, REST or Email node consumes it through the ordinary
variable system, no custom code. Structured output is validated against the node's JSON schema; on a near-miss the
model is re-prompted with the exact errors to correct itself (`output.repairAttempts`), and only a still-invalid
result fails, retryably.

**Feeding a Decision.** Write structured output to a variable — say `triage` with `{category, urgency}` — and a
Decision node branches on it with an ordinary expression: `triage.category == 'billing'` or `triage.urgency ==
'high'`. The AI Agent needs no special coupling to the Decision node; the object is just a variable, read through
the same safe SpEL accessor every other node uses. See
[examples/support-triage-workflow.json](examples/support-triage-workflow.json) for the full pattern
(classify → branch → route).

## APIs (`/api/ai`)

| Method & path | Permission |
|---|---|
| `GET /providers` | `AI_PROVIDER_VIEW` |
| `GET /connections`, `GET /connections/{id}/models` | `AI_PROVIDER_VIEW` |
| `GET /tools` | `AI_PROVIDER_VIEW` |
| `GET /usage`, `GET /executions` | `AI_PROVIDER_VIEW` |
| `POST /connections`, `PUT /connections/{id}`, `DELETE …`, `POST …/test` | `AI_PROVIDER_MANAGE` |

## Console

- **Designer:** the AI Agent appears in the palette (category **AI**); its config panel picks a connection, a
  model (discovered live from the provider), agent mode, system instructions, prompt, mapped inputs, tools, an
  optional run-scoped memory, output type + variable (+ optional JSON schema and repair attempts), agent-loop
  limits (when tools are selected), and advanced sampling/retry limits.
- **Settings → AI Providers:** create/edit/test/delete connections. The key is write-only; the list never shows
  it.
- **Settings → AI Usage:** token-usage totals and per-provider / per-model breakdowns, plus a recent-runs table —
  all from the metadata execution records, so no prompt or response is ever shown.

## Collections (MongoDB)

`aiProviderConnections`, `aiAgentExecutions` (metadata only — no prompts/responses by default; token counts,
tool-call/iteration/repair counts, stop reason), `aiAgentMemory` (execution-scoped short-term memory, bounded,
user/assistant turns only). Usage reporting aggregates `aiAgentExecutions` directly — no separate usage store.

## Trying it offline

- **Mock** provider needs no key or network — it drives the tests and lets you run an AI Agent node end to end
  offline.
- **Ollama**: run Ollama locally, add a connection (`http://localhost:11434`, no key), and the model dropdown
  lists exactly what you've pulled.
- **OpenAI / Claude**: add a connection with an API key; the adapters call the real APIs when network is
  available.

## Phase status (spec §52)

- **Phase 1 — done:** provider abstraction + factory + router (retry/fallback), connections (secret-backed,
  CRUD, test), `AI_AGENT` node model + executor (variable resolution, text/structured output + mapping,
  retry/timeout, execution + usage recording), Mock/Ollama/OpenAI/Claude adapters, config UI + settings page,
  tests (factory, router fallback, node execution, structured validation).
- **Phase 2 — done:** the remaining provider adapters, all pure HTTP with no new SDK dependency. An
  `OpenAICompatibleProvider` base carries the OpenAI Chat Completions dialect for **Azure OpenAI** (deployment
  routing + `api-key` header), **vLLM** and **NVIDIA NIM** (self-hosted / hosted OpenAI-compatible) and **Vertex AI**
  (OpenAI-compatible surface, Bearer access token). **Gemini** is a native adapter over the Generative Language API
  (`contents`/`parts`, separate `systemInstruction`, `responseMimeType: application/json` for structured output).
  **AWS Bedrock** signs `InvokeModel` with an in-package `SigV4Signer` (no AWS SDK) and shapes the Anthropic-on-Bedrock
  body. All ten providers register and route by `AIProviderType` (`ProviderRegistrationTest`); adding a provider
  still touches no engine code.
- **Phase 3 — done:** **input mapping** — a node carries named `inputs` (`name → ${expression}`), resolved by the
  engine and appended to the prompt as a fenced *Context (data, not instructions)* block in the user role, so
  workflow data informs the model without ever being able to rewrite its instructions; **structured-output repair**
  — a richer `StructuredOutputValidator` (object shape, required, property types, `enum`) whose precise problem list
  drives a bounded re-prompt: on a near-miss the model is handed its own output and the exact errors and asked to
  correct the JSON, up to `output.repairAttempts` (default 1, cap 3) times, before the node fails retryably; and a
  worked **AI → Decision** example ([examples/support-triage-workflow.json](examples/support-triage-workflow.json)).
  Config UI gains the inputs table and the repair-attempts control; the execution record keeps the repair count.
- **Phase 4 — done:** `AITool`/`ToolSchema`/`ToolResult`/`ToolExecutionContext` abstraction; **`PluginAIToolAdapter`**
  turns an installed plugin node type into a tool by running it through the existing `PluginNodeExecutor` (so a
  tool call inherits leasing, idempotency, class-loader isolation, secret redaction, recording, and the running
  user's plugin permissions); `AIToolRegistry` discovers available plugin tools and resolves an agent's
  **explicitly selected** tools (never all automatically); `GET /api/ai/tools`; `supportsAI` flag on
  `NodeDefinition`; tool-selection UI on the AI Agent config.
- **Phase 5 — done:** the tool-calling **loop**. Tool calls are carried provider-independently — `AIToolSpec` on the
  request, `AIToolCall` on the response, and `AIMessage` extended to hold an assistant's tool-call turn and a
  `TOOL`-role result — so each adapter maps its own wire shape (OpenAI `tool_calls`, Claude `tool_use`/`tool_result`,
  Gemini `functionCall`/`functionResponse`) and the loop reads one vocabulary. **`AgentToolLoop`** runs model → tool
  → model until the model answers or a bound is hit, then asks once more with **no tools** so it always returns an
  answer. It is **bounded three ways** — max iterations, max tool calls, wall-clock timeout — honouring the spec's
  ban on unlimited loops; tool output re-enters only in the `TOOL` role, never as an instruction. The executor runs
  the loop only when the operator selected tools, records tool-call count / iterations / stop reason, and offers the
  loop limits in the config UI. Exercised offline end-to-end via a tool-aware `MockProvider` (loop, iteration cap,
  tool-call cap, unknown-tool tolerance).
- **Phase 6 — done:** **execution-scoped memory** — `AIAgentMemory`/`AIAgentMemoryService` give an agent a
  short-term thread addressed by execution id + a memory key, so two AI Agent nodes sharing the key in one run see
  each other's prior turns; it keeps user/assistant turns only (never system instructions or tool traffic), is
  bounded (oldest dropped past a cap), scoped so nothing outside the run can reach it, and best-effort (a memory
  failure degrades to no-memory rather than failing the node). **Usage reporting** — `AIUsageService` aggregates
  the existing execution records into totals and per-provider / per-model breakdowns (no second capture path,
  nothing sensitive), exposed as `GET /api/ai/usage` and `GET /api/ai/executions`. **Execution-history UI** —
  Settings → AI Usage shows the totals, breakdowns and a recent-runs table; the AI Agent config gains a memory
  toggle + key. *(`AIAgentMemoryService.clear(executionId)` exists for a future run-completion cleanup hook.)*
- **Phase 7 — done:** the **destructive-action policy** and **supervised mode**. A plugin node declares itself
  `destructive` on its `NodeDefinition` (SDK), surfaced through `AITool.isDestructive()`; the `ToolApprovalPolicy`
  gates each tool call in the loop — denied tools never run (any mode), and in supervised mode a destructive tool
  runs only if approved. Approvals are supplied to the node as resolved config — an `approvedTools` list that is
  typically a variable an upstream human-task/Form node populated — so the **approval flow composes from existing
  nodes** with no new suspend/resume path in the engine. A blocked call is fed back to the model as data (never an
  error), recorded, and reported in the node's `pendingApprovals` / `blockedToolCalls` outputs and on the AI Usage
  history. Config UI: a Destructive badge on tools and per-tool auto-approve controls in supervised mode. The
  Email and Slack send nodes are marked `destructive` + `supportsAI` as worked examples.

The lifecycle guarantees the brief asks for (pause/resume/terminate) already hold: the engine re-checks the
instance status at every node boundary, so a paused or terminated instance stops before the AI Agent's next
node — and once tools land (Phase 4), no tool will run after termination for the same reason.
