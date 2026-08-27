# Excel Handler Plugin

Excel workbooks as OrchPilot workflow nodes and AI Agent tools — read, write, search, filter, sort, transform,
validate, merge, split, compare, report, and convert to and from JSON and CSV.

- **Plugin id:** `orchpilot-excel-handler` · **Version:** `1.0.0` · **Category:** `FILE_PROCESSING`
- **27 nodes**, one per operation · **Java 17**, Apache POI 5.2.2 bundled · **15.5 MB** JAR
- Reuses the **existing** AI Agent, Plugin Server, Plugin Registry, JWT/RBAC and Workflow File Storage. Nothing
  here is a second copy of any of them.

---

## 1. The one platform change this needed

At the end of the file-storage work I flagged that plugins had **no route to `WorkflowFileStorageService`** —
`PluginContext` has no file accessor — and said it would need a deliberate SDK change. This plugin requires it,
so that change is included here.

It is hung on **`NodeExecutionContext`, not `PluginContext`**, and that is the security model:

```java
// NodeExecutionContext already knows workflowId() and workflowVersion().
// So WorkflowFileAccess is bound to the executing workflow and takes NO workflowId parameter.
default WorkflowFileAccess files() { … }
```

A plugin **cannot express** a request for another workflow's file — there is no argument to put the wrong id
into, so there is no cross-workflow read to get wrong. The alternative (`read(workflowId, version, fileId)` plus
a check) puts an insecure-direct-object-reference one forgotten line away, in every plugin, forever.

**It is a `default` method, so it is binary-compatible.** All ten previously shipped plugin JARs keep working
untouched — verified: the full 16-module reactor builds and tests green. An engine too old to supply an accessor
throws a named `UnsupportedOperationException` rather than returning null.

| Added | Where |
|---|---|
| `WorkflowFileAccess`, `WorkflowFileHandle` | `workflow-plugin-sdk` |
| `NodeExecutionContext.files()` (default) | `workflow-plugin-sdk` |
| `ExecutionScopedFileAccess` | `workflow-engine-core` |
| `WorkflowFileStorageService.storeStream(…)` | `workflow-engine-core` |

`WorkflowFileHandle` carries **no path** — not relative, not absolute. A plugin gets an id, a name, a size and a
checksum, and asks the engine to open it. The storage location stays an engine concern, so an administrator can
move it or switch to object storage and no plugin notices.

---

## 2. Excel processing architecture

```
WorkflowFileAccess.open(fileId)          ← the only way in
        │
        ▼
WorkbookReader ──► SheetTable ──► Conditions / TableOps / TransformEngine
   (POI edge)      (headers +      ValidationEngine / CompareEngine / ReportEngine
                    rows)                        │
                                                 ▼
                                          WorkbookWriter (SXSSF)
                                                 │
                                                 ▼
                                   WorkflowFileAccess.write(…) ← always a NEW file
```

**POI touches exactly two classes.** `WorkbookReader` converts a workbook into a `SheetTable`; `WorkbookWriter`
converts one back. Everything between is ordinary Java over lists, which buys three things: the operations are
pure functions that test without fabricating a workbook, they compose (filter → sort → report is three calls on
one type), and the day the input is a CSV or a JSON array instead, nothing downstream changes.

**Types are narrowed at the edge.** Excel stores every number as a double and marks a date only by its display
format. Reporting that faithfully hands a workflow `45231.0` where it expected `2023-11-15` and `50000.0` where
it expected a salary. So `CellReader` narrows to `INTEGER`/`LONG`/`DECIMAL`/`DATE`/`DATETIME`/`BOOLEAN` and says
which it chose. Computed results narrow through one shared `CellValue.computed()` — the two engines that produce
them drifted apart once, and a test caught it.

---

## 3. Capabilities

| Group | Operations |
|---|---|
| **Read** | `sheet.read` · `workbook.listSheets` · `sheet.metadata` · `cell.get` |
| **Shape** | `search` · `filter` · `sort` · `validate` · `transform` · `compare` · `report` |
| **Write** | `create` · `sheet.write` · `row.add` · `row.update` · `cell.set` · **`row.delete`** |
| **Sheets** | `sheet.create` · `sheet.rename` · `sheet.copy` · **`sheet.delete`** |
| **Files** | `merge` · `split` |
| **Convert** | `toJson` · `fromJson` · `toCsv` · `fromCsv` |

**Bold = `destructive`**, so a supervised AI Agent must have it approved.

| Risk | Operations |
|---|---|
| `READ_ONLY` | every read, search, filter, sort, validate, compare, toJson |
| `LOW` | transform, report, toCsv |
| `MEDIUM` | create, write, append, update, set cell, sheet create/rename/copy, merge, split, fromJson, fromCsv |
| `HIGH` | **delete row, delete sheet** |

---

## 4. AI Agent integration

Every node sets `supportsAI = true` and carries its capability id, so the **existing** agent discovers them
through the **existing** Plugin Registry. No new agent, no redeployment.

The path is always `AI → capability → permission → policy → file accessor`. What the agent sees is a fixed
catalogue of typed operations — never a formula evaluator, never a scripting hook, never a path.

> *"Increase salary by 10% for IT employees"* → the agent calls `excel_sheet_read`, then `excel_filter`
> (`Department = IT`), then `excel_transform` (`CALCULATE Salary × 1.10`), and gets back a **new** file id. The
> original workbook is untouched and still readable.

> *"Split this by department"* → `excel_split` returns one file id per department.

---

## 5. Workflow node configuration

**No bespoke Angular was written, and none is needed.** The designer's `SchemaForm` already renders any plugin
node from its published schema — that is how every other plugin's form works. Each of the 27 nodes declares its
own schema, so *Search* shows a column, operator and value while *Append* shows a row list, and the form changes
with the operation for free.

A single "Excel Handler" node with an operation dropdown would have been the other design and is worse: one risk
flag for everything (so the agent could not tell a read from a delete), and fifty fields on one form.

```
Filter Excel                          Update Excel Rows
─────────────────                     ─────────────────
Excel file   [ fileId       ]         Excel file   [ fileId          ]
Sheet        [ Employees    ]         Sheet        [ Employees       ]
Header row   [ -1 = detect  ]         Find column  [ EmployeeId      ]
Conditions   [ [{…}]        ]         Find value   [ ${employeeId}   ]
Combine      [ AND ▼        ]         Updates      [ {"Salary": …}   ]
```

---

## 6. File input

Anything that yields a **file id** works, because that is the only thing a node takes:

| Source | How |
|---|---|
| Form node upload | `${workflow.employeeFileId}` from the form's output mapping |
| Previous Excel node | every write operation outputs `fileId` |
| Workflow variable | `${…}` — the engine resolves it before the plugin sees it |
| AI Agent | the agent supplies the id it read from a previous tool result |
| Existing attachment | the file id from Settings → the workflow's Files panel |

**A filesystem path is never accepted**, because there is no field for one and no code path that could use it.

---

## 7. Security

| Concern | How it is closed |
|---|---|
| **Arbitrary paths** | The plugin has no filesystem access. Files come only from the execution-scoped accessor. |
| **Cross-workflow reads** | Structurally impossible — the accessor takes no `workflowId`. |
| **Macros** | Never executed. A `.xlsm` opens as data; the macro storage is never read, so there is no path that could run VBA. |
| **Formula execution** | Formulas are *read*, not interpreted. Calculated values come from Excel's own cached result, or from POI's evaluator — a fixed set of spreadsheet functions in Java, which cannot reach the filesystem, network or JVM. |
| **Formula *writing*** | Restricted to an allow-list (`SUM`, `VLOOKUP`, `IF`, …). `WEBSERVICE` and `RTD` fetch remote content when a workbook is opened; they are rejected. |
| **CSV injection** | A value starting with `=`, `+`, `-` or `@` is apostrophe-prefixed on export. The content survives; a spreadsheet treats it as text. |
| **Zip bombs / OOM** | Input read under this node's own byte ceiling before POI sees it. Rows, columns and wall-clock checked as work proceeds. `OutOfMemoryError` is caught and turned into an actionable node failure rather than destabilising the engine. |
| **Regex DoS** | Regex is **opt-in per node**; patterns compile once, and an invalid one fails the node rather than throwing per row. |
| **Overwriting input** | Never happens. Every mutating operation stores a **new** file, so a retry starts from what it started from and the original stays readable. |

### One thing I deliberately did *not* build

**There is no expression evaluator in `TRANSFORM`.** A general one would be the obvious design and would mean
shipping an interpreter that runs workflow-supplied text inside the engine's process — precisely the capability
this platform withholds from plugins everywhere else. Transformation is a list of declared steps instead:
`CONCAT` joins named columns, it cannot call a method. Some exotic derivation is not expressible; nothing here
can execute anything.

### A POI trap worth knowing about

My first implementation called `IOUtils.setByteArrayMaxOverride()` to bound allocations. **That is a JVM-wide
static** — a plugin setting it changes limits for the engine and every other plugin in the process — and a value
low enough to be a useful guard also breaks POI's own internal buffers. Every workbook failed. The input is now
bounded by this plugin's own counter, and POI's defaults are left alone. The tests caught it.

---

## 8. Large files

- **Generation always streams.** `SXSSFWorkbook` keeps a 100-row window in memory and flushes the rest to
  temporary files, so a 200 000-row report costs bounded heap. Temp files are disposed in `close()` — without
  that, an engine producing scheduled reports fills its temp directory.
- **Reads are bounded on four axes**, checked as work proceeds, not afterwards:

| Limit | Default | Ceiling |
|---|---|---|
| `maxRows` | 100 000 | 1 000 000 |
| `maxColumns` | 512 | 16 384 |
| `maxFileBytes` | 32 MB | 128 MB |
| `maxProcessingSeconds` | 120 | 600 |

Configured values are **clamped, not rejected** — raising a limit is reasonable; the ceiling stops one node from
taking down an engine shared with every other workflow.

The deadline is cooperative and checked per row. A watchdog thread interrupting a POI parse can leak a file
handle and leave a half-built workbook; stopping at a row boundary is safe.

---

## 9. Error codes

`EXCEL_FILE_NOT_FOUND` · `EXCEL_INVALID_FORMAT` · `EXCEL_CORRUPTED` · `EXCEL_UNSUPPORTED_FORMAT` ·
`EXCEL_SHEET_NOT_FOUND` · `EXCEL_ROW_NOT_FOUND` · `EXCEL_COLUMN_NOT_FOUND` · `EXCEL_INVALID_DATA` ·
`EXCEL_VALIDATION_FAILED` · `EXCEL_FILE_TOO_LARGE` · `EXCEL_PROCESSING_TIMEOUT` *(retryable)* ·
`EXCEL_STORAGE_ERROR` · `EXCEL_PERMISSION_DENIED`

A "not found" always names the alternatives — a missing sheet lists the sheets that exist, a missing column
lists the columns. That turns a dead end into a fix.

**Validation is the deliberate exception:** it reports `valid: false` and **succeeds**, so the workflow branches
on it. Failing the node would conflate *"the check could not run"* with *"the data is wrong"*. Set
`failOnInvalid: true` to stop the workflow instead.

---

## 10. Installation

```bash
mvn -o -pl plugins/excel-plugin -am package -DskipTests
```

Upload `orchpilot-excel-plugin-1.0.0.jar`. It needs **no network hosts and no secrets** — the only permission it
uses is workflow file access, which the engine grants per execution.

> **Requires an engine build that includes plugin file access** — i.e. one containing
> `ExecutionScopedFileAccess`. The manifest declares the ordinary `>=1.0.0 <2.0.0` like every other plugin,
> because file access arrived **without a version bump**: an engine either supplies it or does not, and both
> report 1.0.0, so no manifest range can distinguish them. The check is at runtime instead — an engine without
> it fails the node with a named `EXCEL_STORAGE_ERROR` telling you to upgrade, rather than a
> `NullPointerException`.
>
> If you would rather have this enforced at *install* time, bump the SDK and engine to 1.1.0 and set this
> plugin's `engineCompatibility` back to `>=1.1.0` — see §13.

Storage must be configured first — Settings → File Storage — or every operation returns
`FILE_STORAGE_NOT_CONFIGURED` from the storage layer.

---

## 11. Example workflow

[`examples/employee-salary-review.json`](examples/employee-salary-review.json) — form upload → validate →
branch → filter IT → raise salaries 10% → department summary → email.

```
FORM (upload .xlsx)
      ↓
EXCEL_VALIDATE  →  DECISION on valid
      ↓ valid                    ↓ invalid → END (errors)
EXCEL_FILTER (Department = IT)
      ↓
EXCEL_TRANSFORM (Salary × 1.10, AnnualSalary = Salary × 12)
      ↓
EXCEL_GENERATE_REPORT (group by Department: SUM, AVERAGE, COUNT)
      ↓
EMAIL_SEND  →  END
```

---

## 12. Tests

**52 tests, no fixture files.** Workbooks are built with POI at test time, so a test cannot pass against a stale
fixture that no longer resembles what Excel writes — and the `.xls` and `.xlsx` cases are the same code with one
flag.

| Suite | Covers |
|---|---|
| `WorkbookReaderTest` (20) | type narrowing, ISO dates, formulas as value **and** as text, blank cells, header detection after a preamble, explicit/absent/duplicate headers, `.xls`, empty sheet, multi-sheet, missing sheet, non-workbook, empty file, **truncated file**, row limit, limit clamping, row ranges |
| `ExcelPluginTest` (32) | catalogue and risk flags, all four return shapes, cell read, search, multi-condition filter, **regex opt-in**, sort, validation (reports **and** fails-on-demand), transform with derived column, report aggregation, create/append/update/delete round-tripped **through POI and read back**, **input never overwritten**, sheet rename, refusing to delete the only sheet, merge with unioned columns, split, **bounded split**, compare, CSV round-trip, **CSV formula injection**, JSON→Excel key union, unknown file, unsupported format, missing column |

```bash
mvn -o -pl plugins/excel-plugin test
```

Two production bugs were found by these tests and fixed: the POI global-static problem in §7, and computed
values not narrowing to integers.

---

## 13. Optional: enforcing file access at install time

Today the manifest says `>=1.0.0 <2.0.0` and the file-access requirement is caught at runtime, because the SDK
capability was added without a version bump — so there is no version for a range to test against.

Making it an install-time check is a one-line-per-file change, but it touches the whole reactor:

1. `pom.xml` — parent `<version>` and `<workflow-plugin-sdk.version>` to `1.1.0`
2. every module's `<parent><version>` and `workflow-plugin-sdk` dependency version
3. this plugin's manifest → `"sdkVersion": "1.1.0"`, `"engineCompatibility": ">=1.1.0 <2.0.0"`
4. the other eleven plugin manifests stay at `>=1.0.0` — they do not use `files()`, so they remain loadable on
   either engine

That is the semantically correct answer: a new SDK capability *is* a minor version. It is deliberately not done
here because a repo-wide version bump is a bigger decision than this plugin, and the runtime guard already fails
safely with an actionable message. Say the word and it is a short, mechanical change.
