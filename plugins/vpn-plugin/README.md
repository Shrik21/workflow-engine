# Secure Network / VPN Plugin

Manages and reports secure-network (VPN) connection state through provider control-plane APIs, with a pluggable
provider architecture.

- **Plugin id:** `vpn` · **Node type:** `VPN` · **Version:** 1.0.0 · **Category:** NETWORK
- **Providers:** AWS, Azure, GCP, generic IPsec, OpenVPN, WireGuard
- **Artifact:** `vpn-plugin-1.0.0.jar` — no bundled dependencies

---

## What this plugin is, honestly

It is a **control-plane** plugin. It manages and reports the *state and configuration* of VPN connections
through each provider's API. It does **not**:

- **dial a tunnel up.** A cloud Site-to-Site VPN is always-on infrastructure with no "connect" API — its
  tunnels come up when the customer gateway negotiates IKE. A host tunnel (OpenVPN/WireGuard/IPsec) is brought
  up by a client that needs root and a kernel module, which a workflow node inside a shared JVM must not run.
- **claim connectivity it did not verify.** Every result says what was checked. A status read of the control
  plane is reported as exactly that, never as a data-plane reachability test.

This is not a limitation worked around — it is what the spec asked for ("do not implement fake connect/
disconnect", "do not falsely report network connectivity"). `Connect` converges and reports; statuses map from
real provider states; a test states its method.

**Cloud calls go through the engine's `PluginHttpClient`**, not a bundled cloud SDK. That keeps the plugin
small and offline-installable, and — crucially — binds it to the plugin's **allowed hosts**: grant
`ec2.ap-south-1.amazonaws.com` (or your endpoint) via *Plugins → the version → Edit*, or a cloud call is
refused with a message telling you to.

---

## The provider architecture

One node, `VPN`, dispatches to a `VpnProvider` chosen by the `provider` field. The engine never sees the SPI —
it sees the node. **A new provider is a class implementing `VpnProvider` and one line in
`VpnProviderRegistry`**; nothing in the engine, the node, the schema or the designer changes. That is the
"additional providers without modifying the engine" requirement, made concrete.

```java
public interface VpnProvider {
    VpnConnectionResult      connect(VpnConnectionRequest r);
    VpnConnectionResult      disconnect(VpnConnectionRequest r);
    VpnConnectionStatus      getStatus(VpnConnectionRequest r);
    VpnConnectionTestResult  testConnection(VpnConnectionRequest r);
    VpnConnectionInfo        getConnectionInfo(VpnConnectionRequest r);
}
```

---

## Operations

One node with an operation selector — not ten node types — because the operations share a provider, a
connection and a credential.

| Operation | Meaning |
| --- | --- |
| `CONNECT` | Converge / ensure, and report state. For cloud providers this reports tunnel state (there is no dial); the message says so. |
| `DISCONNECT` | Tear down where the provider supports it. AWS/Azure/GCP **refuse** it rather than fake a success — stopping a cloud connection means deleting it. |
| `STATUS` | Read the current standard status. |
| `TEST_CONNECTION` | Test, reporting exactly what was checked and any latency. |
| `GET_INFO` | Descriptive attributes — gateways, addresses, routes. Never a credential. |
| `WAIT_UNTIL_CONNECTED` | Poll `STATUS` until `CONNECTED`, or fail with `VPN_CONNECTION_TIMEOUT`. |

> The spec's advanced operations (Create, Delete, Rotate Credentials, Update Configuration) are a planned SPI
> extension: the given `VpnProvider` interface has no `create`/`delete` methods, so the node returns
> `VPN_UNSUPPORTED_OPERATION` for them rather than pretending. They are the clean next addition behind the
> same interface.

## Standard statuses

`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `DISCONNECTING`, `FAILED`, `UNKNOWN`, `TIMEOUT`. Each provider maps
its own words on. A decision node branches on `${vpnResult.status}` and never learns a provider's vocabulary.

**`UNKNOWN` is not `FAILED`.** FAILED means the provider said the connection is down. UNKNOWN means the state
could not be determined (an unrecognised value, a restricted credential). Collapsing them would make a
workflow tear down a healthy connection over a missing IAM permission.

---

## Providers

### AWS Site-to-Site VPN
Reads tunnel telemetry with `DescribeVpnConnections`, signed with **SigV4** (no SDK). A connection has two
tunnels; one `UP` is `CONNECTED`, both `DOWN` on an available connection is `FAILED`, `pending`/`deleting` map
accordingly. `CONNECT` reports and says AWS has no dial; `DISCONNECT` is refused (delete is destructive and
separate). **Credentials:** `accessKeyId`, `secretKey`, optional `sessionToken`. **Host:**
`ec2.<region>.amazonaws.com`.

### Azure VPN Gateway
Reads a connection through ARM (`Microsoft.Network/connections/{name}`). `connectionStatus` →
`Connected`/`Connecting`/`NotConnected`. **Credential:** `accessToken` (see below). **Host:**
`management.azure.com`.

### Google Cloud HA VPN
Reads a tunnel through Compute (`vpnTunnels/{tunnel}`). GCP's richer `status` — `ESTABLISHED`,
`FIRST_HANDSHAKE`, `AUTHORIZATION_ERROR`, `NO_INCOMING_PACKETS`, … — maps down without confusing in-flight for
failed. **Credential:** `accessToken`. **Host:** `compute.googleapis.com`.

> **Azure and GCP tokens are supplied, not minted here.** Both authenticate with a short-lived OAuth bearer
> token; minting one is a provider-specific exchange this plugin cannot exercise against a real tenant to know
> it is correct. So the token comes from the secret store under `accessToken`, using whatever your environment
> already runs to obtain tokens (the CLI, a sidecar, a workload-identity webhook). Minting tokens in-plugin is
> a focused addition behind the same interface when an installation needs it.

### Generic IPsec / OpenVPN / WireGuard
Validate configuration, resolve credentials from the secret store, and report **what can honestly be verified
from the engine**:

- **OpenVPN over TCP** — a real TCP socket to the server port. A successful connect is reported as *reachable*
  (`CONNECTING`), never `CONNECTED`: an open port is a precondition for a tunnel, not proof of one.
- **IPsec, WireGuard, OpenVPN over UDP** — UDP endpoints cannot be probed without bringing the tunnel up, so
  these validate the config, resolve the endpoint, and say plainly that the tunnel state is not observable.

To have the engine host actually hold a tunnel, bring it up with the host's own VPN client out of band and use
this provider to check it.

---

## Credentials

There is **no field on the node that holds a key**. A provider declares the credential names it needs; the
node resolves each from the secret store by one of two routes:

1. **Connection profile** — `connectionProfile: "aws-network-prod"` names a stored credential whose parts are
   the secrets `aws-network-prod.accessKeyId`, `aws-network-prod.secretKey`. *Select a profile instead of
   entering credentials on every node* — the spec's connection profiles.
2. **Explicit map** — `credentialSecrets: { "accessKeyId": "aws.key", "secretKey": "aws.secret" }` when the
   secrets are not grouped under one profile.

Because a raw credential can be written nowhere, there is nothing to redact: the value exists only inside one
execution's request, and every provider is contracted never to log, return or embed it.

| Provider | Credential names |
| --- | --- |
| AWS | `accessKeyId`, `secretKey`, `sessionToken` (optional) |
| Azure, GCP | `accessToken` |
| IPsec | `presharedKey` (or `certificate`, `privateKey`) |
| OpenVPN | `caCertificate`, `clientCertificate`, `clientKey`, `username`, `password` |
| WireGuard | `privateKey`, `presharedKey` (optional) |

---

## Workflow variables

Inputs resolve anywhere: `${cloud.region}`, `${vpn.connectionId}`, `${workflow.environment}`.

Outputs are published nested under `outputVariable` (default `vpnResult`), never as dotted keys — a dotted key
cannot be persisted into the execution document. `${vpnResult.status}`, `${vpnResult.connectionId}`,
`${vpnResult.provider}`, `${vpnResult.message}`, plus `success` at the top level for a decision node.

```json
"outputMapping": { "vpnResult.status": "workflow.vpnStatus" }
```

## Wait until connected

`WAIT_UNTIL_CONNECTED` polls `STATUS` every `pollIntervalSeconds` until `CONNECTED`, honouring cancellation. A
`FAILED` status ends the wait at once — no point polling a connection already called down. On `timeoutSeconds`
the node fails with **`VPN_CONNECTION_TIMEOUT`** and still publishes the last state, so an error branch can act
on it.

## Errors

`VPN_CONFIGURATION_INVALID`, `VPN_UNKNOWN_PROVIDER`, `VPN_UNSUPPORTED_OPERATION`, `VPN_CREDENTIAL_MISSING`,
`VPN_CONNECTION_FAILED`, `VPN_CONNECTION_TIMEOUT`, `VPN_AUTHENTICATION_ERROR`, `VPN_PROVIDER_ERROR`. Each pairs
with the engine's retry and error policies; a 5xx or 429 from a cloud is retryable, an auth failure is not.

---

## Build, install, test

```bash
mvn -pl plugins/vpn-plugin -am install
```

Upload `vpn-plugin-1.0.0.jar` to the registry and publish, or install directly. Then **grant the cloud host**
you use in *Plugins → the version → Edit → Allowed hosts* (e.g. `ec2.ap-south-1.amazonaws.com`) — a cloud call
to an ungranted host is refused with a message saying so.

```bash
mvn -pl plugins/vpn-plugin test        # 35 tests
```

The tests cover the SigV4 crypto against FIPS/RFC vectors, the provider status mappings against canned
control-plane responses, OpenVPN-over-TCP reachability against a real local socket, and the node's dispatch,
credential resolution, output nesting and wait/timeout against a fake provider. No test reaches a real cloud —
none could here, and a live-account test would add nothing to the mappings it verifies.

[`examples/`](examples/) holds a VPN → wait → decision → (deploy | email) workflow.

## Security notes

- Credentials come only from the secret store; nothing holds a raw key.
- Cloud calls go through the engine's HTTP client and are bound by the allowed-hosts list.
- The generic providers open at most a TCP reachability socket (OpenVPN/TCP); that socket is not bound by the
  allowed-hosts guard, which covers the HTTP client — stated in the manifest.
- This plugin runs no privileged VPN client and brings no tunnel up.
- The AWS XML parser is configured to refuse DTDs and external entities: a response parser that resolved them
  would be an SSRF and file-read primitive.
