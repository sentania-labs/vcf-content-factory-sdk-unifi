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

The pack ships an **optional, informational vmnic→port stitch to ESXi
hosts** (build 9; replaces the build ≤8 controller-side LLDP cross-link,
which never worked — see below). Each VMWARE `HostSystem` publishes, per
physical NIC, the UniFi switch device name and switch-port name its LLDP
neighbour advertises (`net:vmnic<N>|discoveryProtocol|lldp|systemName` /
`...|portName`). The adapter enumerates HostSystems over the Suite API,
reads those per-vmnic properties, and matches the `(switch, port)` pair
back into its own UniFi switch/port inventory — a per-vmnic-accurate join,
not just per-host. A matched host becomes the foreign parent of the
corresponding **UniFiSwitchPort** (`parentForeign(host, port)`), and the
same join repurposes the port's `LLDP|lldp_system_name` /
`LLDP|lldp_port_id` properties to show the connected ESXi host and vmnic
directly on the port resource.

> **Why the direction inverted in build 9.** Builds ≤8 tried to read the
> neighbour off the UniFi controller itself
> (`port_table[].lldp_table[].lldp_system_name`). On Network App 10.2.105
> that table is empty for every port — the controller's REST API never
> re-publishes the switch's own LLDP daemon's neighbours, on any surface
> (classic, v2, or Integration), even though the switch CLI (`show lldp
> neighbor`) proves the daemon sees the ESXi hosts. See
> `context/investigations/unifi-lldp-switchport-esxi-2026-07-05.md` for the
> full endpoint-by-endpoint finding. Build 9 reads the same neighbour data
> from the other end of the link — vCenter, which does publish it — instead
> of the UniFi controller, which does not.

The stitch is **ambient and optional**: it resolves foreign VMWARE
HostSystem resources and reads their properties over the local VCF
Operations Suite API using the collector's ambient credentials. When the
Suite API is unavailable, or a fetch/match fails, the stitch is skipped
(WARN once) and all UniFi resources collect normally — collection is
never failed over the optional cross-link. Zero or ambiguous
`(switch, port)` matches are legitimate (no fabricated edges, ever) and
are counted in a one-line INFO summary each cycle; per-pair misses are
logged at debug only, never WARN spam.

> **Build 11 — conflicted-port property honesty.** If two *different*
> hosts both claim the same UniFi switch port as an LLDP neighbour in
> the same collect cycle (contradictory data — e.g. a mis-cabled link or
> stale LLDP state on one side), the `LLDP|lldp_system_name` /
> `LLDP|lldp_port_id` properties are written for **neither** claimant that
> cycle rather than silently keeping whichever claim happened to be
> processed last. The conflict is counted in the per-cycle summary line
> and logged at debug with both claimant host names. The relationship
> edge is unaffected — every matched neighbour still produces a
> `HostSystem → UniFiSwitchPort` foreign edge, including conflicted ones;
> this is a property-write honesty fix only.

> **Note:** relationship-edge persistence depends on the bundled framework
> jar. Build 5 picks up the framework `ResourceKey` arg-order fix, so the
> UniFi infrastructure-tree edges and the vmnic→port stitch persist
> correctly (prior v2 builds emitted edges that the platform silently
> dropped at persist time).

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
- **vmnic→port stitch requires the Suite API** and an ESXi host advertising
  LLDP to vCenter on the other end of the link. On a collector without
  ambient Suite API access, or where a host has no `net:vmnic*|
  discoveryProtocol|lldp|*` properties (LLDP off/CDP-only on that host), the
  stitch is omitted for that host (UniFi collection is unaffected).
- **CDP-only hosts are out of scope.** The join anchors on the literal
  `|discoveryProtocol|lldp|` property path; CDP-advertised neighbours use a
  different key shape and are not matched.
- **Renamed UniFi ports are handled via a two-alias join (build 10).**
  DEVEL evidence showed the switch's LLDP daemon advertises the
  *hardware label* (`Port <idx>`), not a custom display name, so build 9's
  display-name-only index missed a renamed port. The own-inventory index
  now registers each port under both `portDisplayName` and `"Port " +
  port_idx` against the same joint key, covering today's firmware
  (hardware label) and a possible future firmware that advertises the
  custom label instead. An alias collision (e.g. a port renamed "Port 3"
  colliding with a real Port 3) degrades to the existing ambiguous-skip —
  never a fabricated edge.
- **Protect objects (NVR / camera)** require the UniFi Protect API to be
  present and reachable on the controller.
