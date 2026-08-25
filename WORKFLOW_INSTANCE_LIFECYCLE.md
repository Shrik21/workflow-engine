# Workflow Instance Lifecycle — Pause, Resume, Terminate

Runtime control over a single **workflow instance** (a `WorkflowExecution`), separate from the workflow
template it runs. Pausing instance #123 of "Customer Onboarding" holds that instance and its tasks; every other
instance of the same workflow keeps running, and the workflow design is never touched.

## Instance statuses and transitions

```
NOT_STARTED (PENDING) ──▶ RUNNING ──▶ COMPLETED
                            │  ▲
                     Pause  │  │ Resume
                            ▼  │
                          PAUSED
                            │
        Terminate ─────────┴──────────▶ TERMINATED  (final — never RUNNING/PAUSED/COMPLETED again)
```

`TERMINATED` is a new terminal status, distinct from the engine's existing `CANCELLED` (drain policy, withdrawn
task): termination is a deliberate administrative end-of-life carrying an actor and a reason. A terminated
instance can never be resumed, restarted or continued, by any path.

An instance parked on a form is `WAITING`; one mid node-loop is `RUNNING`. Both can be paused, and resume sends
each back where it was — a form-parked instance returns to `WAITING` so its submission drives it onward, a
running one re-enters the engine.

## Task cascade

Changing an instance's state cascades **only to the tasks carrying that instance's id**, and **never to a
`COMPLETED` task**:

| Instance action | Active task (`OPEN`/`ASSIGNED`) | Paused task | Completed task |
|---|---|---|---|
| Pause | → `PAUSED` (remembers previous status) | — | untouched |
| Resume | — | → previous status | untouched |
| Terminate | → `TERMINATED` | → `TERMINATED` | untouched |

## Form rules, enforced on the server

A form's Submit is gated on the instance's live state, checked in `TaskCompletionService` before anything else
— not merely by a disabled button, so a stale or hostile client cannot submit anyway:

| Instance state | Save Draft | Submit |
|---|---|---|
| RUNNING / WAITING | ✅ | ✅ |
| PAUSED | ✅ | ❌ → **409** `WORKFLOW_INSTANCE_PAUSED` |
| TERMINATED | ✅ | ❌ → **409** `WORKFLOW_INSTANCE_TERMINATED` |

Save Draft stays available while paused or terminated so a person never loses form input to an administrative
action they did not cause. A draft never advances the workflow — no next node, decision, plugin, REST, email or
database operation runs.

## Concurrency

Every transition flips the instance status with a single conditional MongoDB write that succeeds only from a
legal source status (`ExecutionStateStore.transitionStatus`), then cascades to the tasks. That status write is
the arbitration point:

- **Submit racing Terminate** — if the terminate write lands first, the submit sees `TERMINATED` and is refused;
  if the submit lands first, the task is `COMPLETED` and terminate leaves it alone. A terminated task can never
  become completed.
- **Double pause / double terminate** — idempotent; the second call is a no-op.
- The engine re-reads the persisted status at **every node boundary**, so a paused or terminated instance stops
  before its *next* node. A node already mid-execution is never force-killed: it finishes, and the loop stops
  after it. The continuation point is durable, so resume picks up exactly where it left off.

## API

| Method & path | Permission | Body |
|---|---|---|
| `POST /api/workflow-instances/{id}/pause` | `WORKFLOW_INSTANCE_PAUSE` | `{ "reason": "…" }` (optional) |
| `POST /api/workflow-instances/{id}/resume` | `WORKFLOW_INSTANCE_RESUME` | — |
| `POST /api/workflow-instances/{id}/terminate` | `WORKFLOW_INSTANCE_TERMINATE` | `{ "reason": "…" }` |
| `GET /api/workflow-instances/{id}/status` | `EXECUTION_VIEW` | — |
| `GET /api/workflow-instances/{id}/history` | `EXECUTION_VIEW` | — |

Every action is audited (`INSTANCE_PAUSED/RESUMED/TERMINATED`, `TASK_PAUSED/RESUMED/TERMINATED`,
`FORM_SUBMIT_REJECTED`) and recorded in the instance lifecycle history. No key, password, decrypted payload or
form value is ever logged.

## Console

- **Execution (instance) detail page** — Pause / Resume / Terminate buttons per status, gated on the instance
  permissions, with a pause confirmation and a terminate confirmation that captures a reason. A paused or
  terminated instance shows a banner with the reason and (for terminate) who did it and when.
- **Task inbox / form** — a paused or terminated task shows the explanatory message, keeps Save Draft, and hides
  Submit. The form stays editable so a draft can still be saved. Driven by the server-computed task
  capabilities, so the UI and the backend agree.

## Configuration

Nothing new to configure; the feature uses the existing execution store and audit trail.
