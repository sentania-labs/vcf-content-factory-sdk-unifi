# Changelog

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
