# Overview — VCF Content Factory UniFi Controller

## What's in the Pack

VCF Content Factory UniFi Controller is a Tier 2 (Java SDK) management pack
that monitors Ubiquiti UniFi infrastructure through the UniFi Network API
and the UniFi Protect API. It discovers the controller's sites and the
devices under them — gateways, switches, access points, and (via Protect)
NVRs and cameras — and breaks switches and APs down to per-port and
per-radio child objects with PoE tracking. It replaces the Tier 1 UniFi
Network and UniFi Network Integration management packs.

Each cycle the adapter performs one UniFi Network / Protect pull into a
per-cycle snapshot and emits the full topology and metrics, with the
controller-wide picture anchored on the World resource.

### Resource kinds

The pack discovers an eleven-kind infrastructure tree (see
`inventory-tree.md` for the traversal spec and identifying keys, and
`REFERENCE.md` for the full metric list):

| Kind | Key | What it represents |
|------|-----|--------------------|
| UniFi World | `UniFiWorld` | Aggregation root (singleton per instance). |
| UniFi Site | `UniFiSite` | A controller site. |
| UniFi Gateway | `UniFiGateway` | Gateway / router device. |
| UniFi WAN Interface | `UniFiWanInterface` | A gateway WAN uplink. |
| UniFi Switch | `UniFiSwitch` | Switch device. |
| UniFi Switch Port | `UniFiSwitchPort` | Per-port object with PoE / LLDP detail. |
| UniFi Access Point | `UniFiAccessPoint` | AP device. |
| UniFi Radio | `UniFiRadio` | Per-radio child of an AP. |
| UniFi Wireless Summary | `UniFiWirelessAggregate` | Per-site wireless rollup. |
| UniFi NVR | `UniFiNvr` | Protect NVR. |
| UniFi Camera | `UniFiCamera` | Protect camera. |

### Metrics scope

Roughly 130 metric and property keys across the eleven kinds (see
`REFERENCE.md` for the authoritative list): device CPU / memory / uptime,
per-WAN throughput, per-switch-port traffic and PoE draw, per-radio channel
utilization and client counts, and the Protect NVR / camera state.

## Cross-Adapter Behavior

The pack ships an **optional, informational LLDP cross-link to ESXi
hosts**. A UniFi switch port that learns an ESXi host's uplink via LLDP is
attached to the corresponding **VMWARE HostSystem**, so the network edge
that a physical NIC plugs into is visible from the host. Resolution is by
`VMEntityName` against the real VMWARE inventory (`parentForeign(host,
port)`).

The cross-link is **ambient and optional**: it resolves foreign VMWARE
HostSystem resources over the local VCF Operations Suite API using the
collector's ambient credentials. When the Suite API is unavailable, the
cross-link is skipped with a WARN and all UniFi resources collect normally
— collection is never failed over the optional cross-link. The cross-link
legitimately matches nothing when no LLDP neighbor is an ESXi host.

> **Note:** relationship-edge persistence depends on the bundled framework
> jar. Build 5 picks up the framework `ResourceKey` arg-order fix, so the
> UniFi infrastructure-tree edges and the LLDP cross-link persist correctly
> (prior v2 builds emitted edges that the platform silently dropped at
> persist time).

## Notable Behaviors

- **Session self-recovery on 401.** UniFi controller sessions expire on the
  server side. Build 5 bundles the framework jar that makes the session
  cookie strategy (`SessionCookieAuth`) invalidate its cached `TOKEN`
  cookie on an HTTP 401/403 and replay the request once with a fresh login
  — so an expired session recovers automatically on the next call instead
  of 401-storming the instance to a permanent ERROR state (the build-4
  failure mode). UniFi reads are cookie-only GETs (no client-side CSRF), so
  a re-login refreshes the only session artifact and recovers cleanly.

- **Unreadable is loud.** A failed snapshot refresh or an unreadable
  `/self/sites` payload throws out of `collect()` (resource ERROR / DOWN) —
  the adapter never publishes `0.0` sentinels for data it could not read.
  Session cookie, login password, and `unifises` token are redacted from
  every path- and body-bearing error and log line.

- **Fresh-instance discovery works on VCF Ops 9.0.2.** The adapter
  enumerates its topology on the collect path (`discoverOnCollect()`), so a
  freshly created instance populates on its first collection cycle rather
  than waiting on a discovery task the platform may never invoke.

## Known Limitations

- **No remediation / control actions.** The pack is read-only monitoring.
- **LLDP cross-link requires the Suite API** and an ESXi host on the other
  end of the link. On a collector without ambient Suite API access, or
  where no switch-port LLDP neighbor is an ESXi host, the cross-link is
  omitted (UniFi collection is unaffected).
- **Protect objects (NVR / camera)** require the UniFi Protect API to be
  present and reachable on the controller.
