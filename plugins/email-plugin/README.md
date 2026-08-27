# Email Plugin

Sends email through any SMTP server, with the connection configured on the node.

There is already an email plugin on this platform; it talks to one provider's API. This one talks to whatever
SMTP server an operator has credentials for — their own relay, their company's Microsoft 365 tenant, a regional
SES endpoint — so host, port, security and authentication are node configuration rather than deployment
constants.

- **Node type:** `EMAIL_SEND`
- **Operations:** `SEND`, `TEST_CONNECTION`
- **Artifact:** `email-plugin-1.0.1.jar`
- **Runtime:** Java 17, Workflow Plugin SDK 1.0.0

---

## Build

```bash
mvn -pl plugins/email-plugin -am install
```

The archive bundles its own mail library under `lib/`:

```
email-plugin-1.0.1.jar
├── META-INF/MANIFEST.MF            Workflow-Plugin-Class / -Id / -Version / -Api-Version
├── META-INF/workflow-plugin.json   node definition and configuration schema
├── com/orchpilot/workflow/plugins/email/*.class
└── lib/
    ├── jakarta.mail-2.0.3.jar          Angus Mail — the Jakarta Mail implementation
    ├── angus-activation-2.0.2.jar
    └── jakarta.activation-api-2.1.4.jar
```

The engine extracts `lib/` into this plugin version's own class loader, so the mail library is isolated to this
plugin: another plugin may bundle a different version of it without either being affected, and the engine
itself never gains a mail dependency.

## Install

Upload the JAR to the Plugin Registry and install it from the marketplace, or install it directly:

```bash
curl -X POST http://localhost:8080/api/plugins/email/versions/1.0.1/install \
  -H "Authorization: Bearer $TOKEN"
```

---

## Configuration

| Field | Required | Notes |
| --- | --- | --- |
| `operation` | | `SEND` (default) or `TEST_CONNECTION`. |
| `provider` | | `CUSTOM`, `GMAIL`, `MICROSOFT365`, `YAHOO`, `ZOHO`, `SENDGRID`, `AMAZON_SES`, `MAILGUN`. Fills in the three fields below; never overwrites one you set. |
| `smtpHost` | yes | e.g. `smtp.company.com`. Supports variables. |
| `smtpPort` | | 1–65535. Default 587. |
| `security` | | `NONE`, `STARTTLS` (default), `SSL_TLS`. |
| `authenticationRequired` | | Default `true`. |
| `username` | when authenticating | Usually the mailbox address. Supports variables. |
| `passwordSecret` | when authenticating | The **name** of a secret. See below. |
| `credentialId` | | Alternative to `passwordSecret`: names a credential stored as `<id>.username` and `<id>.password`. |
| `fromEmail` | yes | The server decides whether you may use it. |
| `fromName` | | Shown beside the address. |
| `replyTo` | | |
| `to` | yes | A list, or one string of addresses separated by commas or semicolons. |
| `cc`, `bcc` | | Same shape as `to`. |
| `subject` | yes | Supports variables. |
| `bodyType` | | `TEXT` (default) or `HTML`. |
| `body` | yes | Supports variables. |
| `attachments` | | See *Attachments*. |
| `connectionTimeoutMillis` | | Default 15000. |
| `readTimeoutMillis` | | Default 30000. Also used as the write timeout. |

### Variables

Every text field goes through the engine's own variable resolver — there is one variable system on this
platform and it belongs to the engine. `${customer.email}`, `Order ${order.id} approved` and
`Hello ${customer.name}` all work, in any field in the table above.

A single variable may expand to several addresses: `${approvers.emails}` holding
`a@example.com, b@example.com` becomes two recipients, because splitting happens after resolution.

### The password

A password is never written into a workflow. Three ways to supply one, in order of preference:

1. **`credentialId`** — one name in the workflow, both values in the secret store as `<id>.username` and
   `<id>.password`.
2. **`passwordSecret`** — the name of a secret holding the password.
3. **`password: "${secret.SMTP_PASSWORD}"`** — a reference the engine expands. The workflow holds the
   reference, never the value.

A **literal** password in the `password` field is refused with `SMTP_CONFIGURATION_INVALID`. It would work,
which is exactly why it is refused: it would then be readable by anybody who can read the workflow, would
travel in every export of it, and would sit in its version history forever. Encrypting the field would not fix
any of that.

The resolved value is held for the length of one send, is registered for log redaction by the engine's secret
provider, and appears in no log line, no error message and no output variable.

### Attachments

```json
{ "fileName": "invoice.pdf", "source": "BASE64", "value": "${invoice.pdfBase64}", "contentType": "application/pdf" }
```

| Source | Behaviour |
| --- | --- |
| `VARIABLE` | The variable's content. Long, correctly-padded base64 is decoded; anything else is attached as UTF-8 text. |
| `BASE64` | Decoded. |
| `WORKFLOW_FILE`, `OBJECT_STORAGE` | Declared so a configuration naming them is understood, and **refused at execution** with a message saying so — fetching one needs an engine capability the SDK does not expose. Better than silently sending an empty file. |

**There is no filesystem-path source, deliberately.** A node that could attach `/etc/passwd` — or a path
assembled from a workflow variable somebody else controls — turns "send an email" into "read any file the
engine can read and post it off site".

---

## Testing a configuration

`operation: TEST_CONNECTION` performs the whole connection — DNS, TCP, TLS, AUTH — and disconnects without
sending anything. It exists because the alternative way to test SMTP settings is to send real mail to a real
person.

It returns `email.connectionVerified`, `email.host`, `email.port` and `email.security`, and validates only what
a connection needs, so settings can be proven before the message is written.

> The spec asked for `POST /api/plugins/email/test-connection` and `.../test-email` as REST endpoints. A plugin
> contributes node types, not HTTP endpoints — the SDK's `PluginContext` deliberately exposes no way to
> register a controller, which is what stops a plugin from adding unauthenticated surface to the engine. The
> capability is delivered as this operation instead: the designer can run the node on its own and see the
> result, and a workflow can test its own connection before sending.

---

## Outputs

| Variable | Set by | |
| --- | --- | --- |
| `email.success` | both | |
| `email.messageId` | `SEND` | The `Message-ID` the server received. |
| `email.sentAt` | `SEND` | ISO-8601. |
| `email.recipientCount` | `SEND` | `to` + `cc` + `bcc`. |
| `email.connectionVerified` | `TEST_CONNECTION` | |
| `email.host`, `email.port`, `email.security` | `TEST_CONNECTION` | |

## Errors, and what is retried

The node declares `idempotent: false`, so the engine replays a recorded success rather than sending a second
copy after a retry or a restart.

| Code | Retried | |
| --- | --- | --- |
| `SMTP_CONFIGURATION_INVALID` | no | Every problem is reported at once, not one per run. |
| `SMTP_AUTHENTICATION_FAILED` | **no** | Repeating a rejected password is how an account gets locked out. |
| `SMTP_CONNECTION_FAILED` | yes, unless the host does not resolve | A name that does not resolve will not resolve on the next attempt. |
| `SMTP_TIMEOUT` | yes | |
| `SMTP_TEMPORARY_FAILURE` | yes | A 4xx reply. Greylisting arrives this way and works by expecting a retry. |
| `RECIPIENT_REJECTED` | no | A 5xx address refusal. |
| `SMTP_TLS_FAILED`, `SMTP_SSL_FAILED` | no | Almost always a port that does not match the security mode. |
| `MESSAGE_TOO_LARGE` | no | |
| `EMAIL_SEND_FAILED` | no | |

SMTP says this itself and says it clearly: 4xx is temporary, 5xx is permanent. Where a reply code can be found
it decides — including on the chained exception, which is where a refused recipient's code actually arrives.

## What is never logged

The password, the credentials, the message body, and the attachments. A successful send logs the host, the
message id and the recipient count. Not the recipients: a log line is read by more people than the email was
addressed to.

Failures log the code and a sentence. Any message that looks like it echoes an AUTH exchange is replaced rather
than carried into an execution record.

---

## Security notes

- **Sender verification is the server's job.** SPF, DKIM, DMARC and the provider's own rules decide whether you
  may send as an address. A client cannot grant itself permission, and a node that appeared to offer that would
  be offering to forge mail.
- **STARTTLS is set `required` as well as `enable`.** With only the latter, a server that fails to offer
  STARTTLS causes a silent fall back to sending the password in clear.
- **Certificates are checked against the host** (`ssl.checkserveridentity`). Encryption without that leaves the
  connection open to anything able to answer for the address.
- **This plugin opens SMTP sockets directly through Jakarta Mail.** The engine's allowed-hosts guard applies to
  its HTTP client and does not constrain those connections. Granting this plugin to an operator grants the
  ability to reach an arbitrary host and port on the network the engine sits in — which is what an SMTP node
  fundamentally is, and is stated in the plugin manifest so the decision is made knowingly.
- **No session pooling.** Each send opens a session, uses it and closes the transport. A pool keyed on the
  connection would have to be invalidated when a password rotates, would hold an authenticated connection open
  to a third party between executions, and would let one workflow's settings serve another's send if the key
  were ever wrong.

---

## Provider notes

| Provider | Host | Port | Security | Username |
| --- | --- | --- | --- | --- |
| Gmail | `smtp.gmail.com` | 587 | STARTTLS | the mailbox address; the password must be an **app password** (2FA required) |
| Microsoft 365 | `smtp.office365.com` | 587 | STARTTLS | the mailbox address; SMTP AUTH must be enabled on the mailbox |
| Yahoo | `smtp.mail.yahoo.com` | 587 | STARTTLS | app password |
| Zoho | `smtp.zoho.com` | 587 | STARTTLS | region-dependent — `.eu`, `.in`, `.com.au` |
| SendGrid | `smtp.sendgrid.net` | 587 | STARTTLS | the literal `apikey`; the API key is the password |
| Amazon SES | *none — regional* | 587 | STARTTLS | SES **SMTP** credentials, not an AWS access key |
| Mailgun | `smtp.mailgun.org` | 587 | STARTTLS | `postmaster@your-domain` |

Amazon SES supplies no host on purpose: its endpoints are regional
(`email-smtp.eu-west-1.amazonaws.com` and a dozen others), and defaulting to one would send from the wrong
place for everybody else.

Working configurations for each are in [`examples/`](examples/).

---

## Tests

```bash
mvn -pl plugins/email-plugin test
```

67 tests. The integration tests run against a real SMTP server implemented in the test sources
(`FakeSmtpServer`): a socket, a greeting, EHLO, AUTH, MAIL FROM, RCPT TO, DATA. Mocking `Transport` would test
that Jakarta Mail's API was called, which is the part least likely to be wrong; what is worth proving is that a
message this plugin builds is one a server accepts, that authentication happens, and that a rejection comes
back classified correctly.

One test sends through a real provider and is skipped unless the environment names one:

```bash
EMAIL_PLUGIN_SMTP_HOST=smtp.gmail.com \
EMAIL_PLUGIN_SMTP_USERNAME=you@gmail.com \
EMAIL_PLUGIN_SMTP_PASSWORD='your app password' \
EMAIL_PLUGIN_SMTP_FROM=you@gmail.com \
EMAIL_PLUGIN_SMTP_TO=you@gmail.com \
mvn -pl plugins/email-plugin test
```
