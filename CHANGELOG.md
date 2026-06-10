# Changelog

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
