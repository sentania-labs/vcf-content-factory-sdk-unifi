# Changelog

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
