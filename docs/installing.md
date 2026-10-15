# Installing & Configuring — VCF Content Factory UniFi Controller

## Prerequisites

- VCF Operations 8.x or 9.x (collect-path discovery is validated on
  9.0.2).
- A UniFi controller — a UniFi OS console (e.g. UDM / UDM-Pro / Cloud Key)
  or a self-hosted Network controller — reachable from the VCF Operations
  collector on **TCP 443** (HTTPS).
- A **UniFi controller account** with access to the **Network API** (and
  the **Protect API**, if you want NVR / camera objects).
- For Protect objects, the **UniFi Protect** application must be installed
  and running on the console.

## Permissions Required

The controller account needs read access to the Network API (sites,
devices, ports, radios, statistics) and, for camera monitoring, read access
to the Protect API (NVR and camera state). A read-capable account is
sufficient — the adapter only reads; it makes no configuration changes on
the controller. The single write-shaped call is the login `POST` that
establishes the session.

> **Session note:** the adapter authenticates with **session-cookie auth**.
> It logs in once (`POST /api/auth/login`) to obtain the `TOKEN` session
> cookie and presents that cookie on every subsequent read GET. When the
> controller expires the session, the adapter detects the resulting
> 401/403, re-logs-in, and retries automatically (see Notable Behaviors in
> the overview). The session cookie and login credentials are redacted from
> all logs and error messages.

## Network Requirements

| Port | Protocol | Direction | Purpose |
|------|----------|-----------|---------|
| 443  | HTTPS    | Collector → UniFi controller | UniFi Network API + Protect API reads, and the login POST |

The optional LLDP → HostSystem cross-link uses the **ambient** local Suite
API on the collector and requires no additional outbound network
configuration.

## TLS — certificate trust

UniFi consoles commonly present a self-signed certificate on 443. By
default (`allowInsecure` is `false`) the adapter validates the controller's
certificate chain through the VCF Operations platform trust manager. A
chain is trusted when it is issued by a CA in the VCF Operations trust
store, or when it is a certificate you accepted for this instance.

When you click **Validate Connection** (build 14 and later):

1. **The certificate is already trusted** (issued by a CA the VCF
   Operations trust store holds): validation goes straight on to the
   connection test. No dialog.
2. **The certificate is not trusted** (the console's factory self-signed
   certificate, or a private CA VCF Operations does not know): VCF
   Operations shows its **Review and Accept Certificate** dialog with the
   controller certificate. Check the details (issuer, validity,
   thumbprint against what the console shows) and accept it. Validation
   then runs the connection test. The accepted certificate is stored on
   this instance and trusted on every collection cycle; validation stays
   on.

Enter the console by a name its certificate carries (a DNS name in its
subject alternative names, a wildcard included); when it is entered by an
IP address the certificate does not list, the review dialog is unproven
and Allow Insecure SSL may be the only path (tracked in the fork issue #33
follow-up).

If the console's certificate changes later (renewed or replaced),
collection fails with a certificate error. Open the instance, click
**Validate Connection** and accept the new certificate.

Setting **`allowInsecure=true`** turns certificate validation off, so the
controller credentials and session token go to whatever answers at that
address, and no certificate is offered for review. A self-signed console
does not need it: accept its certificate once as above instead. While it
is on, the adapter logs a WARN each time the instance is configured.

Upgrading from build 13 or earlier: existing instances keep their stored
`allowInsecure` value. An instance created with the old default stores
`true` and keeps skipping validation until you edit it to `false`. Only
the exact value `true` (any letter case) turns validation off; anything
else, including a blank value, validates.

## Configuration Fields

When adding a new adapter instance in VCF Operations, you will be prompted
for:

| Field | Key | Required | Default | Notes |
|-------|-----|----------|---------|-------|
| Host / IP Address | `host` | Yes | — | Controller hostname or IP. |
| Port (HTTPS) | `port` | No | 443 | Controller HTTPS port. |
| Allow Insecure SSL | `allowInsecure` | No | false | `true` turns off certificate validation for the controller (lab use only). Any other value validates. |
| Username | `username` | Yes | — | UniFi controller account with Network (and Protect) API access. |
| Password | `password` | Yes | — | Controller account password (masked). |

## Step-by-Step Installation

1. Install the `.pak` file via **Administration > Solutions > Add**.
2. After installation, navigate to **Data Sources > Integrations > Accounts**.
3. Click **Add Account** and select **VCF Content Factory UniFi Controller**.
4. Fill in the configuration fields above (host, port `443`, controller
   credentials; leave `allowInsecure` at `false`).
5. Click **Validate Connection**. For a self-signed or private-CA console,
   VCF Operations shows the controller certificate for review: accept it
   (see TLS above). Then click **Add**.
6. The adapter discovers the site / device topology and begins collecting
   on the next cycle (default 5 minutes).

## Troubleshooting

- **Test Connection fails on TLS**: the controller certificate is
  untrusted and was not accepted (see TLS above). Click **Validate
  Connection** again and accept the certificate in the review dialog. Set
  `allowInsecure=true` only if you accept an unvalidated connection.
- **The instance recovered from a 401 on its own** — expected. A UniFi
  session expiry produces a transient 401/403; the adapter re-logs-in and
  retries automatically (build 5+).
- **Every UniFi object is ERROR after a password change** (build 14+).
  The adapter log has one WARN `UniFi snapshot build failed: ... HTTP 401`.
  After a refusal the adapter does not log in again for 15 minutes, to
  keep the controller from rate-limiting or locking the account. Edit the
  adapter instance with the new password: the edit takes effect on the
  next cycle without waiting out the 15 minutes.
- **No NVR / camera objects appear** — the UniFi Protect application is not
  installed/running on the console, or the account lacks Protect API
  access.
- **No host cross-link on switch ports** — expected on a collector without
  ambient Suite API access, or where no port's LLDP neighbor is an ESXi
  host. UniFi collection is unaffected.
