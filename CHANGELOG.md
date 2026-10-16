# Changelog

## 0.0.0.15 (Unreleased)

Dev preview build 15 (`build-sdk` stamps 0.0.0.15); the 1.1.0 release line
is unchanged until a `v1.1.0.15` tag. The factory brief is
knowledge/designs/sdk-adapters/unifi-build-15-correctness-and-hygiene.md.

- fix(adapter): build 15, issue #34: the UniFiApiClient logger is resolved once per configure, stored on the configuration holder and handed to the login callback, instead of being looked up on every login and twice per Test Connection. The framework's componentLogger resets a logger to INFO on every lookup, so a DEBUG line in the client could never have been seen. Test Connection on a bare instance (the usual case) looks it up once. No log line changes today. Tests: tests/test_snapshot_logger_once.py.
- fix(adapter): build 15, issue #24: HTTP clients and the Suite API stitcher are released. The login-only client that posts the credentials is now built and discarded together with the authenticated client: in Test Connection's `finally`, on an instance edit and when the instance is discarded. An instance edit also releases the previous Suite API stitcher (its token) before building the new one. On an edit and on discard the adapter releases only the login-only client and leaves the authenticated one to the framework, which already releases it (review N2). Already true before this build, from the framework (`VcfCfAdapter.onConfigure` and `onDiscard`): the previous authenticated client was discarded on an edit and the current one on discard. Note: the framework's `ManagedHttpClient.discard()` clears the session only and does not close the JDK client underneath, so the connection pool is left to garbage collection; that part is a framework matter, not this pak's. Tests: tests/test_client_discard.py (structural; the adapter class needs the SDK jar).
- test(adapter): build 15: `ci/run_java_tests.sh` runs every `tests/com/vcfcf/adapters/unifi/*Test.java` and every `tests/test_*.py` instead of a fixed list, so a new test cannot be added and skipped.
- fix(adapter): build 15, issue #23: **a device or camera with no MAC, and a switch port with no `port_idx`, is skipped instead of registered.** Before, discovery and relationships used the raw value: a pending or partly reported device could register with an empty identifier, and every port missing its index collapsed onto `<mac>_0`, while the snapshot index already skipped a blank MAC. One guard (`UniFiIds.mac`, `UniFiIds.portIdx`) now decides for discovery, relationships, the vmnic stitch's port index, the port lookup and the snapshot index alike. Each skipped record is logged at DEBUG once per snapshot, and the `UniFi API snapshot` INFO line ends with `skipped with no identity: <n> devices, <n> ports, <n> cameras` when any were skipped. A real MAC or port index is used exactly as before, so existing resource keys do not change. The NVR identity is not touched (not in this issue). Tests: UniFiIdsTest, tests/test_inventory_guard.py.
- fix(adapter): build 15, issue #22: the site name in a per-site request path (`stat/device`, `stat/health`) is percent-encoded as one path segment, as the vmnic stitcher already does for its paths, so a reserved character in a name returned by `/self/sites` (for example `/`, `?`, `#`, `%` or a space) cannot change which path is requested. Names of letters, digits, `-`, `_` and `.` (the usual `default` and generated site ids) request the same path as before. Tests: UniFiIdsTest, tests/test_site_path.py.
- fix(adapter): build 15, issue #18: **the site's Version and Timezone properties are collected, and Device Count is counted from the site's devices.** Both were declared in describe.xml and never published. They now come from `stat/sysinfo` (`version` is the Network application version, `timezone` the controller's), one extra request per site per cycle, each published only when the controller sends it. A sysinfo failure costs only those two properties for the cycle (one INFO line per site), never the site; an interrupt (Stop Collecting) during it reaches the framework (review N4). Device Count was read from the `/self/sites` entry; it is now the number of adopted devices in this cycle's `stat/device` for the site (a device with `adopted: false`, pending adoption, is not counted; a device without the field is; a device without a MAC, which the inventory skips per #23, is not counted either, review N8). Tests: tests/test_site_properties.py (structural).
- fix(adapter): build 15, issue #14: **new metric `Status|state` on the gateway, switch and access point (1 connected, 0 otherwise), and a metric whose field is absent is no longer published as 0.** The device's `state` (1 = connected in the UniFi device API; 0 disconnected, 4 upgrading, 5 provisioning and the rest count as not connected) was never read, so an offline device published 0% CPU, 0 uptime and 0 clients and looked healthy. Now a device that is not connected publishes `Status|state` = 0 and its properties, and no performance metrics; its switch ports, radios and WAN interfaces publish no metrics either (their properties still publish). A device whose `state` is missing or not a number publishes no state (unknown is neither connected nor not) and its present metrics. Everywhere (gateway, WAN, switch, port, access point, radio, Wireless Summary, NVR, camera) a metric is published only when its field is present and a number (`metricIfPresent`): a missing field, a boolean or free text publishes nothing where `SimpleJson.asDouble()` gave 0.0. Specific cases: a satisfaction of -1 (UniFi's "no data") is not published; a switch without `total_max_power` (non-PoE) no longer publishes 0 W budget metrics, and `PoE|poe_consumption` is published only when a PoE port reports its draw; `Status|mac_table_count` is the size of the port's `mac_table` list when present, else the controller's `mac_table_count`, else nothing (it read 0 for a port without the list); NVR uptime is not published when `upSince` is missing (it was 0) and `Storage|usage_pct` only when both sizes are present. Three numeric properties also read blank instead of "0" when their field is absent (review N1): port `Status|speed` (a down port really reads 0, so a missing speed no longer looks like one), radio `Configuration|min_txpower` / `max_txpower` and camera `Hardware|phy_rate`. Other properties are unchanged. **Upgrade note:** a gap now means "not reported" or "device disconnected" where a 0 was published before, so a view, super metric or average that included those zeros changes value (averages that counted offline zeros rise). No symptom or alert for a disconnected device ships in this build (fork issue #30). Tests: UniFiValuesTest, tests/test_present_only.py.
- fix(adapter): build 15, issue #16 (parts 2 and 3): **two keys renamed on purpose.** Camera `Status|last_motion` is replaced by `Status|seconds_since_last_motion`: the old value was Protect's `lastMotion` epoch timestamp divided by 1000 (about 1.7 billion "seconds"), the new one is the time since the last motion in seconds, so "no motion for X" can be alerted on; absent when the camera never recorded motion, 0 (not negative) when the timestamp is ahead of the collector's clock. NVR `Configuration|recording_retention_mode` is replaced by the numeric property `Configuration|recording_retention_days`: the old one carried the millisecond duration `recordingRetentionDurationMs` as text under a "mode" name; the new one is that duration in days (fractions kept), published only when the NVR sends it. **Upgrade note:** the old keys stop updating and keep their last value in VCF Operations; anything that reads them (a view, dashboard, symptom or super metric) must move to the new keys. Nothing on main reads them; the parked `feat/dashboards` branch reads `Configuration|recording_retention_mode` in `dashboards/unifi-summary-nvr.yaml` and must move to `Configuration|recording_retention_days` before it lands. Tests: UniFiValuesTest, tests/test_renamed_keys.py.
- fix(adapter): build 15, issues #16 (part 1) and #29: **new metrics `Traffic|tx_rate` and `Traffic|rx_rate` (bytes/s) on switch ports, radios and WAN interfaces, and the WAN byte counters are labelled as the counters they are.** WAN `Traffic|tx_bytes` / `rx_bytes` are cumulative counters since the device's counter reset but were declared `bytes/s`; describe.xml now says `bytes` (values unchanged). The new rate keys are read from UniFi's own rate fields, `tx_bytes-r` / `rx_bytes-r` (the same kind of field the Wireless Summary already reads): on the port from `port_table[]`, on the WAN from the gateway's `wan1` / `wan2` object, on the radio from the access point's `stat.ap.<radio name>-tx_bytes-r` / `-rx_bytes-r`. Each is published only when the field is present; the counters stay. The port and WAN fields are recorded on the factory's lab controller (API map, 2026-04-18); the radio field is not, so a radio may publish no rate until devel confirms the key. Charts that want throughput move to the rate keys. Tests: tests/test_traffic_units.py.
- fix(adapter): build 15, issue #21: **the vmnic stitch reads host properties in bulk and refreshes at most every 15 minutes.** Before, every snapshot build (every cycle) listed every VMWARE HostSystem and then made one Suite API `GET /api/resources/{id}/properties` call per host. Now the host properties are read 50 hosts per `GET /api/resources/properties?resourceId=..` request (the Suite API's `getResourcePropertiesList`); a batch whose bulk request fails, or answers none of its hosts, is read one host at a time as before (one WARN per batch), so an appliance that does not answer the bulk form still stitches. The host list is held for 15 minutes (per configuration; an instance edit starts empty) and reused between fetches; the join against this cycle's UniFi inventory, and so the edges and the `LLDP|*` port properties, still run every cycle. A failed host list refresh reuses the last good list for up to an hour after it was fetched (one WARN per such cycle), and a refresh that could read no host's properties at all counts as failed rather than as a list of hosts without neighbours. A refresh that could read only some hosts also counts as failed when a last good list exists (that list is reused); with none, the partial list serves that cycle only and the next cycle tries again, so an unread host does not lose its `LLDP|*` claimant for 15 minutes. A collector clock that steps back makes the cached list expired, never fresh, and it is not reused after a failed refresh. Past the hour, or with nothing to reuse, the stitch is skipped for the cycle as before. A vmnic cabling change shows within about 15 minutes. **The Protect bootstrap is unchanged from build 14:** fetched every cycle, not cached, so camera state and NVR storage are never older than one collection interval; a failed fetch leaves Protect absent for the cycle and the NVR and cameras publish nothing that cycle. Reusing a last good bootstrap after a failed fetch was tried on this branch and dropped in review, because it republished an up-to-an-hour-old camera state (recording, connected) as current during an outage. The NVR and camera edge flapping half of fork issue #20 therefore stays open for Scott's decision. An interrupt (Stop Collecting) during the stitch or the Protect read now reaches the framework instead of being swallowed. The stitch still runs inside the snapshot build (not moved off the snapshot lock); with the cache that is one fetch per 15 minutes. Tests: RefreshCacheTest, VmnicLldpTest, tests/test_stitch_refresh.py.
- docs(adapter): build 15, issue #27 (items 1 to 3): item 1, the Allow Insecure SSL default, was already consistent on main since build 14: describe.xml, README, REFERENCE and docs/installing.md all say `false`, pinned by tests/test_allow_insecure_default.py; no change. Item 2: README no longer says "LLDP-based stitching to ESXi hosts"; it describes the vCenter-side vmnic stitch and that it needs the collector's local Suite API, and docs/installing.md's network section and troubleshooting entry say the same (the controller's own LLDP table is not used). Item 3: docs/installing.md has a new Account section: a dedicated local account (not a Ubiquiti SSO or cloud account), no MFA, View Only in Network (and Protect for NVR and camera objects), with the reason for each.
- docs(adapter): build 15, issue #32: README's Configuration section said to create the adapter instance under "Integrations > Repository", which is where paks are installed. It now says **Administration > Integrations > Accounts > Add Account** (Data Sources > Integrations > Accounts on 8.x). The pak upload path in README (it said Administration > Solutions) and the two install steps in docs/installing.md now use the same 9.x paths, Administration > Integrations > Repository and Accounts. The 9.x paths follow the vsanhostmetrics pak's README, which records them as the paths present in 9.x; this build has not re-checked them against a 9.x UI.

## 0.0.0.14 (Unreleased)

Dev preview build 14 (`build-sdk` stamps 0.0.0.14); the 1.1.0 release line
is unchanged until a `v1.1.0.14` tag. The factory brief is
knowledge/designs/sdk-adapters/unifi-build-14-availability-and-tls.md.

- fix(adapter): build 14, issue #13: **Behavior change: Allow Insecure SSL defaults to `false`, so a new instance validates the controller certificate.** describe.xml ships `default="false"`; README, REFERENCE and docs/installing.md now all say false (README and REFERENCE said true). The parse is unchanged and pinned: only `true` (any letter case) turns validation off, anything else validates. configure logs one WARN while insecure mode is on. **Upgrade note: existing instances keep their stored value.** An instance created with the old default stores `true` and keeps skipping validation until it is edited to `false`. Validate Connection offers the controller certificate for review (the `certificateCheckUrls` hook, as compliance build 93; closes fork issue #33): a console with an untrusted, for example factory self-signed, certificate is accepted once in VCF Operations' Review and Accept Certificate dialog, the certificate is stored on the instance and validation stays on. No dialog is offered while Allow Insecure SSL is `true`. The dialog is proven with the console entered by a DNS name its certificate carries; README and docs/installing.md say to enter it that way, since by an IP address the certificate does not list the dialog is unproven and Allow Insecure SSL may be the only path (fork issue #33 follow-up). Tests: AllowInsecureTest, tests/test_allow_insecure_default.py.
- fix(adapter): build 14, issue #11: **a failed snapshot is cached, so a wrong password costs one login, not one per resource.** Before, every resource of the cycle rebuilt a failed snapshot and logged in again. Now the first caller of the cycle (usually the discovery step) gets the failure and the rest of the cycle fails from it without contacting the controller. The adapter log gets one `UniFi snapshot build failed` WARN from the adapter, plus the framework's own line for the discovery step and for each resource that fails from the cached result. Each of those framework lines is one line, the discovery step's included, on the cycle that tried the login (the first cycle and every back-off expiry) as on the cycles served from the cache: the caller whose build failed gets the same stackless, classified exception the cache serves later, naming the original's class and message, with no cause chain or stack trace (devel_9_1 follow-up: the attempt cycle's discovery WARN carried a 24-line stack). The original, stack included, is logged at DEBUG with the adapter's WARN line, through a logger resolved once per configure and stored with the cache (the framework's componentLogger resets a logger to INFO on every call, so a per-failure lookup could never write it); a fault in that logger is swallowed and never reaches the collect path. Tests: SnapshotCacheTest, tests/test_snapshot_logger_once.py. After an HTTP 401 or 403 (an authentication refusal) or 429 (rate limiting) no login is tried for 15 minutes unless the instance is edited (an edit starts clean and may log in at once); the WARN says which it was and whether the login or a data call was refused; after any other failure the next cycle retries. Test Connection does not use the cache. Tests: SnapshotCacheTest.
- fix(adapter): build 14, issue #12: the snapshot is stamped when its build finishes and is built once per collection cycle. Before, its age counted from the start of the build, so a build slower than 60 s was rebuilt by every resource. A 15-minute age, counted from the end of the build, is only a safety net for a cycle that never ends. Tests: SnapshotTimingTest.
- fix(adapter): build 14, issue #19: **one site that cannot be read no longer fails every site.** A failed per-site `stat/device` marks that site failed (one adapter WARN naming the site, plus the framework's line for each resource of that site that fails): its site, device, port, radio, WAN and Wireless Summary objects are ERROR for the cycle and its existing relationships are kept, while the other sites collect. The whole snapshot fails only when `/self/sites` fails or every site fails. A site whose call cannot connect to the controller (connection refused, connect timeout, unknown host, no route) stops the per-site loop: the sites after it are marked failed without being asked (one WARN naming them), so one build does not pay the timeout and retries once per site. A request timeout after the connection was made (one slow site) fails only that site and the loop continues, so healthy sites after it still collect (review N1); every site timing out is still DOWN. A site refused with HTTP 401, 403 or 429 while other sites are read is cooled down for 15 minutes in the per-configuration holder (60 s after any other failure, which at the default interval has passed by the next cycle): later builds skip its `stat/device` call and keep it failed (objects ERROR, relationships kept), with one WARN when the cool-down starts and one INFO when it clears; an instance edit starts with none. Before, the refused site was asked again every cycle, one more refused request per refused site per cycle, since the partial snapshot never reached the snapshot back-off (Codex P1). A refusal on every site, or every site cooling down, still fails the whole build into the existing back-off. Tests: SiteReadsTest, SiteIsolationTest (the adapter-side lookup, site and parent decisions, now in an SDK-free helper).
- fix(adapter): build 14, issue #25: **an unreachable controller is DOWN, not ERROR.** Connection refused, connect or request timeouts, an unknown host and no route to host map to DOWN wherever they sit in the cause chain (the login wrapper, a cached failure). Refused logins, certificate failures and unreadable payloads stay ERROR. Tests: FailureMappingTest.
- test(adapter): build 14 adds `ci/run_java_tests.sh` (plain-main Java tests and stdlib Python checks, no SDK jar needed). CI now runs it on every push and pull request (`.github/workflows/tests.yml`, calling the SDK template's reusable `pak-tests.yml` on JDK 17 and 25), the pull-request half of issue #28 item CI7; the tag workflow does not run it yet.

## 1.1.0.13 (2026-08-18)

- fix: Allow Insecure SSL now ignores certificate hostname mismatches (issue #5). No adapter source change: rebuilt against sdk-buildkit v1.0.10, which fixes vcf-content-factory#82 (`insecureSslContext()` reimplemented as `X509ExtendedTrustManager` so JSSE no longer re-arms the endpoint identity check). Build 12 was pulled: it was tagged without the `build_number` bump, so its pak was stamped 1.1.0.11 and version-indistinguishable from the prior release.

## 1.1.0.11 (2026-07-06)

- release: cut the `1.1.0` CI/release line (minor bump over the released
  `1.0.0` line, per `rules/pak-version-lines.md` RULE-014 — `adapter.yaml`
  `version` is what the tag-triggered CI `--release` build stamps; local
  `build-sdk` dev-preview builds are unaffected and continue stamping
  `0.0.0.x`). Rationale for a minor rather than patch bump: the cross-MP
  vmnic→port stitch was redesigned across builds 9-11 — the dead
  controller-side LLDP read was replaced with a vCenter-side vmnic→port
  join (build 9), a hardware-label alias closed a missed-edge gap in the
  own-inventory index (build 10), and conflicted-property honesty was
  added to the stitch write path (build 11). This release line packages
  all three builds; see the `0.0.0.9` / `0.0.0.10` / `0.0.0.11` entries
  below for the full detail on each.
- Unblocked by the RULE-012 defect-gate: DEF-007 (platform cache-orphaning
  issue, not an adapter defect) closed today with PROD Cloud Proxy
  evidence — a 16-edge vmnic→port stitch sustained through a Cloud Proxy
  collector path in production.

## 0.0.0.11 (2026-07-06)

- fix(adapter): conflicted-port property honesty in the vmnic→port stitch
  (`designs/managementpacks/unifi-switchport-host-stitch-v2.md`, build-11
  amendment; supersedes the uninstalled build 10). `computeVmnicStitch`
  tracked `vmnicLldpByPortKey` last-write-wins: if two different hosts
  both claimed the same UniFi switch port as an LLDP neighbour in one
  cycle (contradictory data), whichever match happened to be processed
  last silently won the `LLDP|lldp_system_name` / `LLDP|lldp_port_id`
  property write. Build 11 tracks the claimant host per portKey for the
  cycle; a second claim from a **different** host marks the portKey
  conflicted — the earlier claim's property is removed, no property is
  written for either claimant, both claimant host names are logged at
  debug, and the conflict is counted in a new `<N> conflicted` field on
  the per-cycle INFO summary line. A later third claim on an
  already-conflicted portKey cannot resurrect the property. Same-host
  re-claims (two port aliases folding to the same joint key, or
  identical values) remain idempotent and keep the property write.
  Relationship-edge emission (`HostSystem → UniFiSwitchPort`
  `parentForeign`) is untouched — every match still yields an edge,
  conflicted or not; this is a property-write honesty fix only.

## 0.0.0.10 (2026-07-06)

- fix(adapter): hardware-label alias in the vmnic→port own-inventory index
  (`designs/managementpacks/unifi-switchport-host-stitch-v2.md`, build-10
  amendment). Build 9 missed 1 of 16 DEVEL edges — `usw-lite-16-nuc` port 15
  is renamed `Router` in the controller, but the switch's LLDP daemon
  advertises the hardware label (`Port 15`), not the custom display name,
  so the display-name-only index found no candidate for the joint key
  vCenter published. `buildOwnPortIndex` now registers each port under
  both `normPort(portDisplayName)` and `normPort("Port " + port_idx)`
  against the same `(switch, port)` joint key, with a per-key portKey
  presence check so the common case (display name == hardware label)
  doesn't double-register into a spurious ambiguous collision. Matching
  discipline unchanged: exactly-one candidate → edge, zero/2+ → debug-only
  skip, never a fabricated edge.

## 0.0.0.9 (2026-07-05)

- fix(adapter): replace the dead controller-side LLDP cross-link with a
  vCenter-side vmnic→port join
  (`designs/managementpacks/unifi-switchport-host-stitch-v2.md`, build-9
  addendum; `context/investigations/unifi-lldp-switchport-esxi-2026-07-05.md`).
  Investigation proved `port_table[].lldp_table` is empty for every port on
  Network App 10.2.105 and no controller API surface (classic / v2 /
  Integration) re-publishes the switch's own LLDP daemon's neighbours — the
  build ≤8 stitch matched 0 hosts on every cycle, silently. vCenter/ESXi does
  publish the same neighbour data per HostSystem
  (`net:vmnic<N>|discoveryProtocol|lldp|systemName` / `...|portName`,
  live-proven on all 8 DEVEL hosts), so build 9 inverts the join direction:
  - **`UniFiStitcher`:** `matchHostByName` / `ForeignResourceResolver`
    removed (dead under the inverted join — a host lookup by name never
    happens anymore). New `listHostsWithVmnicLldp()` enumerates VMWARE
    HostSystems (Call A, honest `isPartOfUniqueness` from the Suite API
    response, unchanged from build 8) plus each host's per-vmnic LLDP
    properties (Call B, `GET /api/resources/{id}/properties`) — 1+H Suite
    API GETs per snapshot refresh, no cache (fetch fresh every refresh,
    replacing the resolver's 5-minute TTL).
  - **`UniFiAdapter`:** `emitLldpHostCrossLink` renamed
    `emitVmnicHostStitch`; matching now joins the normalized
    `(switchName, portName)` pair against the adapter's own inventory
    (`normSwitch`/`normPort`: lowercase, `_`→space, collapse whitespace,
    trim — folds the XG SFP-port LLDP quirk `"SFP_ 1"` to the UniFi display
    name `"SFP 1"`). Exactly-one match → edge; zero/ambiguous → debug-only
    skip, no fabrication (unchanged discipline from build 8). Edge
    semantics (`parentForeign` → additive `addRelationships`, never
    full-set onto the foreign HostSystem parent) and crash safety
    (stitch failure never costs the cycle) are unchanged. One INFO summary
    per cycle: `vmnic→port stitch: <E> edges (<H> hosts, <V> vmnics w/
    LLDP, <A> ambiguous, <U> unmatched)`.
  - **`LLDP|*` describe group repurposed, not removed.** The controller-side
    read this group was built for never worked on 10.2.105, so shipping it
    unchanged would leave three declared-but-never-populated properties —
    the exact misleading-metric failure mode the framework forbids.
    `lldp_system_name` / `lldp_port_id` are now populated from the matched
    vmnic join (the connected ESXi host name and vmnic label — better data
    than the controller ever had for these fields). `lldp_chassis_id` is
    **removed** (code + `describe.xml` + `resources.properties`): vCenter's
    LLDP properties expose no chassis id, so it can never be honestly
    populated under either design.
  - `adapter.yaml` description reworded "LLDP-based stitching" →
    "vCenter vmnic->port stitching"; `docs/overview.md` rewritten to
    describe the new join direction and cite the investigation.

## 0.0.0.8 (2026-07-02)

- fix(adapter): cross-MP stitch audit against the current framework model
  (`context/defects.md` DEF-002; `lessons/cross-mp-stitch-cp-identity-and-
  edge-mechanics.md`). Two fixes in `UniFiStitcher.java` /
  `UniFiAdapter.java#emitLldpHostCrossLink`; everything else already
  conformed:
  1. **Real `isPartOfUniqueness` propagation (the fix).**
     `SuiteApiHostBridge.listResources()` hardcoded `isUnique="true"` for
     every VMWARE `HostSystem` identifier returned by the Suite API,
     instead of reading the identifier's real
     `identifierType.isPartOfUniqueness` flag. This is the exact silent
     failure class documented in
     `lessons/cross-mp-foreign-key-uniqueness-flags.md` (the synology
     `.18`-`.21` reproducer): an over-marked key's effective identity
     diverges from the registered resource's, so the platform can never
     bind the edge — the collector log still shows `Relationship items
     count: N>0` every cycle, and the edge silently never persists, with
     zero error anywhere. Fixed to read
     `id.get("identifierType").get("isPartOfUniqueness").asBoolean()` per
     identifier and propagate it verbatim (byte-identical pattern to
     `SynologyStitcher.SuiteApiDatastoreBridge`), defaulting to `false` on
     absent/null — never over-mark.
  2. **Multi-candidate LLDP neighbour (the search-values concern).**
     `emitLldpHostCrossLink` matched only `lldp_table.get(0)` per switch
     port. A port's `lldp_table` can carry more than one neighbour entry
     (inline media converter, shared segment); taking only the first is
     the same first-match shortcut class as the synology single-NIC
     bug (build 25 finding). Fixed to try every `lldp_table` entry on the
     port against real VMWARE `HostSystem` inventory and take the first
     REAL match, not the first entry.
  - **Confirmed already conforming — no change:** the write verb
    (`rb.parentForeign(host, portKey)` → framework `RelationshipBuilder`
    additive `addRelationships`, never full-set `setRelationships` onto
    the foreign HostSystem — this closes the DEF-002 clobber-risk concern
    structurally, independent of any 9.1 `setRelationships`-scoping
    proof); the ambient identity path (`SuiteApiStitcher.create(this,
    …)`, no adapter-side credential handling — ambient identity v3
    injected-instance-first now rides in via the rebuilt
    `adapter_framework/` jar with zero adapter source change); the
    degrade-not-crash posture (`stitcher == null` skip, cross-link
    failure caught and WARN-logged, never costs the cycle its internal
    topology); resolving `HostSystem` by the search value `VMEntityName`
    then rebuilding the key from the resolved resource's own identifier
    set (never a bare MOID, never inventing an identifier).
  - **Inherited (framework, no adapter action — rides in via the rebuilt
    `adapter_framework/` jar bundled by `build-sdk`):** ambient identity
    v3 (injected per-instance credential first, then
    `automationuser.properties`, then `maintenanceuser.properties`), the
    additive `parentForeign` write path in `RelationshipBuilder`, and the
    BC-mirror loopback Suite API transport
    (`VcfCfAdapter.applyBcMirrorTransport`). Ships in sdk-buildkit-v1.0.5.
  - This is a devel/dev-preview build — no `v*` release tag. Per RULE-014
    (`rules/pak-version-lines.md`) the hand build is expected to stamp
    `0.0.0.8`. Closes the unifi half of DEF-002's static-review basis
    (the full-set-onto-foreign-parent concern); DEF-002 itself remains
    open pending its stated closing criterion — a live devel collect
    against an LLDP-reachable ESXi host showing the matched HostSystem
    retains its pre-existing VMWARE children and gains the
    `UniFiSwitchPort` child.

## 0.0.0.7 (2026-06-25)

- fix(framework): dev preview build with the CORRECTED hand-build version
  convention. Hand/dev builds now use major.minor.patch = `0.0.0` (pak
  version `0.0.0.x`) so they always sort below any real CI/release line
  and never trigger upgrade-refusal. The prior `99.x` convention was
  erroneous: a `99.0.0.x` hand-build makes a future real `1.0.0.x`
  release look like a downgrade, and VCF Ops refuses the install. No
  UniFi adapter source change. Still bundles the localization-fixed
  `vcfcf-adapter-base.jar` (carries the `onDescribe()` /
  `AdapterDescribe.make(String)` fix); bundled `lib/vcfcf-adapter-base.jar`
  sha256 `4eabad523a30ed547b5aa8987b26d1517dc9d7c89c87a6217ac642ebb3734c53`.

## 99.0.0.6 (2026-06-25)

- fix(framework): dev preview build against the rebuilt
  `vcfcf-adapter-base.jar` carrying the `onDescribe()` localization fix
  (`AdapterDescribe.make(String)` swap). Every SDK pak bundles this base
  jar, so it must be rebuilt to carry the fix; bundled
  `lib/vcfcf-adapter-base.jar` sha256
  `4eabad523a30ed547b5aa8987b26d1517dc9d7c89c87a6217ac642ebb3734c53`.
  No UniFi adapter source change. Version major set to `99` to visually
  mark this as a hand-built local dev preview, distinct from the CI/release
  `1.0.0.x` line.

## 1.0.0.5 (2026-06-10)

- fix(framework): pick up the fixed `vcfcf-adapter-base.jar` (build 5) — this jar
  alone fixes relationship edge persistence and enables single-retry-on-401. Edge
  persistence: `RelationshipBuilder.resource()` now builds
  `new ResourceKey(name, kind, adapterKind)` (was the swapped
  `(adapterKind, kind, name)` arg order, which corrupted the field
  `ResourceKey.compareTo` checks first, so every relationship endpoint failed to
  bind to its registered resource and all edges were dropped at persist time).
  401 recovery: `ManagedHttpClient.checkAuthRetry` + `SessionCookieAuth`
  (`shouldRetryAfterStatus(401/403)` → `invalidateAuth()` + single replay) now
  recover from an expired `TOKEN` cookie automatically — the build-4 failure where
  an expired session 401-stormed the instance to ERROR indefinitely. No UniFi
  adapter source change was needed for either fix; `buildRelationships()` logic is
  unchanged (the edges were always built correctly — persistence was the framework
  bug). Bundled-jar bytecode verified: `javap` on `RelationshipBuilder.resource()`
  shows the corrected name/kind/adapterKind arg order.
- fix(adapter): session/401 recovery audit (no code change required). Every UniFi
  Network-API and Protect-API call routes through `ManagedHttpClient` with
  `SessionCookieAuth` as the wired strategy, so the framework 401 retry fires on
  all of them. UniFi caches no CSRF token client-side: its reads are cookie-only
  GETs (CSRF is not required for UniFi-OS GETs), and `login()` is the only POST —
  so a re-login refreshes the only session artifact (the `TOKEN` cookie) and
  recovers cleanly with no stale-CSRF replay risk. Redaction
  (TOKEN/unifises/password) unchanged.
- refactor(adapter): adopt framework §22 collect-path discovery plumbing. Replaced
  the build-4 hand-rolled `needsRediscovery()=true`/`rediscover()` pair and the
  private `ResourceSink` inner interface with the framework opt-in
  `discoverOnCollect() { return true; }` over a single `protected @Override
  enumerateResources(com.vcfcf.adapter.spi.ResourceSink)`; deleted the
  `getDiscoverer()` override (the framework default wires the same body for the
  `onDiscover()` path). Behaviour identical, less code. Resource keys (`rcOf`)
  byte-identical to build 4 — existing devel resources do not duplicate.

## 1.0.0.4 (2026-06-10)

- fix(adapter): collect-path discovery (build 4) — VCF Ops 9.0.2 never invokes
  `onDiscover()` for this adapter3-type collector, so build 3 heartbeat GREEN but
  discovered zero resources. The collector now overrides `needsRediscovery()=true`
  and runs discovery on the collect path via `rediscover()` →
  `registerNewResource(rc)`; the framework runs that before the per-resource
  collect loop on the first cycle after configure, so a freshly-configured
  instance populates on its first collect. `getDiscoverer()` stays wired to the
  same shared `enumerateResources()` body (one enumeration, two callers — no
  drift) for forward compatibility and platforms that do call `onDiscover()`.
  Java-only change; describe.xml, resource kinds/identifiers, and metric keys
  unchanged (pak-compare vs build 3: 0 BLOCKING / 0 WARNING).

## 1.0.0.3 (2026-06-10)

- feat(adapter): framework v2 migration (build 3) — re-home from aria-ops-core
  (`UnlicensedAdapter` + `com.vmware.tvs.*`) onto `VcfCfAdapter`/`AdapterBase` and
  the `com.vcfcf.adapter.spi` roles (`VcfCfTester`/`VcfCfDiscoverer`/`VcfCfCollector`).
  Keyed constructors `super(ADAPTER_KIND[, ...])`, no `onDescribe` override,
  `componentLogger` for all helper loggers, C2 pak shape (lib/ = vcfcf-adapter-base.jar).
- feat(adapter): per-cycle snapshot-cache collect idiom — one UniFi Network/Protect
  pull per cycle shared across all per-resource `collect(rc)` calls; full topology
  emitted on the World resource. Same resource kinds / identifiers / metric keys and
  describe.xml as v1.
- feat(adapter): LLDP→ESXi HostSystem cross-link rewired through the ambient
  `SuiteApiStitcher` + `UniFiStitcher` (resolve by `VMEntityName` against real
  VMWARE inventory, `parentForeign(host, port)`); WARN-and-skip when the Suite API
  is unavailable, matching v1's optional semantics.
- fix(adapter): unreadable-is-loud — a failed snapshot refresh or an unreadable
  `/self/sites` payload throws out of `collect()` (resource ERROR/DOWN, no 0.0
  sentinels); secrets (TOKEN/unifises session cookie, login password) redacted from
  every path/body-bearing error and log.

## 1.0.0.2 (2026-05-19)

- feat(unifi): UniFi Controller SDK adapter build 2 + auto-doc generation
- release: unifi-controller-managementpack 1.0
