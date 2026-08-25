# Workflow File Storage

Settings → File Storage: where OrchPilot physically writes files uploaded to a workflow, and how those files are
referenced, served and audited.

Built inside the existing Workflow microservice. It adds **no** authentication, workflow engine, permission
system, plugin registry or plugin server — it reuses all five.

---

## 1. Architecture

```
Angular  Settings → File Storage        Designer → Files panel
   │                                          │
   ▼                                          ▼
StorageApiService                    WorkflowFileApiService
   │  /api/settings/storage                   │  /api/workflows/{id}/versions/{v}/files
   ▼                                          ▼
┌──────────────────────────────────────────────────────────────┐
│ EXISTING platform: JwtAuthenticationFilter → RBAC            │
│   settings endpoints  → WORKFLOW_STORAGE_SETTINGS_VIEW/EDIT  │
│   file endpoints      → workflowAuthorizationService.canView │
│                                              /canEdit        │
└──────────────────────────────────────────────────────────────┘
   │                                          │
   ▼                                          ▼
WorkflowStorageSettingsService      WorkflowFileStorageService
   │  validate → canonicalise → save          │  resolve settings, version, filename,
   │                                          │  path, checksum, record, audit
   ▼                                          ▼
StoragePathValidator              FileStorageProviderRegistry
                                              │  dispatch on the reference's storageType
                                              ▼
                                     FileStorageProvider  (interface)
                                              │
                                     LocalFileStorageProvider  ← ships today
                                     S3 / AzureBlob / GcpStorage / Nfs  ← later
                                              │
                                              ▼
                                     Physical storage + MongoDB reference
```

Package layout, all under `com.orchpilot.workflow.storage`:

| Package | Holds |
|---|---|
| `model` | `WorkflowStorageSettings`, `WorkflowFileReference`, `StorageType`, `StorageStatus`, `FileStatus`, `RetentionPolicy` |
| `repository` | `WorkflowStorageSettingsRepository`, `WorkflowFileRepository` |
| `provider` | `FileStorageProvider`, `LocalFileStorageProvider`, `FileStorageProviderRegistry`, `StoredObject` |
| `service` | `WorkflowFileStorageService`, `WorkflowStorageSettingsService` |
| `controller` | `StorageSettingsController`, `WorkflowFileController` |
| `validation` | `StoragePathValidator` |
| `util` | `FilenameSanitizer`, `StoragePaths` |
| `health` | `StorageHealthService` |
| `audit` | `StorageAuditService` |
| `exception` | `FileStorageException` |
| `dto` | request/response records |

---

## 2. Directory structure

Tenancy already exists in the platform as a **nullable discriminator** (`User.tenantId`, `Workflow.tenantId`,
`Group.tenantId`) and is dormant. So the tenant segment appears **only when `tenantId` is non-null**:

**Single-tenant (today)**
```
D:\OrchPilot\data\                    /opt/orchpilot/data/
    workflows\                            workflows/
        WF-123\                               WF-123/
            v1\                                   v1/
                files\                                files/
                    8f82c1-document.pdf                   8f82c1-document.pdf
            v2\                                   v2/
                files\                                files/
                    a91b04-document.pdf                   a91b04-document.pdf
```

**Multi-tenant (when activated)**
```
D:\OrchPilot\data\tenants\tenant123\workflows\WF-123\v1\files\...
```

Because the relative path is stored **per file**, switching layouts later strands nothing: old files keep their
recorded path, new ones get the new shape.

---

## 3. MongoDB schema

Two new collections. Nothing existing is duplicated or modified.

### `workflowStorageSettings` — one document per tenant

```json
{
  "_id": "...",
  "tenantId": null,
  "storageType": "LOCAL",
  "basePath": "D:\\OrchPilot\\data",
  "enabled": true,
  "retentionPolicy": "NEVER",
  "retentionDays": null,
  "createdAt": "...", "createdBy": "...",
  "updatedAt": "...", "updatedBy": "...",
  "documentVersion": 3
}
```

`basePath` is stored **canonicalised** (`Path.toRealPath()` — symlinks and `..` resolved), because every
containment check compares against it. `documentVersion` is `@Version`, so two administrators saving different
paths at once cannot silently overwrite each other.

**Index:** `tenantId` — unique, sparse (permits the single `null` row, prevents a duplicate on concurrent first save).

### `workflowFiles` — one document per uploaded file

```json
{
  "_id": "8f82c1a4b3d2e6f0",
  "tenantId": null,
  "workflowId": "WF-123",
  "workflowVersion": 2,
  "originalFileName": "invoice.pdf",
  "storedFileName": "8f82c1a4b3d2e6f0-invoice.pdf",
  "relativePath": "workflows/WF-123/v2/files/8f82c1a4b3d2e6f0-invoice.pdf",
  "contentType": "application/pdf",
  "size": 102400,
  "checksum": "a8c9...",
  "storageType": "LOCAL",
  "status": "ACTIVE",
  "createdAt": "...", "createdBy": "userId",
  "deletedAt": null, "deletedBy": null
}
```

**Indexes:**
- `_id` (= `fileId`) — implicit
- `tenantId + workflowId + workflowVersion + status` — the listing query
- `workflowId + workflowVersion + checksum` — duplicate detection

`_id` **is** the `fileId`. One identifier, so a reference cannot disagree with itself.

**The path is relative and POSIX-style on every platform.** No absolute path is stored here, in a workflow
definition, in a workflow variable, or in an export.

---

## 4. API

### Settings — `WORKFLOW_STORAGE_SETTINGS_*`

| Method | Path | Permission |
|---|---|---|
| `GET` | `/api/settings/storage` | `..._VIEW` |
| `PUT` | `/api/settings/storage` | `..._EDIT` |
| `POST` | `/api/settings/storage/test` | `..._EDIT` |
| `GET` | `/api/settings/storage/health` | `..._VIEW` |
| `DELETE` | `/api/settings/storage` | `..._EDIT` |

`GET` **re-probes** the path rather than returning a status stored at save time — a volume that failed to mount
after a restart is exactly what an administrator opens this screen to discover.

`POST /test` returns:
```json
{ "valid": true, "readable": true, "writable": true,
  "canonicalPath": "D:\\OrchPilot\\data", "created": false, "freeSpaceBytes": 123456789, "problems": [] }
```

### Files — workflow access control

| Method | Path | Check |
|---|---|---|
| `POST` | `/api/workflows/{id}/versions/{v}/files` | `canEdit` |
| `GET` | `/api/workflows/{id}/versions/{v}/files` | `canView` |
| `GET` | `/api/workflows/{id}/versions/{v}/files/{fileId}` | `canView` |
| `DELETE` | `/api/workflows/{id}/versions/{v}/files/{fileId}` | `canEdit` |
| `GET` | `/api/workflows/{id}/versions/{v}/files/consistency` | `canEdit` |

Files are addressed by **id**, never by path. Nothing accepts a path and nothing returns one.

---

## 5. Angular UI

| Component | Route / placement |
|---|---|
| `StorageSettingsPage` | `/settings/storage` — nav item "File Storage", guarded by `..._VIEW` |
| `WorkflowFiles` | embedded in the **designer's property panel**, next to Access control |
| `StorageApiService`, `WorkflowFileApiService` | `core/api/` |

**Settings screen** — storage type, base path, create-if-missing, accept-uploads, retention; then
`[Test path]` `[Save]` `[Reset]`.

**Save is disabled until the current path has been tested and passed**, and editing any probed field clears the
previous result. The server validates independently and would refuse a bad path anyway; making the order visible
turns "your path is wrong" from an error into a step.

**Files panel** — upload (multiple, sequential, with a progress bar), list with size/version/uploader/time, and
per-row Download and Delete. A file whose content is missing from storage is badged **Missing** in the list
rather than only failing when somebody clicks Download.

---

## 6. Security model

Six layers. Any one would have to fail before a traversal succeeded.

1. **Filename allow-list** — `FilenameSanitizer` takes the last path segment (after decoding percent-escapes,
   including double-encoded) and replaces every character outside
   `[A-Za-z0-9.\-_ ]`. Defuses Windows device names (`CON`, `LPT1`), strips trailing dots/spaces (Windows drops
   them silently, so `x.txt.` and `x.txt` are the same file to the OS but different strings to a check), strips
   leading dots, and removes NUL bytes and header-breaking characters.
2. **Generated stored name** — `{fileId}-{sanitised}`. The original name is never the only physical name, so
   concurrent uploads of `invoice.pdf` cannot collide.
3. **Controlled path segments only** — `StoragePaths` accepts no caller-supplied fragment. Every segment
   (tenant, workflow, version, file id) is platform-generated and asserted safe. There is deliberately no method
   of the form `basePath + userInput`.
4. **Stored-path validation** — `requireRelative()` rejects absolute paths, backslashes and traversal segments
   when a path is read *back* from the database, in case a document was edited directly.
5. **Containment after resolution** — `LocalFileStorageProvider.resolve()` normalises the resolved path and
   requires `Path.startsWith(canonicalRoot)`. Checked on the *resolved* path, not the key: a symlink inside the
   tree can point anywhere and no string inspection reveals it. `Path.startsWith` is separator-aware, so
   `/data/xyz` is not treated as inside `/data/x`.
6. **Canonical root** — the base path is stored already resolved through `toRealPath()`, so layer 5 compares
   against something with no symlinks left in it.

Covered by tests: `../`, `..\`, absolute paths, Windows drive traversal (`C:\`, `C:file.txt`), UNC paths
(`\\host\share\x`), percent-encoded and double-encoded traversal, `....//`, NUL bytes, device names, and a
sibling directory sharing a name prefix.

**Never logged or returned:** file contents; the absolute path of any individual file. The absolute *root* is
returned by the settings endpoint (an administrator cannot verify a mount without seeing it, and that endpoint
is already permission-gated) and recorded in the settings audit entry — "who pointed storage where" is
unanswerable without it.

---

## 7. Permission model

Two new constants on the existing `Permission` enum:

| Permission | Grants |
|---|---|
| `WORKFLOW_STORAGE_SETTINGS_VIEW` | see the location, status, free space |
| `WORKFLOW_STORAGE_SETTINGS_EDIT` | change or clear it |

`Role.ADMIN` is defined as `EnumSet.allOf(Permission.class)`, so an administrator holds both **automatically**.
No other role lists them.

| | View | Edit |
|---|---|---|
| ADMIN | ✅ | ✅ |
| WORKFLOW_ADMIN, WORKFLOW_EDITOR, USER, … | ❌ | ❌ |

Split rather than combined so an auditor can confirm where files are kept without being able to redirect them.

**Uploading a file needs neither.** File operations follow the workflow's own access control
(`canView`/`canEdit`), including group grants and ownership. A separate `FILE_UPLOAD` permission would let the
two drift apart — which is how somebody ends up able to delete files from a workflow they cannot open.

---

## 8. File lifecycle

```
upload → ACTIVE ──delete──→ DELETED   (content gone, reference kept for audit)
              └──consistency check──→ ORPHANED   (reference exists, content missing)
```

A file lives in two places — bytes on storage, a row in MongoDB — with no transaction spanning both. Four
outcomes, each answered deliberately:

| | Answer |
|---|---|
| Both succeed | normal |
| Write fails | nothing recorded — clean |
| **Write succeeds, insert fails** | the orphaned bytes are **deleted** before the error is rethrown |
| **Record exists, bytes gone** | cannot be prevented, only detected → `FILE_NOT_FOUND_IN_STORAGE`, and found in bulk by the consistency check |

Deleting is a status change plus a physical delete, never a document removal: an audit trail that loses the
record of what was deleted is not an audit trail.

**Concurrency** — the service is stateless. The provider streams to a temporary file *in the destination
directory*, then `ATOMIC_MOVE`s it onto the final name, so a reader never sees a partial file. Same directory,
because an atomic move is only guaranteed within one filesystem. Where a filesystem refuses `ATOMIC_MOVE` (some
network shares), it falls back to a replacing move and **logs a warning** — a real, if small, weakening.

**Checksum** — SHA-256, computed while streaming through a `DigestOutputStream`, not by re-reading. The
recorded size is the **measured** one, never the client's declared size: a truncated upload reports the size it
meant to send.

---

## 9. Workflow version integration

`Workflow.version` is *the number the next publish will produce*; published `WorkflowVersion` documents are
`1..N`. Files uploaded while drafting at version *N* land in `v{N}` and become that published version's files —
**with no file movement when the version is cut.**

That satisfies "do not physically move files unnecessarily when a version changes" for free, and it is why
uploads are accepted against exactly two version numbers: a published version, or the current draft. Anything
else is a caller inventing a version, and is refused with a message naming the draft that does exist.

Versions never share a directory, so publishing v2 leaves v1's files exactly where they are — which is what
lets an execution still running on v1 read v1's files.

---

## 10. Import / export integration

`WorkflowPackage` gains `fileReferences`, following the **same contract as the existing `credentialReferences`**:
metadata travels, content does not.

```json
{ "fileName": "invoice.pdf", "contentType": "application/pdf",
  "size": 102400, "checksum": "a8c9...", "workflowVersion": 2 }
```

There is **no `relativePath` field, let alone an absolute one**. The path is derived on the target system from
its own storage root and the imported workflow's id, so a package exported from `D:\OrchPilot\data` on Windows
imports cleanly under `/opt/orchpilot/data` on Linux.

On import, `ValidationResult.fileReferences` is populated and a warning is raised:

> *N attached file(s) were described but not included in the package; re-upload them to the imported workflow's
> version. Their checksums are listed so you can confirm you uploaded the same files.*

The importer has to know the workflow expects files, or a node fails at run time with a `FILE_NOT_FOUND` that
gives no hint the package never carried them.

**Why the bytes do not travel:** carrying them would make an export unbounded in size and would move a copy of
every uploaded document to wherever the `.orchpilot` file goes — a data-handling decision that belongs to the
person exporting, not to the format. Packages written before this field existed still deserialise (the setter
tolerates null).

---

## 11. Cloning

`WorkflowFileStorageService.copyVersionFiles(sourceId, sourceVersion, targetId, targetVersion)` **copies** the
bytes to new references under the clone's own path, with new file ids.

```
WF-100/v2/files/abc-template.xlsx   →   WF-200/v1/files/xyz-template.xlsx
```

Copies rather than re-points: a clone sharing the original's references would mean deleting a file from the
clone deletes it from the original, entangling the two workflows' lifecycles permanently. A missing source file
is logged and skipped rather than abandoning the clone half-copied.

> **Note:** this repository has no clone *endpoint* today (`canClone` exists on the authorization service, but
> nothing calls it). The service method is ready for whenever one is added.

---

## 12. Plugin integration — and one thing I did **not** do

Plugins reach files through the `WorkflowFileStorageService` abstraction, never through the configured
filesystem path.

**I deliberately did not add a `files()` method to `PluginContext`.** That interface is the SDK contract all ten
shipped plugins compile against, and changing it is a breaking change requiring every one to be rebuilt — well
outside "add a Settings module". The engine-side service is in place and node executors can resolve a file
reference at runtime today.

**If you want plugins to read files directly, that is a deliberate SDK version bump** — say the word and I'll do
it as its own change, with all ten plugins rebuilt and tested. Until then, the intended flow is:

```
Form node → file reference in a workflow variable → node executor → WorkflowFileStorageService → bytes
```

with the node resolving the physical file only at execution time.

---

## 13. Docker / Linux deployment

**The configured directory must be backed by a persistent volume.** Container-local storage is destroyed with
the container — an upload that survives until the next deploy and then vanishes is worse than one that never
worked.

```yaml
services:
  workflow-engine:
    volumes:
      - /opt/orchpilot/data:/app/data     # host path : container path
```

Then configure the base path as the **container** path — `/app/data`, not `/opt/orchpilot/data`. The engine
resolves paths inside its own filesystem namespace.

- The process user must own or be able to write the mounted directory. A root-owned bind mount with a non-root
  container user is the most common cause of a `Test path` failure that looks like a permissions bug.
- The write probe is a genuine create/write/read/delete cycle, so it catches a read-only mount at configuration
  time rather than at the first upload.
- Back the volume up. Nothing in this module is a backup.

---

## 14. Future cloud storage

`FileStorageProvider` deliberately exposes **no `Path`, no `File`, no absolute location** — only relative keys.
S3 and Azure have no filesystem; an interface that leaked one would have to be redesigned the day a second
provider arrived rather than merely implemented.

```java
StoredObject store(String root, String relativeKey, InputStream content, long declaredSize);
InputStream   read(String root, String relativeKey);
boolean       delete(String root, String relativeKey);
boolean       exists(String root, String relativeKey);
List<String>  list(String root, String relativePrefix);
int           deletePrefix(String root, String relativePrefix);
long          freeSpace(String root);
```

Adding S3 is a new `@Component implements FileStorageProvider` and nothing else — `FileStorageProviderRegistry`
discovers providers from the Spring context and dispatches **per reference**, on the `storageType` recorded on
each file. That makes a migration additive: new files go to the new provider, files written before the switch
keep resolving through the one that wrote them, and no backfill is needed.

`StorageType.isImplemented()` gates the settings screen, so an unimplemented type is visible but cannot be
saved — configuration that would fail at the first upload is refused up front.

---

## 15. Testing

**72 storage tests**, all passing; **543 in the core module**, 0 failures.

| Suite | Covers |
|---|---|
| `FilenameSanitizerTest` (27) | traversal in both separators, encoded and double-encoded, absolute, UNC, drive-relative, device names, trailing dots/spaces, NUL, header-breaking characters, truncation, ordinary names |
| `LocalFileStorageProviderTest` (17) | round trip, checksum vs declared size, containment (6 hostile keys × 3 operations), nothing written on refusal, prefix-is-not-containment, idempotent delete, missing content, partial-upload filtering, prefix delete, version isolation, **16-way concurrent upload** |
| `StoragePathsTest` (16) | documented layouts, tenant/version/workflow isolation, POSIX separators, collision resistance, tampered stored paths |
| `StoragePathValidatorTest` (12, 2 OS-gated) | writable directory, probe cleanup, canonicalisation, missing directory with/without create, file-not-directory, relative refused, blank, NUL, Windows and Linux path shapes, **unwritable directory** |
| `WorkflowFileStorageServiceTest` (15) | layout, no absolute path recorded, collision, hostile upload name, unknown/draft version resolution, empty upload, **orphan cleanup when the insert fails**, workflow isolation, deleted file, version isolation, tenant isolation, not-configured message, clone copies, consistency check both directions |

The provider tests use a **real temporary directory**, not a mock: containment after symlink resolution, atomic
replacement and concurrent writes *are* filesystem behaviour, and a mocked `Files` would only assert that the
code calls the methods it calls.

```bash
mvn -o -pl workflow-engine-core test
```

---

## Error codes

| Code | HTTP | Meaning |
|---|---|---|
| `FILE_STORAGE_NOT_CONFIGURED` | 409 | No location set, or storage disabled |
| `FILE_STORAGE_UNAVAILABLE` | 503 | Configured but currently unusable |
| `FILE_NOT_FOUND` | 404 | No such file in this workflow *and* version |
| `FILE_NOT_FOUND_IN_STORAGE` | 410 | Reference exists, bytes do not |
| `FILE_REJECTED` | 400 | Empty upload, unknown version, bad input |
| `FILE_PATH_INVALID` | 400 | A resolved path escaped the root |
| `FILE_STORAGE_IO_ERROR` | 500 | The storage could not complete the operation |
| `FILE_STORAGE_PROVIDER_UNSUPPORTED` | 501 | No provider for that storage type |

The exact message for the unconfigured case is the one the specification asked for:

> *Workflow file storage has not been configured. Please contact an administrator.*

Nothing is ever silently written to a temporary directory instead.

---

## Configuration

Upload limits come from the **existing** `application.yml` and are unchanged:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 64MB
      max-request-size: 80MB
```

`MaxUploadSizeExceededException` was already handled by `GlobalExceptionHandler`. Nothing is loaded fully into
memory — upload and download both stream.

**The base path is never hardcoded** and has no default. Absent configuration is `NOT_CONFIGURED`, not a guess.

---

## Retention — stored, not enforced

`retentionPolicy` (`NEVER` / `DAYS_30` / `DAYS_90` / `DAYS_180` / `CUSTOM`) and `retentionDays` are persisted and
editable. **Nothing deletes files on a schedule.**

That is deliberate, and the UI says so. A retention setting that silently deletes files is the single most
destructive thing this module could do; shipping the schema first means the day a sweeper is written it acts on
policies administrators actually chose, rather than on a default nobody reviewed. Archiving a version does not
delete its files either.
