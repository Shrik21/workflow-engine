# Workflow Import / Export (OrchPilot `.orchpilot` format)

Move a workflow between environments as a single encrypted file. The file is a proprietary binary container
with the extension `.orchpilot`; opened in a text editor it shows a `ORCHPILOT` signature and then ciphertext —
no workflow JSON, no node names, no structure.

This document describes what the file contains, how it is protected, the API and UI, how to configure it, and
what is deliberately left as a seam for later.

## What is exported — and what can never be

The workflow's **definition** is packaged: its node graph, connections, triggers, and — at the exporter's
choice — variables, referenced form definitions, the plugin id/version list, and the access-group names.

**No secret is ever exported.** This is a property of the platform's design, not a scrub applied at export
time: a workflow definition holds only a *reference* to a credential (a secret name or a credential id the
plugin resolves at run time), never the credential value, which lives in the secret store. On top of that, the
export pulls those references into an explicit `credentialReferences` list — `{nodeId, field, type, name}` — so
the person importing is shown exactly what they must map to their own credentials afterwards. There is no
"include credentials" option, by design.

## How the file is protected

AES-256-GCM authenticated encryption. Every export uses a **fresh random 256-bit content key** and a **fresh
96-bit nonce**; the GCM tag means any change to the bytes is detected on import rather than silently accepted.
No XOR, no base64-as-encryption, no reversible custom scheme.

Two modes:

| Mode | Key handling | Portable to |
| --- | --- | --- |
| **Platform** (default) | The content key is wrapped (encrypted) under the engine's configured master key — envelope encryption — and the wrapped key travels in the file. | Only an environment configured with the same master key. |
| **Password** | The content key is derived from the operator's password with **Argon2id** (t=3, m=64 MiB, p=1). Only the salt and Argon2 parameters travel; the password and the derived key never do. | Anywhere, given the password. |

Neither the password nor any derived key is ever stored or logged. A wrong password, a tampered file, a
corrupt file, and a file made for a different environment's master key all fail with the same non-specific
message — the difference between them is itself information an attacker could use.

### File layout

```
magic             9 bytes   "ORCHPILOT"
formatVersion     1 byte    container version (1)
encryptionVersion 1 byte    crypto scheme (1 = AES-256-GCM)
mode              1 byte    1 = platform-wrapped, 2 = password-derived
keyMeta           u16 + N   JSON: wrapped key + wrap nonce (platform), or salt + Argon2 params (password)
nonce             u8  + N   the payload GCM nonce
ciphertext        u32 + N   AES-256-GCM ciphertext, tag appended
checksum          32 bytes  SHA-256 of everything above
```

The checksum is a cheap structural guard so a truncated or edited header is caught before any crypto runs; the
GCM tag is the actual security boundary and is verified on decrypt regardless. Every length prefix is bounded
as it is read, so a crafted file claiming a huge block is rejected at the length, not after an
`OutOfMemoryError`.

## Importing is safe by construction

The uploaded file is treated as hostile until the authenticated decrypt proves otherwise. Import always:

- **Lands in the caller's own tenant.** The tenant recorded in the file is provenance only and is ignored; the
  workflow is created through the normal create path, which stamps ownership and tenant from the authenticated
  user. A crafted file claiming another tenant's id cannot place a workflow there.
- **Regenerates every id.** New workflow id, new node ids (with all edges, compensation targets, and other
  references rewritten to match), and referenced forms re-created with new ids and the nodes repointed at them.
  Nothing reuses an existing MongoDB id, so an import can never attach to or overwrite an existing document.
- **Never overwrites.** An existing workflow of the same origin is reported as a conflict in the preview; the
  import still creates a separate new draft.
- **Does not deserialize arbitrary Java types.** The payload is read with a strictly-typed Jackson mapper with
  default typing off, so no class named in the document is ever loaded (the deserialization-gadget defence).
- **Does not auto-install or downgrade plugins, and does not grant permissions.** Missing/out-of-date plugins
  and referenced access groups are surfaced for the operator to act on; a newer installed plugin is never
  flagged, and a downgrade is never proposed.

Validation runs first and writes nothing: it decrypts, checks, and returns a preview (workflow info, plugin
compatibility, credential references, access groups, conflict, warnings). A second, deliberate action imports.

## REST API

All under `/api/workflows`. The actor comes from the standard actor header; export takes the same view
permission as the workflow.

| Method & path | Body | Returns |
| --- | --- | --- |
| `POST /{id}/export` | JSON `ExportRequest` (`includeForms`, `includeVariables`, `includePluginDependencies`, `includePermissions`, `encryptionMode`, `password`) | `application/octet-stream` with `Content-Disposition` filename |
| `POST /import/validate` | multipart: `file`, optional `password` | `ImportValidationResult` preview |
| `POST /import` | multipart: `file`, optional `password` | `ImportResult` (new workflow id + warnings) |

A tampered, corrupt, wrong-password, or unsupported file returns **400** (`IMPORT_FILE_INVALID`) with a
non-specific message; a file above the multipart limit returns **413**.

Every action is audited: `WORKFLOW_EXPORT`, `WORKFLOW_IMPORT`, `WORKFLOW_IMPORT_VALIDATED`,
`WORKFLOW_IMPORT_FAILED`. No key, password, decrypted payload, or credential value is ever logged.

## Console

- **Workflows list → Export** (per row): choose what to include and the protection mode, then download the
  `.orchpilot`. The password mode shows a confirm field and a "there is no recovery" note.
- **Workflows list → Import** (toolbar, needs `WORKFLOW_CREATE`): a wizard — upload & optional password →
  preview (plugins, credentials to map, access groups, any conflict) → import → done, with "Open in designer".

## Configuration

```yaml
workflow:
  engine:
    secrets:
      master-key: ${WORKFLOW_SECRETS_KEY:...}   # base64 128/192/256-bit; required for platform-mode export/import
    import-export:
      max-file-bytes: ${WORKFLOW_IMPORT_MAX_FILE_BYTES:52428800}   # 50 MB; enforced before decryption
spring:
  servlet:
    multipart:
      max-file-size: 64MB   # outer HTTP guard; keep above import-export.max-file-bytes
```

Platform-mode export/import needs the master key configured; without it, use password mode (platform-mode
export fails fast with a clear message).

## Seams left for later

These are intentionally scaffolded as clean extension points rather than implemented, and are called out here
so nothing is mistaken for finished:

- **KMS / external key management.** Platform mode wraps and unwraps the content key through two methods in
  `PackageCrypto` (`wrapKey` / `unwrapKey`). Those two bodies are the entire surface an AWS KMS / Azure Key
  Vault / GCP KMS / Vault integration would replace; the payload encryption stays exactly as it is. Today they
  are local AES-GCM under the configured master key.
- **Digital signatures (Ed25519).** The format version and container are ready to carry a detached signature
  for provenance beyond the GCM tag; signing/verification is not wired.
- **Package versioning & migration.** `formatVersion` / `encryptionVersion` are checked on read and an
  unsupported version is refused; a forward-migration framework for older packages is not built.
- **Conflict modes beyond "create new".** The default and only implemented mode creates a new workflow, which
  satisfies "never overwrite". Replace-existing and import-as-new-version are surfaced as a detected conflict
  but not yet applied.
- **CLI.** Not included. If added, it must never accept a password as a shell argument.
- **Export-history metadata collection.** Exports are audited; a separate queryable export-history collection
  is not added.
