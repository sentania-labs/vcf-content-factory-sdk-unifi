# Overview — VCF Content Factory UniFi Controller

## What's in the Pack

VCF Content Factory UniFi Controller is a Tier 2 (Java SDK) management pack
that monitors Ubiquiti UniFi infrastructure through the UniFi Network API
and the UniFi Protect API. It discovers the controller's sites and the
devices under them — gateways, switches, access points, and (via Protect)
NVRs and cameras — and breaks switches and APs down to per-port and
per-radio child objects with PoE tracking. It replaces the Tier 1 UniFi
Network and UniFi Network Integration management packs.

Each cycle the adapter performs one UniFi Network pull into a per-cycle
snapshot and emits the full topology and metrics, with the controller-wide
picture anchored on the World resource. The Protect bootstrap is fetched
every cycle too; when that fetch fails, Protect is absent for the cycle and
the NVR and cameras publish nothing that cycle. The vmnic stitch's Suite
API host list changes rarely and is fetched at most every 15 minutes
(build 15, issue #21).

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

Switches and access points nest under the device they uplink to
(Gateway > Switch > ... > Access Point), so the tree follows the cabling;
a device whose uplink cannot be placed (unknown, a loop, a switch behind an
access point) sits directly under its Site (build 18). Per-parent objects
are named `<label> (<parent>)`: `Port 3 (<switch>)`, `WAN 1 (<gateway>)`,
`5 GHz (<access point>)`, `Wireless Summary (<site>)` (build 18; existing
objects are renamed too, and a rename on the controller carries over; see
REFERENCE.md, Object names).

### Metrics scope

Roughly 130 metric and property keys across the eleven kinds (see
`REFERENCE.md` for the authoritative list): device CPU / memory / uptime,
per-WAN throughput, per-switch-port traffic and PoE draw, per-radio channel
utilization and client counts, and the Protect NVR / camera state.

Build 15 changes to the metric set:

- **New `Status|state`** on the gateway, switch and access point: 1 when
  the device is connected, 0 when it is not (disconnected, upgrading,
  provisioning and so on), and **new `Status|state_code`**, a property
  naming the state: disconnected, connected, pending adoption, upgrading,
  provisioning, heartbeat missed, adopting, inform error, or the number
  for any other code.
- **New `Traffic|tx_rate` / `rx_rate`** (bytes/s) on switch ports, radios
  and WAN interfaces, from UniFi's own `tx_bytes-r` / `rx_bytes-r` rate
  fields. `Traffic|tx_bytes` / `rx_bytes` stay, and are cumulative
  counters since the device's counter reset (the WAN ones were mislabelled
  bytes/s before build 15). Use the rate keys for throughput charts. The
  radio rate field is not yet confirmed on a live controller, so a radio
  may publish no rate.
- **Renamed:** camera `Status|last_motion` (an epoch timestamp) is now
  `Status|seconds_since_last_motion` (time since the last motion), and NVR
  `Configuration|recording_retention_mode` (the raw milliseconds as text)
  is now the numeric `Configuration|recording_retention_days`. The old
  keys stop updating and keep their last value.

Build 16 adds to the metric set:

- **New `PoE|poe_budget_used_pct`** (%) on the switch: PoE Consumption
  divided by PoE Budget Max, times 100, the share of the switch's PoE
  budget drawn (for a "PoE budget nearly exhausted" alert, fork issue #30).
  Published only when the switch reports a PoE Budget Max above 0 and at
  least one PoE port reports its draw, so a non-PoE switch publishes
  nothing (never 0 and never NaN). A draw above the budget reads above 100.

Build 17 adds two properties and stops five from reading a missing field
as a value (review of build 16):

- **New `Status|up`** on the WAN interface: `true` / `false`, the WAN's
  link state, so WAN Degraded leaves out an unused second WAN.
- **New `Configuration|recording_mode`** on the camera: Protect's
  recording mode (`always`, `detections`, `schedule`, `never`), so Camera
  Not Recording leaves out a camera set never to record.
- Switch port `Status|up`, `Status|is_uplink` and `Status|duplex`, and
  camera `Status|is_connected` and `Status|is_recording`, are published
  only when the controller sends their field; before, a missing field read
  `false` (or `Half`), which the alerts would act on.

### Alerts

Build 16 (issue #30) bundles eight alerts, fourteen symptoms (twelve before build 17) and six
recommendations, installed with the pak: gateway, switch and access point
offline (`Status|state_code` disconnected or heartbeat missed, so a
firmware upgrade does not fire), uplink port down, camera offline, camera
not recording (connected cameras not set never to record), PoE budget nearly exhausted
(`PoE|poe_budget_used_pct` >= 90 on a connected switch) and WAN degraded
(availability below 99 or latency above 100 ms, on a WAN whose link is
up). What they do not catch (a WAN that loses its link, a switch that
loses its only uplink, inform-error devices, stale holds) is listed in
`REFERENCE.md`, Alerts, Limits. The full table (object,
severity, badge, conditions) is in `REFERENCE.md`, Alerts.

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
never failed over the optional cross-link.

Since build 15 (issue #21) the host list and the hosts' LLDP properties
are fetched at most every 15 minutes, the properties in bulk (one Suite
API request per 50 hosts, `GET /api/resources/properties?resourceId=..`,
instead of one per host), and the result is reused between fetches; the
join against the UniFi inventory still runs every cycle. A batch the bulk
request cannot read is read one host at a time, as before (one WARN per
batch). A failed fetch reuses the last good host list, with a WARN, for up
to an hour after it was fetched; past that the stitch is skipped until the
Suite API answers again. A new vmnic cabling change therefore shows within
about 15 minutes. Zero or ambiguous
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

- **A gap means not reported, never a fake 0** (build 15). A metric is
  published only when the controller sends its field as a number; before
  build 15 a missing field was published as 0 (0% CPU, 0 clients, 0
  satisfaction). A satisfaction of -1, UniFi's "no data", is not
  published. A gateway, switch or access point that is not informing the
  controller (any state other than connected, upgrading, provisioning or
  adopting; see Device state codes below) publishes `Status|state` = 0,
  its state code and its properties, and no metrics; its switch ports,
  radios and WAN interfaces publish no metrics either. So a blank stretch
  on a device's CPU or traffic chart, with such a state code, means the
  device was not reporting, not that collection broke. An upgrading,
  provisioning or adopting device reads 0 on `Status|state` but keeps
  publishing the metrics it reports. A device
  whose state is not reported publishes no state and the metrics it does
  report. Views, super metrics and averages that used to count those zeros
  read differently from build 15 (averages that included offline zeros
  rise).

- **Records with no identity are skipped** (build 15). A device or camera
  the controller reports without a MAC, a switch port without a
  `port_idx` and a radio without a radio code (for example on a device
  pending adoption) is not discovered, not related and not counted in the
  site's Device Count or the NVR's Camera Count. A device pending adoption
  (`adopted: false`) is not discovered or counted either until it is
  adopted. An NVR without a MAC is
  not registered either; its cameras still are, with no parent in the
  tree. Device Count counts only gateways, switches and access points, the
  types the pack discovers. Each skip is logged at DEBUG, and the `UniFi
  API snapshot` INFO line ends with `skipped with no identity: <n>
  devices, <n> ports, <n> radios, <n> cameras, <n> nvr` when any were
  skipped.

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

- **A failed snapshot is not retried per resource (build 14).** When the
  per-cycle snapshot cannot be built (wrong password, controller down),
  the failure is cached: the first caller of the cycle (usually the
  discovery step) reports it, and every other resource of that cycle
  fails from the cached result without contacting the controller. The
  adapter log gets one `UniFi snapshot build failed` WARN from the
  adapter, plus the framework's own line for the discovery step and for
  each resource that fails from the cached result. Each framework line is
  one line naming the original failure (its class and message), with no
  stack trace, on the cycle that tried the login as on the cycles served
  from the cache; the original with its stack is logged at DEBUG. After an
  authentication refusal (HTTP 401 or 403) or rate limiting (HTTP 429) no
  login is tried for 15 minutes unless the instance is edited, so a wrong
  or rotated password costs one failed login every few cycles instead of
  one per resource. The WARN says which it was and whether the login or a
  data call was refused. Any other failure is tried again on the next
  cycle. During a wrong-password back-off the adapter instance still
  shows **Collecting** in the account list while every UniFi object is
  ERROR: the adapter cannot change the instance status (a framework
  limitation).

- **An unreachable controller is DOWN, not ERROR (build 14).** Connection
  refused, a connect or request timeout, an unknown host name or no route
  to the host marks the UniFi objects DOWN (the target is down), wherever
  the network error sits in the failure. A refused login, a certificate
  failure or an unreadable answer is ERROR.

- **One unreadable site does not take out the others (build 14).** If the
  device list of one site cannot be read (a permission on that site, a
  timeout, a 5xx), that site's site, device, port, radio, WAN and Wireless
  Summary objects go to ERROR for the cycle, and every other site collects
  normally. The adapter log gets one WARN naming the site, plus the
  framework's own line for each resource of that site. Its existing
  relationships are left as they are. A device that moved between that
  site and a readable one in the meantime can briefly show the wrong site:
  moved out of the failed site, it is under both sites; moved into it, it
  is under neither (and ERROR, since it was not read). Both heal on the
  first cycle the failed site is read again. The whole cycle fails only
  when the site list itself or every site's device list cannot be read. If
  one site's call cannot connect to the controller (connection refused,
  connect timeout, unknown host, no route), the sites after it are not
  asked that cycle and are ERROR too, so a controller that is down costs
  one timeout per cycle, not one per site. A site whose request times out
  after connecting (a slow, large site) is ERROR alone and the sites after
  it are still read; a controller that accepts connections but answers
  nothing therefore costs one timeout per site, and if every site times
  out the objects are DOWN. A site whose device list is refused (HTTP
  401 or 403, an authentication or permission refusal, or 429, rate
  limiting) while other sites are read is not asked again for 15 minutes
  unless the instance is edited: its objects stay ERROR, its relationships
  are kept, and the other sites collect every cycle. The adapter log gets
  one WARN when that cool-down starts and one INFO when it has passed and
  the site is asked again. Any other site failure (a 5xx, a timeout) is
  asked again on the next cycle. A refusal on every site fails the whole
  cycle into the 15-minute back-off above, as before.

- **Fresh-instance discovery works on VCF Ops 9.0.2.** The adapter
  enumerates its topology on the collect path (`discoverOnCollect()`), so a
  freshly created instance populates on its first collection cycle rather
  than waiting on a discovery task the platform may never invoke.

- **Removed objects are marked Not Existing and deleted** (build 18,
  issue #20). An object the controller no longer lists, missing for 12
  consecutive complete cycles, is marked Not Existing and VCF Operations
  deletes it after the Deleted Objects retention. A kind counts a miss only
  in a cycle that read it in full: an unreadable site, a disconnected
  switch or access point, or a Protect outage counts nothing for the kinds
  it touches. Guarded empty answers count nothing too: an empty site list,
  no device on any site, an empty camera list under a listed NVR, and an
  empty port or radio table or missing `wan1` on a listed device, so the
  last site, device, camera, and those children are removed by hand. The
  last device of a type is removed when other devices are still listed.
  The first upgrade marks every leftover from older builds
  (see README, Upgrading to build 18). Rule: REFERENCE.md, Removed objects.

- **A failed read does not drop relationships** (build 18, issue #20). On a
  cycle without Protect the NVR's site keeps its edges, NVR included, for
  up to 12 cycles; NVR > Camera edges and the vmnic stitch edges are kept
  through Protect and Suite API outages. Details: REFERENCE.md,
  Relationships when a read fails.

### Device state codes

The UniFi device `state` field (`stat/device`), as this pack reads it for
`Status|state`, `Status|state_code` and whether metrics are published:

| Code | `Status|state_code` | `Status|state` | Metrics published |
|---|---|---|---|
| 0 | disconnected | 0 | no |
| 1 | connected | 1 | yes |
| 2 | pending adoption | 0 | no |
| 4 | upgrading | 0 | yes |
| 5 | provisioning | 0 | yes |
| 6 | heartbeat missed | 0 | no |
| 7 | adopting | 0 | yes |
| 9 | inform error | 0 | no |
| any other | the number | 0 | no |
| absent or not a whole number | (not published) | (not published) | yes, fields present |

Source: codes 1 and 0 are recorded on the factory's lab controller
(`knowledge/context/api-maps/unifi-network-api.md`, `stat_device.state`,
"1=connected, 0=disconnected"; only 1 was observed). The other names are
community-sourced (the device state values used by open-source UniFi
clients) and not yet seen on a live controller; confirm on devel. Metrics
publish only while the device is informing the controller (1, 4, 5, 7):
in any other state the controller holds last values, which would
otherwise publish as current.

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
