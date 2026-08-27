# Workflow Engine UI

Angular 20 operations console for the workflow engine. Standalone components, signals, no UI component
library, no diagram library.

The design constraint that shapes the whole application: **the engine's node types are not a fixed
list**. Four are built in, everything else arrives at runtime as a plugin JAR. So this front end never
hardcodes a node type, a configuration field or an integration name. It reads `GET /api/nodes` and
renders whatever it finds, which is why a plugin uploaded five minutes ago is immediately usable in the
designer with no front-end release.

---

## Running it

```bash
npm install
npm start
```

Serves on <http://localhost:4200> and proxies `/api` to `http://localhost:8080`
(see [proxy.conf.json](proxy.conf.json)), so run the engine alongside it.

```bash
npm run build      # production bundle into dist/
npm test           # 90 unit tests, headless Chrome
```

With Docker, `docker compose up --build` from the repository root brings up MongoDB, the engine and this
UI behind nginx on port 4200, with `/api` proxied server-side.

---

## Screens

| Route | What it is for |
|---|---|
| `/workflows` | The inventory. Status alongside the live published version, because a workflow edited after publishing is DRAFT and still executable at its previous version. |
| `/workflows/:id` | **The designer.** Palette, canvas, property panel. `new` starts a blank draft. |
| `/executions` | Every run, whatever started it. Auto-refreshes only while something is in flight. |
| `/executions/:id` | Timeline, variables, result, logs. Retries, chosen branches and per-node errors are on the timeline, not hidden behind expanders. |
| `/inbox` | **Task inbox.** Executions parked on a form, with the form rendered beside the queue. |
| `/nodes` | Every node type this engine can currently execute, with the schema each one published. |
| `/plugins` | Install, activate, deactivate, reload, roll back, and read invocation history. |
| `/secrets` | Credential names and metadata. Write-only: no endpoint returns a value, and none should. |
| `/events` | Emit a business event, with the workflows that subscribe to it listed first. |

---

## The two pieces that carry the architecture

### `shared/forms/` — the schema-driven form

`SchemaForm` renders a form from the JSON-schema subset the SDK's `SchemaBuilder` emits. It knows nothing
about SendGrid, Slack or HTTP; it knows how to map a schema to controls:

| Schema | Control |
|---|---|
| `format: 'secret-ref'` | secret picker over known secret names, labelled as a *name*, never a value |
| `enum` | dropdown, plus the current value when it is a `${...}` expression rather than one of the options |
| `format: 'textarea'` | multi-line |
| `integer` / `number` | numeric, but accepts `${...}` because an author routinely wants a variable there |
| `boolean` | checkbox |
| `object` with `additionalProperties` | key/value editor |
| `object` without | JSON editor, honest that the schema does not describe the shape |

The mapping rules are pure functions in [`schema-fields.ts`](src/app/shared/forms/schema-fields.ts) and
are unit-tested directly, because a subtle mistake here silently drops an operator's input.

### `features/designer/` — the canvas

An SVG edge layer beneath absolutely positioned HTML nodes, sharing one transform. Nodes need wrapping
text, badges and focusable controls, which are painful in SVG; edges need real curves, which is the
opposite.

* Drag from the palette, or double-click to drop one in.
* Connect by dragging from a node's right-hand port to anywhere on the target node, not to an 11-pixel
  target.
* Scroll to zoom about the cursor, drag the background to pan, `Fit` to recover.
* Decision branches become named ports, so an unwired branch is visible on the canvas.
* Delete removes the selection; every node is a focusable button, and focus selects it, so the canvas is
  usable from the keyboard.

Geometry, edge routing and automatic layout are pure functions in
[`graph-geometry.ts`](src/app/features/designer/graph-geometry.ts). Automatic layout matters more than it
sounds: a workflow authored as JSON has no coordinates, and rendering every node stacked at the origin
makes the canvas useless.

---

## Decisions worth knowing

**Version pinning is explicit and visible.** Dropping a plugin node records the exact loaded version, so
it is pinned from the moment it is created. The property panel shows the pin and offers to remove it, and
the canvas badges the version. Leaving it blank would silently re-point the node when a newer version is
uploaded.

**A node whose plugin is not loaded is flagged, not hidden.** It stays in the definition, is drawn with a
dashed border and an `unavailable` badge, and its existing configuration is still editable as JSON.
Hiding it would make a workflow look fine while publishing rejects it.

**Both validation voices are shown.** Local structural checks update as the graph is drawn; the engine's
validator is authoritative about plugins, expressions and cron syntax. The issue bar lists both, and the
engine's full rejection list is kept on screen rather than flashed in a toast.

**The admin key lives in `sessionStorage`, not `localStorage`.** It authorises installing executable code
into the engine's JVM, so it should not outlive the browser session. It is attached only to
`/api/plugins` and `/api/secrets`, never broadcast to every request.

**Polling stops when nothing is happening.** The execution list polls only while a run is in flight; the
detail view stops when the run is terminal or waiting. A screen of finished executions should not generate
traffic.

**Destructive actions say what they will actually do.** Deactivating a plugin warns that in-flight
invocations are drained and names the node types that will stop resolving. Deleting a workflow says it
also deletes its published versions.

---

## Brand and accessibility

Colours are the OrchPilot palette verbatim, declared once in
[`_tokens.scss`](src/styles/_tokens.scss); nothing else in the codebase contains a hex value. Raleway
carries the wordmark, navigation and headings, Arial the body text, tables and forms. Neither is fetched
from a CDN: an on-premise console must render with no outbound network access. To pin Raleway exactly,
drop woff2 files into `public/fonts` and add an `@font-face` rule to the tokens file.

One status means one colour everywhere, through a single `StatusPill`, so a colour on the canvas means
what it means in the execution list. Only in-flight statuses animate, and the animation respects
`prefers-reduced-motion`.

Every icon is paired with a visible text label. The canvas exposes its nodes as focusable buttons with
descriptive labels, and the property panel is the non-visual route to everything the canvas shows.
Dialogs are labelled and close on Escape, except confirmations, which do not dismiss on a stray click.

---

## Layout

```
src/app/
├── core/
│   ├── api/            one typed client per engine resource; NodeApiService also caches the catalogue
│   ├── models/         interfaces mirroring the engine's DTOs
│   ├── api.interceptor.ts   identity headers out, single error shape in
│   ├── session.store.ts     actor and admin key
│   └── notification.service.ts
├── shared/
│   ├── forms/          schema-driven form, key/value editor, coercion rules
│   ├── ui/             status pill, modal, toasts, empty state, node glyphs
│   └── pipes/          duration, relative time, bytes, pretty JSON
├── features/
│   ├── designer/       store, canvas, palette, property panel
│   ├── workflows/  executions/  inbox/  plugins/  nodes/  secrets/  events/
├── app.ts              shell: navigation, waiting badge, identity dialog
└── app.routes.ts       every feature lazily loaded
```
