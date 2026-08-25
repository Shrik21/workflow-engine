# Security Architecture

Authentication and authorization for the workflow platform. Spring Security 7 · Argon2id · JWT
(Nimbus) · rotating refresh tokens · MongoDB.

---

## 1. The one rule that shapes everything

**Passwords are hashed, never encrypted.** There is no code path in this platform that can turn a
stored credential back into a password, because no such capability exists: `Argon2PasswordEncoder`
computes a one-way digest and `matches()` re-computes it. The `users` collection holds
`passwordHash` and nothing else resembling a credential.

Reversible encryption exists in the platform, but only for data that genuinely must be read back:
SendGrid API keys, bearer tokens, database passwords. That lives behind
`SecretEncryptionService` (AES-256-GCM) and is a deliberately separate mechanism with a separate
key. Conflating the two is the single most common authentication defect, so they share no code.

| | Passwords | Secrets |
|---|---|---|
| Mechanism | Argon2id hash | AES-256-GCM |
| Reversible | No, by construction | Yes, that is the point |
| Key | None, the hash is self-describing | `APP_ENCRYPTION_KEY` |
| Read back | Impossible | By a scoped, audited plugin call |

### Why Argon2id

Argon2id is memory-hard: an attacker with a GPU or ASIC gains far less than against BCrypt, whose
4 KB working set fits trivially in silicon. It won the Password Hashing Competition and is the
current OWASP first choice.

The encoder is a `DelegatingPasswordEncoder` with `argon2` as the default and `bcrypt` retained for
verification. That is not hedging: it means a database migrated from a BCrypt system keeps working,
and every successful login on a legacy hash silently re-hashes to Argon2id. A single-algorithm
encoder would force a password reset for every existing user.

Parameters: 16-byte salt, 32-byte hash, 1 lane, 19 MiB memory, 2 iterations. These are the
OWASP-recommended figures and are configurable, because the right cost depends on the hardware the
engine runs on and on how many logins per second it must absorb.

---

## 2. HS256 versus RS256, and what to run in production

Both implemented; the choice is `security.jwt.algorithm`.

**HS256** is a MAC. One secret both signs and verifies, so anything able to verify a token is also
able to mint one. Fine while a single service issues and consumes its own tokens: it is faster, the
key is a string, and there is nothing to distribute.

**RS256** is a signature. The private key signs, and the public key only verifies. A second service
can validate a token without holding anything that lets it forge one, and the public half can be
published at a JWKS endpoint and rotated without redeploying consumers.

The distinction only matters once a token crosses a trust boundary:

| | HS256 | RS256 |
|---|---|---|
| Verifier can forge tokens | Yes | No |
| Key distribution | Copy the secret everywhere | Publish the public key |
| Rotation | Coordinated restart | Add a key to the JWKS |
| Speed | Faster | Slower to sign, fast to verify |

**Recommendation.** HS256 is the default and is correct for the single-service deployment this
platform ships as. The moment a second service validates these tokens, switch to RS256: the
platform then exposes `GET /.well-known/jwks.json`, and other services verify against it while the
private key never leaves the auth service. This is why `JwtKeyProvider` is an interface with two
implementations rather than a hardcoded `Mac` object; changing algorithm is configuration, not a
rewrite.

Never `alg: none`, and never let the token's own header choose the algorithm: the decoder is pinned
to the configured one, which is what defeats algorithm-confusion attacks.

---

## 3. Tokens

```
Access token   JWT, 15 minutes, held in Angular memory only
Refresh token  opaque 256-bit random, 7 days, HttpOnly cookie, hash stored server-side
```

The access token is a JWT because it must be verifiable without a database round trip on every
request. The refresh token is deliberately **not** a JWT: it is a random opaque string, because its
whole purpose is to be revocable, and revocation needs server state anyway. Making it a JWT would
add signature verification without removing the database lookup.

**Claims carry only** `sub` (user id), `username`, `roles`, `iat`, `exp`, `iss`, `jti`. No email, no
permissions list, no secrets. Permissions are derived from roles at request time rather than
embedded, so revoking a permission takes effect on the next request instead of waiting out the
token's lifetime.

**Storage.** Access token in memory, lost on refresh of the page, which is the point: it is not
readable by injected script from `localStorage` because it is not there. Refresh token in an
HttpOnly, SameSite=Strict, path-scoped cookie, so script cannot read it at all and CSRF cannot aim
it anywhere but the refresh endpoint. The cookie is `Secure` whenever the request arrives over
HTTPS.

That works because the console and the API are same-origin in both shipped deployments (dev-server
proxy, and nginx in Docker). **The tradeoff, stated plainly:** if you serve the console from a
different origin, `SameSite=Strict` stops sending the cookie and you must either move to
`SameSite=None; Secure` over HTTPS, or set `security.jwt.refresh-token-transport: body` and accept
that the refresh token now lives in JavaScript's reach. The body mode exists for non-browser
clients and is the documented fallback, not the default.

**Rotation.** Every refresh consumes the presented token and issues a new one:

```
POST /api/auth/refresh  (cookie)
   → hash, look up, check not revoked and not expired
   → revoke the presented token
   → issue a new access + refresh pair
```

Presenting an already-revoked token is treated as theft, not as a mistake: the entire token family
for that user is revoked and `TOKEN_REUSE_DETECTED` is audited. Without that, a stolen token stays
usable in parallel with the legitimate one for its full lifetime.

Only a SHA-256 hash of the token is stored. A dump of `refresh_tokens` therefore yields nothing
usable, exactly as for passwords.

---

## 4. Roles and permissions

Authorization is on **permissions**; roles are bundles of them. That indirection is what lets new
roles arrive without touching a single check.

```java
enum Permission { WORKFLOW_VIEW, WORKFLOW_CREATE, ..., USER_DELETE }
enum Role { ADMIN(all), USER(view/create/edit/execute + execution view) }
```

Every endpoint asserts a permission, never a role:

```java
@PreAuthorize("hasAuthority('PLUGIN_UPLOAD')")
```

Adding `WORKFLOW_EDITOR` later is one enum constant with a permission set. Had the checks said
`hasRole('ADMIN')`, every one of them would need revisiting. Roles are still granted as
`ROLE_ADMIN` authorities alongside permissions, so `hasRole` keeps working where a coarse check is
genuinely what is meant.

`USER` gets no user management, no plugin management, no settings.

**Ownership.** A `USER` may edit and delete only workflows they own; `ADMIN` reaches everything.
This is enforced in the service layer, not the controller, because execution and publication reach
workflows through paths a controller annotation does not cover.

**Tenant-ready.** `User` and `Workflow` both carry a nullable `tenantId`, and ownership checks go
through one `WorkflowAccessPolicy` class. Adding tenant isolation later means adding a predicate
there, not auditing every query.

---

## 5. Request flow

```
Angular ──Authorization: Bearer <jwt>──► JwtAuthenticationFilter
                                              │ validate signature, iss, exp, alg
                                              │ load user, check enabled/locked
                                              ▼
                                        SecurityContext (authorities = roles + permissions)
                                              │
                                              ▼
                                   @PreAuthorize on the controller
                                        ┌─────┴─────┐
                                     allowed      denied → 403 with a generic message
                                        │
                                        ▼
                                    WorkflowExecutionContext.user
                                        │
                                        ▼
                                    NodeExecutionContext.currentUser()
                                        (userId, username, roles only)
```

A plugin receives `userId`, `username` and `roles`. It never receives the JWT, the refresh token or
the password hash, because `WorkflowUser` has no field that could hold them. A plugin that wants to
act as the user cannot borrow their credentials.

Stateless throughout: `SessionCreationPolicy.STATELESS`, no `JSESSIONID`, no server-side session.

---

## 6. Login hardening

- **Generic failures.** Wrong password, unknown user, disabled account and locked account all
  answer `401` with `Invalid username or password`. Distinguishing them turns the login form into a
  user-enumeration oracle. The audit log records the real reason; the response does not.
- **Constant work for unknown users.** A missing user still runs a dummy Argon2 verification, so
  response timing does not disclose whether the account exists.
- **Throttling.** Failures are counted per username and per IP. Five failures inside the window
  locks that identity for fifteen minutes, both configurable. Counting per IP as well stops one
  attacker spraying many usernames from one host; counting per username stops a distributed attack
  on one account.
- **Lockout is temporary and separate** from the administrative `accountLocked` flag, so an attack
  cannot permanently deny a real user access.

---

## 7. Audit

`security_audit_logs` records `LOGIN_SUCCESS`, `LOGIN_FAILURE`, `LOGOUT`, `TOKEN_REFRESH`,
`TOKEN_REUSE_DETECTED`, `PASSWORD_CHANGED`, `USER_CREATED`, `USER_UPDATED`, `USER_DISABLED`,
`ROLE_CHANGED`, `ACCOUNT_LOCKED`, `ACCESS_DENIED`, with actor, subject, IP, user agent and outcome.

It never records a password, a hash, a JWT, a refresh token or a secret. The audit writer takes a
typed event rather than a free-text message, so there is no parameter into which a credential could
be passed by accident.

---

## 8. Collections and indexes

| Collection | Indexes |
|---|---|
| `users` | unique `username`, unique `email`, `tenantId` |
| `refresh_tokens` | unique `tokenHash`, `userId`, TTL on `expiresAt` |
| `login_attempts` | `identifier`, TTL on `expiresAt` |
| `security_audit_logs` | `userId`, `at`, `event` |

TTL indexes mean expired refresh tokens and stale throttle counters are reclaimed by MongoDB rather
than by a cleanup job.

---

## 9. What replaces the admin API key

The previous `X-Admin-Api-Key` header is **removed**. It was a single shared secret with no
identity, no expiry and no audit trail, which is exactly what this system replaces. Plugin and
secret endpoints now require the `PLUGIN_*` and `USER_*` permissions of an authenticated principal.

Machine callers authenticate as a user: create a service account, give it only the permissions it
needs, and have it call `/api/auth/login`. That yields an identity in the audit log, a revocable
credential and a short-lived token, none of which the shared key provided.

---

## 10. Bootstrap

No default credentials ship. With `app.bootstrap-admin.enabled=true` and username, email and
password supplied from the environment, startup creates one ADMIN **only if no ADMIN exists**. The
password is hashed immediately and the plaintext is never written anywhere, including the log, which
reports only that an admin was created. Leaving it disabled is the default; an installation with no
admin refuses nothing except administration, which is safer than a guessable account.

---

## 11. Headers and transport

`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy:
strict-origin-when-cross-origin`, a Content-Security-Policy with no `unsafe-eval`, and HSTS when
served over HTTPS. CORS lists exact origins from configuration; a wildcard is refused rather than
honoured, because these endpoints install code and read credentials.
