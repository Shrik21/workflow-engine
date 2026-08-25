# External / Public Form Links

Let a workflow Form Task be completed by an external customer who has **no OrchPilot account**, through a
secure link — reusing the existing form node, form designer, task, execution engine, instance lifecycle and
audit trail. No parallel form system is introduced.

```
Form Node (Assignment: External) ─▶ Task raised, WAITING ─▶ Generate secure link
        ─▶ customer opens /public/form/{token} ─▶ fills in / saves draft ─▶ Submit
        ─▶ task COMPLETED ─▶ variables mapped ─▶ workflow continues
```

## Security model — the core

- **Token**: 32 random bytes from `SecureRandom`, URL-safe base64 — long, non-sequential, non-guessable. Never
  a UUID, database id, or base64-of-something used as a secret.
- **Stored as a hash only**: the raw token exists solely in the customer's URL and, once, in the generation
  response. Only **SHA-256(token)** is persisted (uniquely indexed). A database compromise yields no working
  links.
- **Two separate auth models**: internal APIs need a JWT; the public form APIs (`/api/public/forms/**`) are
  whitelisted from JWT and authorized *only* by the form token, verified inside every call. A form token cannot
  call any internal API (it isn't a JWT); an external customer never receives a JWT.
- **Everything resolved from the token**: the task, workflow instance and tenant all come from the token record.
  The customer supplies only field values by name, so there is nothing in the request to tamper with — a token
  reaches exactly one task and cannot cross to another task or tenant.

## Token lifecycle

`ACTIVE → USED` (allowance spent) / `REVOKED` (admin) / `EXPIRED` (past expiry). Every link has an expiry
(default 24h, configurable). Default `maxSubmissions = 1` (single-use); multi-use is configurable. Regenerating
revokes the old token and mints a new one — the old URL dies immediately.

## Form rules, enforced on the server

The instance's live state gates the form, checked on every call and mapped to a customer-safe screen:

| Instance state | Save Draft | Submit |
|---|---|---|
| RUNNING | ✅ | ✅ |
| PAUSED | ✅ | ❌ → 409, "workflow is paused" |
| TERMINATED | ✅ | ❌ → 409, "workflow terminated" |
| COMPLETED / task already submitted | view only | ❌ → "already submitted" |

Submit re-validates token → instance running → task actionable, then reuses the **same** server-side form
validation, task completion and `submitSignal` engine-resume the internal task path uses. A submission from an
already-open tab onto a paused, terminated or used instance is refused with a **409** — the disabled button is a
courtesy, the server is the control.

## API

**Public (token-authorized, no JWT):**

| Method & path | Purpose |
|---|---|
| `GET /api/public/forms/{token}` | Open — returns title, fields, expiry, allow flags, saved draft. Never any workflow/task/tenant/form id. |
| `POST /api/public/forms/{token}/draft` | Save draft — advances nothing. |
| `POST /api/public/forms/{token}/submit` | Submit — completes the task, continues the workflow, returns a reference number. |

**Internal (JWT + permission):**

| Method & path | Permission |
|---|---|
| `POST /api/workflow-tasks/{taskId}/external-link` | `EXTERNAL_FORM_CREATE_LINK` |
| `POST .../external-link/revoke` | `EXTERNAL_FORM_REVOKE_LINK` |
| `POST .../external-link/regenerate` | `EXTERNAL_FORM_CREATE_LINK` |
| `GET .../external-link` | `EXTERNAL_FORM_CREATE_LINK` (status only, no token) |

Generate/regenerate return the URL exactly once; nothing else returns a token. Every action is audited
(`EXTERNAL_FORM_LINK_CREATED/REVOKED/REGENERATED`, `EXTERNAL_FORM_OPENED/DRAFT_SAVED/SUBMITTED/SUBMIT_FAILED/
EXPIRED`) with task/instance/tenant, IP and user-agent — never form values or tokens.

## Rate limiting

A per-IP fixed-window limiter on `/api/public/forms/**` (default 60/min, stricter 10/min on submit,
configurable), answering `429` before a flood reaches the database. In-memory and per-instance — the one line to
change for a multi-node deployment is the shared counter store. The window resets each minute, so a real
customer is never locked out on IP alone.

## Console

- **Public page** — a chromeless `/public/form/:token` route outside the authenticated shell (no login
  redirect), reusing the existing `dynamic-form` to render whatever the form designer built. Paused/terminated
  banners, distinct Invalid/Expired/Revoked/Already-submitted/Unavailable screens, and a Thank-You page with a
  reference number. Header "OrchPilot", footer "Powered by OrchPilot" — no workflow, node, task or internal id.
- **Task inbox** — an external task shows an "External" tag and "waiting for external response", plus a link
  panel to Generate / Copy (shown once) / Revoke / Regenerate, gated on `EXTERNAL_FORM_CREATE_LINK`.

## Configuration

```yaml
workflow:
  engine:
    external-form:
      base-url: ${WORKFLOW_EXTERNAL_FORM_BASE_URL:/public/form/}   # absolute https URL to email links
      default-expiration-hours: 24
      rate-limit-per-minute: 60
      submit-rate-limit-per-minute: 10
      captcha-required: false
```

## Deferred seams (flagged, not silently stubbed)

- **File upload** — file fields render, but secure upload (storage, MIME sniffing, AV scan) is a separate
  sub-system; the dangerous-extension blocklist (`.exe/.bat/.cmd/.ps1/.sh/…`) is configured and ready to enforce.
- **CAPTCHA** — a config flag and a `captchaToken` field are in place; no provider (reCAPTCHA/Turnstile) is
  wired. Off by default.
- **Email delivery of the link** — deferred per request; the existing email plugin is the intended hook.
- **Stronger customer auth** (OTP / reference+DOB / SSO) — the token resolution is the single extension point;
  the initial implementation is link-only.
- **Multi-node rate limiting** — swap the in-memory counter for a shared store.
