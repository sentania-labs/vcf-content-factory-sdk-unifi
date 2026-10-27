package com.vcfcf.adapters.unifi;

import com.vcfcf.adapter.VcfCfAdapter;
import com.vcfcf.adapter.auth.SessionCookieAuth;
import com.vcfcf.adapter.http.HttpClientBuilder;
import com.vcfcf.adapter.http.ManagedHttpClient;
import com.vcfcf.adapter.json.SimpleJson;
import com.vcfcf.adapter.retry.RetryPolicy;
import com.vcfcf.adapter.spi.ResourceSink;
import com.vcfcf.adapter.spi.VcfCfCollector;
import com.vcfcf.adapter.spi.VcfCfTester;
import com.vcfcf.adapter.stitch.RelationshipBuilder;
import com.vcfcf.adapter.stitch.SuiteApiStitcher;

import com.integrien.alive.common.adapter3.AdapterBase;
import com.integrien.alive.common.adapter3.Logger;
import com.integrien.alive.common.adapter3.MetricData;
import com.integrien.alive.common.adapter3.MetricKey;
import com.integrien.alive.common.adapter3.Relationships;
import com.integrien.alive.common.adapter3.ResourceKey;
import com.integrien.alive.common.adapter3.ResourceStatus;
import com.integrien.alive.common.adapter3.TestParam;
import com.integrien.alive.common.adapter3.config.AdapterConfig;
import com.integrien.alive.common.adapter3.config.ResourceConfig;
import com.integrien.alive.common.adapter3.config.ResourceIdentifierConfig;
import com.integrien.alive.common.util.CommonConstants.ResourceStatusEnum;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UniFi Controller adapter — framework v2 (build 5).
 *
 * <p><b>Build 5 — framework-plumbed collect-path discovery + framework 401
 * recovery + edge persistence.</b> Three changes, all behaviour-preserving:
 *
 * <ul>
 *   <li><b>Edge persistence + 401 recovery (framework jar pickup, no adapter
 *       code).</b> Build 4 edges left the adapter correct but were dropped at
 *       platform persistence by the framework {@code RelationshipBuilder}
 *       ResourceKey arg-order bug (now {@code new ResourceKey(name, kind,
 *       adapterKind)}); and an expired {@code TOKEN} cookie was never
 *       invalidated, 401-storming the instance to ERROR indefinitely. Both are
 *       fixed in the bundled {@code vcfcf-adapter-base.jar}: every UniFi call
 *       routes through {@code ManagedHttpClient} with {@code SessionCookieAuth},
 *       so {@code ManagedHttpClient.checkAuthRetry} now invalidates the cookie
 *       and replays once on 401/403. UniFi caches no CSRF token (its reads are
 *       cookie-only GETs — see {@link UniFiApiClient}), so a re-login refreshes
 *       the only session artifact and recovers cleanly. No adapter source change
 *       was needed for either fix.</li>
 *   <li><b>Discovery is now framework-plumbed (§22).</b> The build-4 hand-rolled
 *       {@code needsRediscovery()=true}/{@code rediscover()} dual-path pattern is
 *       replaced by the framework opt-in {@link #discoverOnCollect()}{@code =true}
 *       over a single {@code protected} {@link #enumerateResources(ResourceSink)}.
 *       The framework default {@code getDiscoverer()} wires the same body for the
 *       {@code onDiscover()} path. Behaviour is identical; the resource keys
 *       ({@link #rcOf}) are byte-identical to build 4, so existing resources do
 *       not duplicate.</li>
 * </ul>
 *
 * <p><b>v1 → v2 SPI port.</b> Re-homed from aria-ops-core
 * ({@code UnlicensedAdapter} + {@code com.vmware.tvs.*}) onto {@link VcfCfAdapter}
 * (which extends {@code AdapterBase} directly) and the {@code com.vcfcf.adapter.spi}
 * roles: {@link VcfCfTester}, {@link VcfCfCollector}. No
 * {@code com.vmware.tvs.*}, no {@code Resource}/{@code ResourceCollection}, no
 * JAX-WS. Transport is the framework {@link ManagedHttpClient} over the existing
 * UniFi Network/Protect REST client ({@link UniFiApiClient}); the
 * {@link SessionCookieAuth} session model carries over functionally. The typed
 * {@link UniFiConfig} POJO and describe.xml are unchanged.
 *
 * <p><b>vCenter-side vmnic→port stitch (build 9 — replaces the dead
 * controller-side LLDP read).</b> Builds ≤8 tried to stitch each switch port to
 * a VMWARE {@code HostSystem} named by the port's own LLDP neighbour
 * ({@code port_table[].lldp_table[].lldp_system_name}), read off the UniFi
 * controller. On Network App 10.2.105 that table is empty for every port — the
 * controller never re-publishes the switch's own LLDP daemon's neighbours over
 * any API surface (classic / v2 / Integration); see
 * {@code context/investigations/unifi-lldp-switchport-esxi-2026-07-05.md}. Build
 * 9 inverts the join: it enumerates VMWARE {@code HostSystem} resources over the
 * ambient {@link SuiteApiStitcher} and reads each host's per-vmnic LLDP
 * properties (published by vCenter/ESXi — {@code net:vmnic<N>|discoveryProtocol|
 * lldp|systemName} / {@code ...|portName}), then matches the joint
 * {@code (switchName, portName)} pair back into the adapter's own UniFiSwitch /
 * UniFiSwitchPort inventory (see {@link UniFiStitcher} and
 * {@link #emitVmnicHostStitch}). Same optional semantics as before: WARN-and-
 * skip when the Suite API is unavailable, and a match/fetch failure never costs
 * the cycle its internal topology. The edge is still emitted as
 * {@code parentForeign(host, switchPort)} — the host is the foreign parent of
 * the (local) UniFi switch port.
 *
 * <p><b>Per-resource collect reshape (snapshot cache).</b> v1 collected the whole
 * topology in one pass; v2 calls {@code collect(rc)} once per discovered resource.
 * To preserve v1's single-pull semantics and value parity, a per-cycle
 * {@link Snapshot} caches the UniFi API responses (sites, per-site devices/health,
 * Protect bootstrap); each {@code collect(rc)} dispatches on the resource kind and
 * serves from the snapshot. Relationships are emitted as the full topology on the
 * World resource's {@code collectRelationships} call (v1 set
 * {@code shouldForceUpdateRelationships=true} — full-set every cycle).
 *
 * <p><b>Unreadable is not invisible.</b> A REST/session failure on the per-cycle
 * snapshot refresh — or a {@code /self/sites} payload that carries no readable
 * site list — throws out of {@code collect()} so the framework marks the resource
 * ERROR/DOWN (never a silent empty result, never a 0.0 sentinel). The Protect
 * sub-tree is genuinely optional (no NVR on most controllers): a Protect failure
 * is logged INFO and the NVR/camera resources are simply absent, exactly as v1.
 */
public final class UniFiAdapter extends VcfCfAdapter<UniFiConfig> {

	private static final String ADAPTER_KIND = "unifi_controller";

	/**
	 * Build 14 (issue #11): the API client and the snapshot cache of one
	 * configuration, published together by one volatile write in
	 * {@link #configureAdapter}. An instance edit publishes a new holder, so
	 * a cached failure (and its login back-off) never outlives the settings
	 * it failed with, and a call never pairs one configuration's client with
	 * another's cache. Null until the first configure.
	 */
	private volatile Configured configured;

	/**
	 * Ambient Suite API transport for the optional vmnic→port stitch, and the
	 * UniFi-specific resolver over it. Both null when the Suite API is
	 * unavailable (remote collector with no {@code maintenanceuser.properties}) —
	 * the stitch is then skipped for the cycle and collection proceeds.
	 */
	private volatile SuiteApiStitcher suiteStitcher;
	private volatile UniFiStitcher stitcher;

	/**
	 * Per-cycle snapshot of the UniFi API responses, shared across all
	 * per-resource {@code collect()} calls within one collection cycle, lives in
	 * the {@link SnapshotCache} of the current {@link Configured} holder.
	 *
	 * <p>Build 14 (issue #12): one build per collection cycle. The cycle, not
	 * the clock, decides reuse; this age (counted from the end of the build)
	 * is only a safety net that rebuilds a snapshot in a cycle that has run
	 * this long. Before build 14 the snapshot was rebuilt after 60 s counted
	 * from the start of its build, so a build slower than 60 s was rebuilt by
	 * every resource.
	 */
	private static final long MAX_SNAPSHOT_AGE_MS = 15L * 60_000L;

	/**
	 * Build 14 (issue #11): after a failed snapshot build that is not an
	 * authentication refusal, no new build before this has passed (and never
	 * in the same cycle). Shorter than the 5-minute default collection
	 * interval, so an unreachable controller is tried once per cycle.
	 */
	private static final long FAILURE_RETRY_BACKOFF_MS = 60_000L;

	/**
	 * Build 14 (issue #11): after a 401, 403 or 429, no new build (and so no
	 * login) before this has passed, unless the instance is edited. At the
	 * default 5-minute interval that is one failed login every fourth cycle
	 * instead of one per resource per cycle.
	 */
	private static final long AUTH_FAILURE_BACKOFF_MS = 15L * 60_000L;

	/**
	 * Build 15 (issue #21): the vmnic stitch's Suite API host list is
	 * fetched at most this often and reused between fetches. LLDP
	 * neighbours change rarely; before build 15 it was fetched on every
	 * snapshot build (every cycle). The Protect bootstrap is not cached at
	 * all: it is fetched every cycle, as in build 14, so camera state and
	 * NVR storage are never older than one collection interval.
	 */
	private static final long SLOW_REFRESH_MS = 15L * 60_000L;

	/**
	 * Build 15 (issue #21): after a failed refresh the last good host list is
	 * reused for at most this long after it was fetched; past it the failure
	 * degrades as before (no stitch for the cycle).
	 */
	private static final long SLOW_MAX_STALE_MS = 60L * 60_000L;

	/**
	 * One configuration's API client, snapshot cache and the snapshot
	 * cache's logger (immutable). The logger is resolved once in
	 * configureAdapter and stored here: componentLogger resets the logger to
	 * INFO on every call, so a per-failure lookup would make the cache's
	 * DEBUG line unreachable even after an operator raised the level.
	 * Codex P1: also the per-site stat/device cool-downs, so an instance
	 * edit starts with none. Build 15 (issue #34): also the API client's
	 * logger, resolved once per configure for the same reason and shared by
	 * the client and its login callback.
	 */
	private static final class Configured {
		final UniFiApiClient api;
		final HttpClients clients;
		final Logger apiLog;
		final Logger snapshotLog;
		final Logger inventoryLog;
		final SnapshotCache<Snapshot> snapshots;
		final SiteReads.Cooldowns siteCooldowns;
		// Build 15 (issue #21): the host list is fetched at most every
		// SLOW_REFRESH_MS. The Protect bootstrap has no cache (every cycle).
		// Build 15 (peer review 4): stat/sysinfo back-off after a refusal.
		final SysinfoGate sysinfoGate = new SysinfoGate(
				System::currentTimeMillis, AUTH_FAILURE_BACKOFF_MS);
		final RefreshCache<List<UniFiStitcher.ForeignHost>> vmnicHosts =
				new RefreshCache<>(System::currentTimeMillis, SLOW_REFRESH_MS,
						SLOW_MAX_STALE_MS);

		Configured(UniFiApiClient api, HttpClients clients, Logger apiLog,
				Logger snapshotLog, Logger inventoryLog,
				SnapshotCache<Snapshot> snapshots,
				SiteReads.Cooldowns siteCooldowns) {
			this.api = api;
			this.clients = clients;
			this.apiLog = apiLog;
			this.snapshotLog = snapshotLog;
			this.inventoryLog = inventoryLog;
			this.snapshots = snapshots;
			this.siteCooldowns = siteCooldowns;
		}
	}

	/**
	 * Build 15 (issue #24): the authenticated client and the raw client its
	 * login callback posts the credentials with. Built and discarded as a
	 * pair, so the login-only client never outlives the configuration or
	 * the Test Connection that created it.
	 */
	private static final class HttpClients {
		final ManagedHttpClient main;
		final ManagedHttpClient login;

		HttpClients(ManagedHttpClient main, ManagedHttpClient login) {
			this.main = main;
			this.login = login;
		}

		/** Discards both; a fault in one never skips the other. */
		void discard() {
			discardQuietly(main);
			discardQuietly(login);
		}

		private static void discardQuietly(ManagedHttpClient c) {
			if (c == null) return;
			try {
				c.discard();
			} catch (RuntimeException ignored) {
				// Release is best effort; nothing to report to the caller.
			}
		}
	}

	public UniFiAdapter() {
		super(ADAPTER_KIND);
	}

	public UniFiAdapter(String adapterDir, Integer adapterInstanceId) {
		super(ADAPTER_KIND, adapterDir, adapterInstanceId);
	}

	// -----------------------------------------------------------------------
	// onDescribe — framework default (resolves describe.xml from the
	// constructor-stored ADAPTER_KIND; safe under controller-side bare
	// instantiation where getAdapterKind() is null). Do NOT implement.
	// See lessons/controller-describe-bare-instantiation.md.
	// -----------------------------------------------------------------------

	// -----------------------------------------------------------------------
	// configureAdapter (replaces v1 configure)
	// -----------------------------------------------------------------------

	@Override
	protected void configureAdapter(ResourceStatus status,
			ResourceConfig resourceConfig) {
		UniFiConfig cfg = buildConfig(resourceConfig);
		this.config = cfg;
		// Build 15 (issue #24): release what the previous configuration
		// built. VcfCfAdapter.onConfigure has already discarded the previous
		// main client (this.httpClient); its login-only client and the Suite
		// API stitcher are this adapter's to release.
		discardConfigured();
		// Resolved once per configure, never per failure or per login (see
		// Configured; build 15, issue #34 for the API client's logger).
		Logger apiLog = componentLogger(UniFiApiClient.class);
		Logger snapshotLog = componentLogger(SnapshotCache.class);
		// Build 15 (issue #23): DEBUG lines for devices, ports and cameras
		// skipped for having no identity.
		Logger inventoryLog = componentLogger(UniFiIds.class);
		HttpClients clients = buildHttpClient(cfg, apiLog);
		this.httpClient = clients.main;
		this.configured = new Configured(
				new UniFiApiClient(clients.main, apiLog),
				clients,
				apiLog,
				snapshotLog,
				inventoryLog,
				new SnapshotCache<>(System::currentTimeMillis,
						MAX_SNAPSHOT_AGE_MS, FAILURE_RETRY_BACKOFF_MS,
						AUTH_FAILURE_BACKOFF_MS, snapshotLog::debug),
				// Codex P1: same windows as the snapshot back-off.
				new SiteReads.Cooldowns(System::currentTimeMillis,
						AUTH_FAILURE_BACKOFF_MS, FAILURE_RETRY_BACKOFF_MS));

		// Optional vmnic→port stitch transport. Ambient mode — no describe.xml
		// credential fields. create() reads maintenanceuser.properties and
		// targets the local Suite API; on a remote collector that file is
		// absent and create() throws. Degrade exactly as build ≤8 did: WARN
		// once, leave the stitcher null, and let the cycle complete with all
		// UniFi resources collecting normally and only the stitch skipped.
		try {
			this.suiteStitcher = SuiteApiStitcher.create(this,
					componentLogger(SuiteApiStitcher.class));
			this.stitcher = new UniFiStitcher(this.suiteStitcher,
					componentLogger(UniFiStitcher.class));
		} catch (RuntimeException e) {
			this.suiteStitcher = null;
			this.stitcher = null;
			logWarn("vmnic→port stitch skipped — Suite API unavailable "
					+ "(remote collector without maintenanceuser.properties?): "
					+ e.getMessage());
		}

		logInfo("UniFiAdapter configured: host=" + cfg.host + " port=" + cfg.port
				+ " allowInsecure=" + cfg.allowInsecure
				+ " vmnicHostStitch=" + (stitcher != null));
		// Build 14 (issue #13): one WARN per configure while insecure mode is on.
		String insecure = cfg.insecureWarning();
		if (insecure != null) {
			logWarn(insecure);
		}
	}

	private UniFiConfig buildConfig(ResourceConfig rc) {
		String host = getIdentifier(rc, "host");
		String port = getIdentifier(rc, "port");
		String allowInsecure = getIdentifier(rc, "allowInsecure");
		String username = getCredentialField(rc, "username");
		String password = getCredentialField(rc, "password");
		return new UniFiConfig(host, port, username, password, allowInsecure);
	}

	/**
	 * @param apiLog the API client's logger, resolved once by the caller
	 *        (build 15, issue #34): the login callback runs on every login
	 *        and must not look it up again, since each lookup resets the
	 *        logger to INFO.
	 * @return the authenticated client with the login-only client it uses
	 *         (build 15, issue #24), so the caller discards both
	 */
	private HttpClients buildHttpClient(UniFiConfig cfg, Logger apiLog) {
		// Raw client for the login round-trip (no auth strategy attached yet).
		ManagedHttpClient rawHttp = baseBuilder(cfg).build();

		// Session cookie auth: TOKEN cookie minted by POST /api/auth/login. The
		// login closure captures only the raw client, the logger and the
		// credentials; the password never leaves this client.
		SessionCookieAuth auth = new SessionCookieAuth("TOKEN",
				() -> UniFiApiClient.login(rawHttp, apiLog,
						cfg.username, cfg.password));

		return new HttpClients(baseBuilder(cfg).auth(auth).build(), rawHttp);
	}

	private HttpClientBuilder baseBuilder(UniFiConfig cfg) {
		HttpClientBuilder b = HttpClientBuilder.builder()
				.baseUrl(cfg.baseUrl())
				.retryPolicy(RetryPolicy.builder()
						.maxAttempts(3)
						.baseDelayMs(1000)
						.build())
				.timeout(Duration.ofSeconds(30));
		if (cfg.allowInsecure) {
			// Lab opt-out: UniFi OS ships a self-signed cert by default.
			b.allowInsecure(true);
		} else {
			b.platformSsl(this);
		}
		return b;
	}

	// -----------------------------------------------------------------------
	// certificateCheckUrls (framework hook; build 14, review W1, issue #33)
	// -----------------------------------------------------------------------

	/**
	 * Build 14: the controller endpoint VCF Operations reviews on Validate
	 * Connection, so an untrusted (for example factory self-signed)
	 * certificate brings up the Review and Accept Certificate dialog instead
	 * of failing Test Connection. The accepted certificate is stored on the
	 * instance and trusted by {@code platformSsl(this)} on every cycle, with
	 * validation still on. Derived only from the passed, possibly unsaved
	 * config, never from instance state; empty when Allow Insecure SSL is on
	 * (read with this pak's own {@link UniFiConfig#parseAllowInsecure}, the
	 * parse the client uses, not the framework's trimmed one). See
	 * {@link UniFiConfig#certificateCheckUrls}. {@code @Override} on
	 * purpose, as compliance build 93: a buildkit older than 1.0.11 has no
	 * such hook and must fail to compile rather than build a pak that never
	 * offers the dialog.
	 */
	@Override
	protected List<String> certificateCheckUrls(ResourceConfig rc) {
		return UniFiConfig.certificateCheckUrls(getIdentifier(rc, "host"),
				getIdentifier(rc, "port"), getIdentifier(rc, "allowInsecure"));
	}

	// -----------------------------------------------------------------------
	// getTester — self-contained (controller calls it on a BARE instance:
	// configureAdapter has NOT run, so this.configured / this.config are null). Derive
	// everything from the ResourceConfig on the TestParam.
	// -----------------------------------------------------------------------

	@Override
	protected VcfCfTester<UniFiConfig> getTester() {
		return (cfg, http, param) -> {
			ResourceConfig rc = testResourceConfig(param);
			if (rc == null) {
				throw new Exception("Test-connection: no adapter-instance "
						+ "ResourceConfig available on TestParam — cannot read "
						+ "UniFi host/credentials to test");
			}
			UniFiConfig testCfg = buildConfig(rc);
			// Build 15 (issue #34): one logger for the test client and its
			// login callback. A configured instance reuses the one its
			// configure resolved; a bare instance (the usual Test Connection
			// caller) resolves it once here.
			Configured c = this.configured;
			Logger apiLog = c != null ? c.apiLog
					: componentLogger(UniFiApiClient.class);
			HttpClients testHttp = buildHttpClient(testCfg, apiLog);
			try {
				UniFiApiClient testApi = new UniFiApiClient(testHttp.main,
						apiLog);
				SimpleJson sites = testApi.listSites();
				SimpleJson data = sites.get("data");
				if (data.isNull() || !data.isList()) {
					throw new IOException("UniFi /self/sites returned a 200 payload "
							+ "with no readable 'data' site list");
				}
				logInfo("Test OK: connected to " + testCfg.host + ", "
						+ data.size() + " site(s)");
			} finally {
				// Build 15 (issue #24): the login-only client too.
				testHttp.discard();
			}
		};
	}

	private static ResourceConfig testResourceConfig(TestParam param) {
		if (param == null) return null;
		AdapterConfig adConf = param.getAdapterConfig();
		if (adConf == null) return null;
		return adConf.getAdapterInstResource();
	}

	// -----------------------------------------------------------------------
	// Discovery — framework-plumbed (§22 discoverOnCollect)
	// -----------------------------------------------------------------------

	/**
	 * Drive resource discovery from the collect path on every cycle.
	 *
	 * <p><b>Why (VCF Ops 9.0.2 root cause).</b> On 9.0.2 the server's "Auto
	 * Discover" task never reaches {@code onDiscover()} for this adapter3-type
	 * collector, so an instance would heartbeat GREEN but discover zero
	 * resources indefinitely (the build-3 symptom). Returning {@code true} here
	 * makes the framework call {@link #enumerateResources(ResourceSink)} with
	 * {@link #registerNewResource(ResourceConfig)} at the top of every collect
	 * cycle — before the per-resource collect loop — so a freshly-configured
	 * instance populates on its first collect. The platform de-duplicates
	 * already-known resources by their identifying identifiers, so
	 * re-enumerating every cycle is safe and cheap.
	 *
	 * <p>This replaces the build-4 hand-rolled {@code needsRediscovery()}/{@code
	 * rediscover()} pair with the framework opt-in (§22): identical behaviour,
	 * less code, single enumeration body shared with the {@code onDiscover()}
	 * path via the framework default {@code getDiscoverer()}.
	 */
	@Override
	protected boolean discoverOnCollect() {
		return true;
	}

	/**
	 * Build 14 (issue #11): a discovery pass is its own cycle for the
	 * snapshot cache, so a failure cached by a collection cycle stops
	 * blocking only by its back-off, not by a discovery call. Otherwise the
	 * framework default: {@link #enumerateResources(ResourceSink)} into the
	 * discovery result.
	 */
	@Override
	@SuppressWarnings("rawtypes")
	protected com.vcfcf.adapter.spi.VcfCfDiscoverer getDiscoverer() {
		return (cfg, http, param, dr) -> {
			beginCycle();
			enumerateResources(dr::addResource);
		};
	}

	/**
	 * Build 14 (issue #11): marks the start of a collection or discovery
	 * pass in the current configuration's snapshot cache. A no-op before
	 * the first configure.
	 */
	private void beginCycle() {
		Configured c = this.configured;
		if (c != null) {
			c.snapshots.beginCycle();
		}
	}

	/**
	 * The single, shared resource-tree enumeration. Walks the per-cycle UniFi
	 * snapshot and emits one {@link ResourceConfig} per resource to {@code sink}.
	 *
	 * <p><b>One body, two framework callers.</b> The framework default
	 * {@code getDiscoverer()} feeds each config to {@code DiscoveryResult.addResource}
	 * (the {@code onDiscover()} path); collect-path discovery (gated by
	 * {@link #discoverOnCollect()}) feeds each config to
	 * {@link #registerNewResource(ResourceConfig)} (the path VCF Ops 9.0.2
	 * actually exercises). Holding the enumeration in one method guarantees the
	 * two paths can never drift.
	 *
	 * <p>The snapshot is pulled internally via {@link #currentSnapshot()} (the
	 * same per-cycle snapshot the collect loop uses — no extra UniFi API calls).
	 * A snapshot/REST failure propagates so the framework treats it as a
	 * non-fatal rediscovery failure (WARN, keep existing resource set) rather
	 * than silently registering zero resources.
	 *
	 * @param sink consumes each enumerated {@link ResourceConfig}
	 */
	@Override
	protected void enumerateResources(ResourceSink sink)
			throws InterruptedException, Exception {
		Snapshot s = currentSnapshot();
		sink.accept(rcOf("UniFiWorld", "UniFi World", "world_id", "unifi_world"));

		int sites = 0, gateways = 0, switches = 0, ports = 0, aps = 0,
				radios = 0, wans = 0, aggregates = 0, cameras = 0, nvrs = 0;

		for (SimpleJson site : s.sites.get("data").asList()) {
			String siteName = site.get("name").asString();
			String siteDesc = site.get("desc").asString(siteName);
			sink.accept(rcOf("UniFiSite", siteDesc, "site_name", siteName));
			sites++;

			for (SimpleJson dev : s.devicesForSite(siteName).get("data").asList()) {
				String type = dev.get("type").asString("");
				// Build 15 (issue #23): no MAC, no identity; skipped here,
				// in buildRelationships and in the snapshot index alike
				// (Snapshot.build logs it at DEBUG).
				String mac = deviceMac(dev);
				if (mac == null) continue;
				// Codex P2: a device pending adoption is not registered (the
				// same rule as the site's Device Count; isDiscovered).
				if (!isDiscovered(dev)) continue;
				String name = deviceDisplayName(dev);

				if (isGateway(type)) {
					sink.accept(rcOf("UniFiGateway", name, "mac", mac));
					gateways++;
					if (!dev.get("wan1").isNull()) {
						sink.accept(rcOf("UniFiWanInterface", "WAN 1",
								"wan_name", mac + "_wan1"));
						wans++;
					}
					if (!dev.get("wan2").isNull()) {
						sink.accept(rcOf("UniFiWanInterface", "WAN 2",
								"wan_name", mac + "_wan2"));
						wans++;
					}
				} else if ("usw".equals(type)) {
					sink.accept(rcOf("UniFiSwitch", name, "mac", mac));
					switches++;
					SimpleJson portTable = dev.get("port_table");
					if (!portTable.isNull()) {
						for (SimpleJson port : portTable.asList()) {
							Integer idx = portIdx(port);
							if (idx == null) continue; // #23: never <mac>_0
							sink.accept(rcOf("UniFiSwitchPort",
									portDisplayName(port, idx),
									"port_key", mac + "_" + idx));
							ports++;
						}
					}
				} else if ("uap".equals(type)) {
					sink.accept(rcOf("UniFiAccessPoint", name, "mac", mac));
					aps++;
					SimpleJson radioStats = dev.get("radio_table_stats");
					if (!radioStats.isNull()) {
						for (SimpleJson radio : radioStats.asList()) {
							String radioCode = radioCode(radio);
							if (radioCode == null) continue; // peer review 7
							sink.accept(rcOf("UniFiRadio",
									radioDisplayName(radioCode),
									"radio_key", mac + "_" + radioCode));
							radios++;
						}
					}
				}
			}

			sink.accept(rcOf("UniFiWirelessAggregate", "Wireless Summary",
					"aggregate_id", siteName + "_wlan_aggregate"));
			aggregates++;
		}

		// Protect: NVR + cameras (optional sub-tree).
		if (s.protect != null) {
			SimpleJson nvr = s.protect.get("nvr");
			if (!nvr.isNull()) {
				// Peer review 7: an NVR with no MAC has no identity and is
				// not registered; its cameras still are (their own MAC).
				String nvrMac = UniFiIds.mac(nvr.get("mac").asString());
				if (nvrMac != null) {
					sink.accept(rcOf("UniFiNvr", nvr.get("name").asString("NVR"),
							"nvr_mac", nvrMac));
					nvrs++;
				}
				SimpleJson cams = s.protect.get("cameras");
				if (!cams.isNull()) {
					for (SimpleJson cam : cams.asList()) {
						String camMac = UniFiIds.mac(cam.get("mac").asString());
						if (camMac == null) continue; // #23
						sink.accept(rcOf("UniFiCamera",
								cam.get("name").asString(camMac),
								"camera_mac", camMac));
						cameras++;
					}
				}
			}
		}

		logInfo("UniFi enumerate: " + sites + " sites, " + gateways
				+ " gateways, " + wans + " wan-ifaces, " + switches
				+ " switches, " + ports + " ports, " + aps + " aps, "
				+ radios + " radios, " + aggregates + " aggregates, "
				+ nvrs + " nvr, " + cameras + " cameras");
	}

	private ResourceConfig rcOf(String kind, String name, String idKey, String idValue) {
		ResourceKey key = new ResourceKey(name, kind, ADAPTER_KIND);
		key.addIdentifier(new ResourceIdentifierConfig(idKey, idValue, true));
		return new ResourceConfig(key);
	}

	// -----------------------------------------------------------------------
	// getCollector — per-resource dispatch over a per-cycle snapshot
	// -----------------------------------------------------------------------

	@Override
	protected VcfCfCollector<UniFiConfig> getCollector() {
		return new VcfCfCollector<UniFiConfig>() {
			// Discovery is framework-plumbed via discoverOnCollect()=true over
			// enumerateResources(ResourceSink) (§22); there is no rediscover()
			// override here. The framework calls
			// enumerateResources(this::registerNewResource) at the top of every
			// cycle before this collect loop runs.

			/**
			 * Build 14 (issue #11): the framework calls this exactly once at
			 * the top of every onCollect, before discovery and the
			 * per-resource loop, so it is the start-of-cycle hook for the
			 * snapshot cache. Never asks for the hand-rolled rediscovery
			 * (always false), exactly as before.
			 */
			@Override
			public boolean needsRediscovery(UniFiConfig cfg) {
				// Rides on an undocumented side effect (called once per
				// onCollect); explicit start-of-cycle hook: vcf-content-factory#221.
				beginCycle();
				return false;
			}

			@Override
			public void collect(UniFiConfig cfg, ManagedHttpClient http,
					ResourceConfig rc, List<MetricData> out, AdapterBase adapter)
					throws InterruptedException, Exception {
				Snapshot snap = currentSnapshot();
				dispatchCollect(rc, snap, out);
			}

			@Override
			public Relationships collectRelationships(UniFiConfig cfg,
					ResourceConfig rc) {
				// Emit the full topology once per cycle, anchored on the World
				// resource (always present, always collected). Full-set every
				// cycle mirrors v1's shouldForceUpdateRelationships=true.
				if (!"UniFiWorld".equals(rc.getResourceKind())) {
					return null;
				}
				try {
					return buildRelationships(currentSnapshot());
				} catch (Exception e) {
					logWarn("Relationship build failed: " + e.getMessage());
					return null;
				}
			}

			/**
			 * Build 14 (issue #25): DOWN when the controller could not be
			 * reached (connect refused, connect or request timeout, unknown
			 * host, no route), found anywhere in the cause chain, including
			 * through the framework's login wrapper and a cached snapshot
			 * failure. ERROR for authentication refusals, certificate
			 * failures, unreadable payloads and anything else. Before build
			 * 14 only a bare ConnectException was DOWN.
			 */
			@Override
			public ResourceStatusEnum mapCollectException(Exception e) {
				if (UniFiFailures.isUnreachable(e)) {
					return ResourceStatusEnum.RESOURCE_STATUS_DOWN;
				}
				return ResourceStatusEnum.RESOURCE_STATUS_ERROR;
			}
		};
	}

	/**
	 * Return the snapshot for this cycle, building it on the cycle's first
	 * call (or after {@link #MAX_SNAPSHOT_AGE_MS} in an endless cycle). A
	 * refresh failure (session/REST error, or an unreadable
	 * {@code /self/sites} payload) propagates out so the framework marks the
	 * resource ERROR/DOWN, never a silent empty snapshot.
	 *
	 * <p>Build 14 (issue #11): a failed refresh is cached by the
	 * configuration's {@link SnapshotCache}: the first caller of the cycle
	 * (usually the collect-path discovery step) gets a one-line, stackless
	 * copy of the failure, and the remaining resources fail from the same
	 * kind of copy without contacting the controller (no new login), as
	 * does every call until its back-off has passed. The cache logs one
	 * "UniFi snapshot build failed" WARN, and the same line with the
	 * original exception and its stack at DEBUG through the logger stored
	 * in {@link Configured}; the framework still logs
	 * its own line for each resource that fails.
	 */
	private Snapshot currentSnapshot() throws Exception {
		Configured c = this.configured;
		if (c == null) {
			throw new IllegalStateException("UniFi adapter instance is not "
					+ "configured");
		}
		return c.snapshots.get(
				() -> Snapshot.build(c, this),
				this::logWarn);
	}

	private void dispatchCollect(ResourceConfig rc, Snapshot s, List<MetricData> out)
			throws IOException {
		String kind = rc.getResourceKind();
		if (kind == null) return;
		switch (kind) {
			case "UniFiWorld":
				break; // keepalive only (v1 parity)
			case "UniFiSite":
				collectSite(rc, s, out);
				break;
			case "UniFiGateway":
				collectGateway(rc, s, out);
				break;
			case "UniFiWanInterface":
				collectWanInterface(rc, s, out);
				break;
			case "UniFiSwitch":
				collectSwitch(rc, s, out);
				break;
			case "UniFiSwitchPort":
				collectSwitchPort(rc, s, out);
				break;
			case "UniFiAccessPoint":
				collectAccessPoint(rc, s, out);
				break;
			case "UniFiRadio":
				collectRadio(rc, s, out);
				break;
			case "UniFiWirelessAggregate":
				collectWirelessAggregate(rc, s, out);
				break;
			case "UniFiNvr":
				collectNvr(rc, s, out);
				break;
			case "UniFiCamera":
				collectCamera(rc, s, out);
				break;
			default:
				logWarn("collect: unknown resource kind " + kind);
		}
	}

	// -----------------------------------------------------------------------
	// MetricData append helpers
	// -----------------------------------------------------------------------

	private static void metric(List<MetricData> out, String key, double value) {
		out.add(new MetricData(new MetricKey(key), System.currentTimeMillis(), value));
	}

	/**
	 * Build 15 (issue #14): publishes the field only when it is present and
	 * a number (see {@link UniFiValues#number}); never 0.0 for an absent or
	 * unreadable field, which is what {@code SimpleJson.asDouble()} returns.
	 */
	private static void metricIfPresent(List<MetricData> out, String key,
			SimpleJson field) {
		metricIfPresent(out, key, UniFiValues.number(field.asString()));
	}

	/** Build 15 (issue #14): publishes {@code value} unless it is null. */
	private static void metricIfPresent(List<MetricData> out, String key,
			Double value) {
		if (value != null) metric(out, key, value);
	}

	/**
	 * Build 15 (issue #14): a satisfaction score, skipped when absent or
	 * -1 (UniFi's "no data"); see {@link UniFiValues#satisfaction}.
	 */
	private static void satisfactionIfPresent(List<MetricData> out, String key,
			SimpleJson field) {
		metricIfPresent(out, key, UniFiValues.satisfaction(field.asString()));
	}

	/**
	 * Build 15 (issue #14, peer review 5): {@code Status|state} (1 connected,
	 * 0 not) and {@code Status|state_code} (the state's short name) for a
	 * gateway, switch or access point, nothing when the state is unknown.
	 * Returns whether its performance metrics are published: only in an
	 * informing state (1, 4, 5, 7) or an unknown one; see
	 * {@link UniFiValues#publishesPerformance}.
	 */
	private static boolean publishState(List<MetricData> out, SimpleJson dev) {
		String raw = dev.get("state").asString();
		metricIfPresent(out, "Status|state",
				UniFiValues.stateMetric(UniFiValues.connected(raw)));
		String name = UniFiValues.stateName(raw);
		if (name != null) prop(out, "Status|state_code", name);
		return UniFiValues.publishesPerformance(raw);
	}

	/**
	 * Build 15 (issue #14, peer review 5): whether a port, radio or WAN of
	 * this device publishes metrics (the device's state decides).
	 */
	private static boolean devicePerformance(SimpleJson dev) {
		return UniFiValues.publishesPerformance(dev.get("state").asString());
	}

	/**
	 * Build 15 (issue #14): the {@code system-stats} block shared by the
	 * gateway, switch and access point. UDM sends cpu and mem as strings.
	 */
	private static void systemStats(List<MetricData> out, SimpleJson dev) {
		SimpleJson ss = dev.get("system-stats");
		metricIfPresent(out, "System|cpu_pct", ss.get("cpu"));
		metricIfPresent(out, "System|mem_pct", ss.get("mem"));
		metricIfPresent(out, "System|uptime", dev.get("uptime"));
		metricIfPresent(out, "System|num_sta", dev.get("num_sta"));
	}

	/**
	 * Build 15 (issue #16): a numeric property (describe.xml
	 * {@code dataType="float" isProperty="true"}).
	 */
	private static void numericProp(List<MetricData> out, String key, double value) {
		out.add(new MetricData(new MetricKey(true, key),
				System.currentTimeMillis(), value));
	}

	private static void prop(List<MetricData> out, String key, String value) {
		out.add(new MetricData(new MetricKey(true, key),
				System.currentTimeMillis(), value != null ? value : ""));
	}

	/**
	 * Build 17 (review W2 on build 16): a boolean property, "true" or
	 * "false", published only when the controller sent the field
	 * (UniFiValues.flag). An absent field publishes nothing, never "false",
	 * because "false" on these keys drives the bundled alerts.
	 */
	private static void flagPropIfPresent(List<MetricData> out, String key,
			SimpleJson field) {
		String value = UniFiValues.flag(field.asString());
		if (value != null) prop(out, key, value);
	}

	/** Build 17: a text property published only when present and not blank. */
	private static void textPropIfPresent(List<MetricData> out, String key,
			SimpleJson field) {
		String value = UniFiValues.text(field.asString());
		if (value != null) prop(out, key, value);
	}

	// -----------------------------------------------------------------------
	// Per-kind collectors (value semantics preserved from v1)
	// -----------------------------------------------------------------------

	private void collectSite(ResourceConfig rc, Snapshot s, List<MetricData> out)
			throws IOException {
		String siteName = getIdentifier(rc, "site_name");
		s.requireSiteRead(siteName);
		SimpleJson site = s.findSite(siteName);
		if (site == null) {
			logWarn("collect: site " + siteName + " absent from snapshot");
			return;
		}
		prop(out, "Configuration|description", site.get("desc").asString(""));
		// Build 15 (issue #18): counted from this cycle's stat/device for the
		// site (the site was read: requireSiteRead above), not read from the
		// /self/sites entry. Review N8, peer review 6, Codex P2: counts
		// exactly the devices discovery registers (isDiscovered: a gateway,
		// switch or access point with a MAC, not pending adoption; a device
		// without the "adopted" field counts as adopted).
		int adopted = 0;
		for (SimpleJson dev : s.devicesForSite(siteName).get("data").asList()) {
			if (isDiscovered(dev)) adopted++;
		}
		prop(out, "Configuration|device_count", String.valueOf(adopted));
		// Build 15 (issue #18): version (Network application) and timezone
		// from stat/sysinfo, each published only when the controller sent
		// it; absent when sysinfo could not be read this cycle.
		SimpleJson info = s.sysinfo(); // the controller's (peer review 4)
		if (info != null) {
			String version = info.get("version").asString("");
			if (!version.isEmpty()) prop(out, "Configuration|version", version);
			String timezone = info.get("timezone").asString("");
			if (!timezone.isEmpty()) prop(out, "Configuration|timezone", timezone);
		}
	}

	private void collectGateway(ResourceConfig rc, Snapshot s, List<MetricData> out)
			throws IOException {
		String mac = getIdentifier(rc, "mac");
		SimpleJson dev = s.findDevice(mac);
		if (dev == null) {
			logWarn("collect: gateway " + mac + " absent from snapshot");
			return;
		}

		// Build 15 (issue #14): state first; a gateway not informing
		// the controller (state other than 1, 4, 5, 7)
		// publishes state and properties only, and every metric is
		// published only when its field is present.
		if (publishState(out, dev)) {
			systemStats(out, dev);

			for (SimpleJson t : dev.get("temperatures").asList()) {
				String tName = t.get("name").asString("");
				SimpleJson tValue = t.get("value");
				if ("CPU".equals(tName)) metricIfPresent(out, "Temperature|temp_cpu", tValue);
				else if ("Local".equals(tName)) metricIfPresent(out, "Temperature|temp_local", tValue);
				else if ("PHY".equals(tName)) metricIfPresent(out, "Temperature|temp_phy", tValue);
			}

			SimpleJson st = dev.get("speedtest-status");
			metricIfPresent(out, "Speedtest|xput_up", st.get("xput_upload"));
			metricIfPresent(out, "Speedtest|xput_down", st.get("xput_download"));
			metricIfPresent(out, "Speedtest|speedtest_latency", st.get("latency"));
		}

		prop(out, "Configuration|model", dev.get("model").asString(""));
		prop(out, "Configuration|firmware", dev.get("version").asString(""));
		prop(out, "Configuration|serial", dev.get("serial").asString(""));
		prop(out, "Configuration|ip", dev.get("ip").asString(""));
		prop(out, "Configuration|mac_address", mac);
		prop(out, "Configuration|name", dev.get("name").asString(""));
	}

	private void collectWanInterface(ResourceConfig rc, Snapshot s, List<MetricData> out)
			throws IOException {
		String key = getIdentifier(rc, "wan_name"); // "<gwMac>_wan1" | "<gwMac>_wan2"
		int sep = key.lastIndexOf('_');
		if (sep < 0) {
			logWarn("collect: malformed wan_name " + key);
			return;
		}
		String gwMac = key.substring(0, sep);
		String wanName = key.substring(sep + 1);
		SimpleJson dev = s.findDevice(gwMac);
		if (dev == null) {
			logWarn("collect: wan iface parent gateway " + gwMac + " absent from snapshot");
			return;
		}
		SimpleJson wan = dev.get(wanName);
		if (wan.isNull()) {
			logWarn("collect: wan " + key + " absent on gateway");
			return;
		}

		// Build 15 (issue #14): none of the WAN's metrics while its gateway
		// is not informing the controller (UniFiValues.
		// publishesPerformance); otherwise each only when present.
		if (devicePerformance(dev)) {
			// Cumulative counters (build 15, issue #16: unit bytes, was
			// declared bytes/s) and UniFi's own rates (issue #29).
			metricIfPresent(out, "Traffic|tx_bytes", wan.get("tx_bytes"));
			metricIfPresent(out, "Traffic|rx_bytes", wan.get("rx_bytes"));
			metricIfPresent(out, "Traffic|tx_rate", wan.get("tx_bytes-r"));
			metricIfPresent(out, "Traffic|rx_rate", wan.get("rx_bytes-r"));
			metricIfPresent(out, "Health|latency", wan.get("latency"));
			metricIfPresent(out, "Health|availability", wan.get("availability"));
			metricIfPresent(out, "Health|speed", wan.get("speed"));
		}

		// Build 17 (review B1 on build 16): the WAN's link state, so WAN
		// Degraded can be scoped to a WAN that is up (an unused second WAN
		// reads up false with availability 0). Only when the controller
		// sends it; published regardless of the gateway's state, like the
		// switch port's Status|up.
		flagPropIfPresent(out, "Status|up", wan.get("up"));

		prop(out, "Configuration|type", wan.get("type").asString(""));
		prop(out, "Configuration|ip", wan.get("ip").asString(""));
		prop(out, "Configuration|netmask", wan.get("netmask").asString(""));
		prop(out, "Configuration|gateway_ip", wan.get("gateway").asString(""));
		SimpleJson dns = wan.get("dns");
		if (!dns.isNull() && dns.size() > 0) {
			StringBuilder sb = new StringBuilder();
			for (SimpleJson d : dns.asList()) {
				if (sb.length() > 0) sb.append(", ");
				sb.append(d.asString());
			}
			prop(out, "Configuration|dns", sb.toString());
		}
	}

	private void collectSwitch(ResourceConfig rc, Snapshot s, List<MetricData> out)
			throws IOException {
		String mac = getIdentifier(rc, "mac");
		SimpleJson dev = s.findDevice(mac);
		if (dev == null) {
			logWarn("collect: switch " + mac + " absent from snapshot");
			return;
		}

		// Build 15 (issue #14): state first; a switch not informing
		// the controller (state other than 1, 4, 5, 7)
		// publishes state and properties only.
		boolean perf = publishState(out, dev);
		if (perf) {
			systemStats(out, dev);
			satisfactionIfPresent(out, "System|satisfaction", dev.get("satisfaction"));
		}

		// total_max_power is null on a non-PoE switch: no PoE budget metrics
		// then (before build 15 they were published as 0).
		Double maxPower = UniFiValues.number(dev.get("total_max_power").asString());
		boolean poeCapable = maxPower != null && maxPower > 0;
		if (perf) metricIfPresent(out, "PoE|total_max_power", maxPower);

		prop(out, "Configuration|model", dev.get("model").asString(""));
		prop(out, "Configuration|firmware", dev.get("version").asString(""));
		prop(out, "Configuration|serial", dev.get("serial").asString(""));
		prop(out, "Configuration|ip", dev.get("ip").asString(""));
		prop(out, "Configuration|mac_address", mac);
		prop(out, "Configuration|name", dev.get("name").asString(""));
		prop(out, "Configuration|poe_capable", poeCapable ? "true" : "false");
		prop(out, "Configuration|has_fan",
				dev.get("has_fan").asBoolean() ? "true" : "false");

		// PoE budget: sum each PoE port's draw (same fields collectSwitchPort
		// reads). Build 15 (issue #14): published only when at least one PoE
		// port reports its draw, and the remaining budget only when the
		// switch reports its maximum too.
		SimpleJson ports = dev.get("port_table");
		int portCount = ports.isNull() ? 0 : ports.size();
		double poeConsumption = 0;
		boolean poeReported = false;
		for (SimpleJson port : ports.asList()) {
			if (port.get("port_poe").asBoolean()) {
				Double draw = UniFiValues.number(port.get("poe_power").asString());
				if (draw != null) {
					poeConsumption += draw;
					poeReported = true;
				}
			}
		}
		prop(out, "Configuration|port_count", String.valueOf(portCount));
		if (perf && poeReported) {
			metric(out, "PoE|poe_consumption", poeConsumption);
			if (maxPower != null) {
				metric(out, "PoE|poe_budget_remaining", maxPower - poeConsumption);
			}
			// Build 16 (issue #30): share of the budget drawn, for the "PoE
			// budget nearly exhausted" alert. Only with a maximum above 0.
			metricIfPresent(out, "PoE|poe_budget_used_pct",
					UniFiValues.poeBudgetUsedPct(poeConsumption, maxPower));
		}
	}

	private void collectSwitchPort(ResourceConfig rc, Snapshot s, List<MetricData> out)
			throws IOException {
		String portKey = getIdentifier(rc, "port_key"); // "<switchMac>_<idx>"
		int sep = portKey.lastIndexOf('_');
		if (sep < 0) {
			logWarn("collect: malformed port_key " + portKey);
			return;
		}
		String switchMac = portKey.substring(0, sep);
		int idx = parseInt(portKey.substring(sep + 1));
		SimpleJson dev = s.findDevice(switchMac);
		if (dev == null) {
			logWarn("collect: switch-port parent switch " + switchMac + " absent");
			return;
		}
		SimpleJson port = findPort(dev, idx);
		if (port == null) {
			logWarn("collect: port " + portKey + " absent on switch");
			return;
		}

		// Build 15 (issue #14): none of the port's metrics while its switch
		// is not informing the controller (UniFiValues.
		// publishesPerformance); otherwise each only when present.
		boolean perf = devicePerformance(dev);
		if (perf) {
			metricIfPresent(out, "Traffic|tx_bytes", port.get("tx_bytes"));
			metricIfPresent(out, "Traffic|rx_bytes", port.get("rx_bytes"));
			// Build 15 (issue #29): UniFi's own rates next to the counters.
			metricIfPresent(out, "Traffic|tx_rate", port.get("tx_bytes-r"));
			metricIfPresent(out, "Traffic|rx_rate", port.get("rx_bytes-r"));
			metricIfPresent(out, "Traffic|tx_errors", port.get("tx_errors"));
			metricIfPresent(out, "Traffic|rx_errors", port.get("rx_errors"));

			satisfactionIfPresent(out, "Status|satisfaction", port.get("satisfaction"));
			// The size of mac_table when the port carries the list, else the
			// controller's own mac_table_count; neither present, nothing
			// (before build 15 a port without mac_table read 0).
			SimpleJson macTable = port.get("mac_table");
			if (macTable.isList()) {
				metric(out, "Status|mac_table_count", macTable.size());
			} else {
				metricIfPresent(out, "Status|mac_table_count",
						port.get("mac_table_count"));
			}
		}
		// Build 17 (review W2 on build 16): up and is_uplink only when sent.
		flagPropIfPresent(out, "Status|up", port.get("up"));
		// Review N1: blank when absent, never "0" (a down port reads 0).
		prop(out, "Status|speed", port.get("speed").asString(""));
		// Build 17: Full/Half only when full_duplex is sent (was Half when absent).
		String fullDuplex = UniFiValues.flag(port.get("full_duplex").asString());
		if (fullDuplex != null) {
			prop(out, "Status|duplex", "true".equals(fullDuplex) ? "Full" : "Half");
		}
		flagPropIfPresent(out, "Status|is_uplink", port.get("is_uplink"));
		prop(out, "Status|media", port.get("media").asString(""));
		prop(out, "Status|stp_state", port.get("stp_state").asString(""));
		prop(out, "Status|port_name", port.get("name").asString(""));

		if (port.get("port_poe").asBoolean()) {
			// poe_power / voltage / current are strings; parsed, and
			// published only when present (build 15, issue #14).
			if (perf) {
				metricIfPresent(out, "PoE|poe_power", port.get("poe_power"));
				metricIfPresent(out, "PoE|poe_voltage", port.get("poe_voltage"));
				metricIfPresent(out, "PoE|poe_current", port.get("poe_current"));
			}
			prop(out, "PoE|poe_enable",
					port.get("poe_enable").asBoolean() ? "true" : "false");
			prop(out, "PoE|poe_class", port.get("poe_class").asString(""));
			prop(out, "PoE|poe_mode", port.get("poe_mode").asString(""));
		}

		// LLDP|* (build 9 — repurposed): the controller's own port_table[].
		// lldp_table is empty on Network App 10.2.105 (investigation
		// context/investigations/unifi-lldp-switchport-esxi-2026-07-05.md), so
		// these properties are now populated from the vCenter-side vmnic→port
		// join computed once per snapshot refresh (Snapshot.build ->
		// computeVmnicStitch), the same join emitVmnicHostStitch uses to build
		// the HostSystem foreign-parent edge. Absent from the map (no matched
		// vmnic for this port this cycle, OR a build-11 conflicted portKey —
		// two different hosts both claimed it this cycle) → no LLDP
		// properties this cycle, not a sentinel.
		String[] matched = s.vmnicLldpByPortKey.get(portKey);
		if (matched != null) {
			prop(out, "LLDP|lldp_system_name", matched[0]);
			prop(out, "LLDP|lldp_port_id", matched[1]);
		}
	}

	private void collectAccessPoint(ResourceConfig rc, Snapshot s, List<MetricData> out)
			throws IOException {
		String mac = getIdentifier(rc, "mac");
		SimpleJson dev = s.findDevice(mac);
		if (dev == null) {
			logWarn("collect: access-point " + mac + " absent from snapshot");
			return;
		}

		// Build 15 (issue #14): state first; a access point not informing
		// the controller (state other than 1, 4, 5, 7)
		// publishes state and properties only.
		if (publishState(out, dev)) {
			systemStats(out, dev);
			satisfactionIfPresent(out, "System|satisfaction", dev.get("satisfaction"));
		}

		prop(out, "Configuration|model", dev.get("model").asString(""));
		prop(out, "Configuration|firmware", dev.get("version").asString(""));
		prop(out, "Configuration|serial", dev.get("serial").asString(""));
		prop(out, "Configuration|ip", dev.get("ip").asString(""));
		prop(out, "Configuration|mac_address", mac);
		prop(out, "Configuration|name", dev.get("name").asString(""));
	}

	private void collectRadio(ResourceConfig rc, Snapshot s, List<MetricData> out)
			throws IOException {
		String radioKey = getIdentifier(rc, "radio_key"); // "<apMac>_<radioCode>"
		int sep = radioKey.lastIndexOf('_');
		if (sep < 0) {
			logWarn("collect: malformed radio_key " + radioKey);
			return;
		}
		String apMac = radioKey.substring(0, sep);
		String radioCode = radioKey.substring(sep + 1);
		SimpleJson dev = s.findDevice(apMac);
		if (dev == null) {
			logWarn("collect: radio parent ap " + apMac + " absent");
			return;
		}
		SimpleJson radio = findRadioStats(dev, radioCode);
		if (radio == null) {
			logWarn("collect: radio " + radioKey + " absent on ap");
			return;
		}

		// Build 15 (issue #14): none of the radio's metrics while its access
		// point is not informing the controller (UniFiValues.
		// publishesPerformance); otherwise each only when present.
		boolean perf = devicePerformance(dev);
		if (perf) {
			metricIfPresent(out, "RF|channel", radio.get("channel"));
			metricIfPresent(out, "RF|tx_power", radio.get("tx_power"));
			metricIfPresent(out, "RF|cu_total", radio.get("cu_total"));
			satisfactionIfPresent(out, "RF|satisfaction", radio.get("satisfaction"));
			metricIfPresent(out, "RF|tx_retries_pct", radio.get("tx_retries_pct"));

			metricIfPresent(out, "Clients|user_num_sta", radio.get("user-num_sta"));
		}

		String radioName = radio.get("name").asString("");
		if (perf && !radioName.isEmpty()) {
			SimpleJson statAp = dev.get("stat").get("ap");
			metricIfPresent(out, "Traffic|tx_bytes",
					statAp.get(radioName + "-tx_bytes"));
			metricIfPresent(out, "Traffic|rx_bytes",
					statAp.get(radioName + "-rx_bytes"));
			// Build 15 (issue #29): the radio's rate, next to its counters,
			// when the access point reports one (key unproven on a live
			// controller; nothing is published when it is absent).
			metricIfPresent(out, "Traffic|tx_rate",
					statAp.get(radioName + "-tx_bytes-r"));
			metricIfPresent(out, "Traffic|rx_rate",
					statAp.get(radioName + "-rx_bytes-r"));
		}

		prop(out, "Configuration|radio_type", radioCode);
		prop(out, "Configuration|radio_name", radioName);
		SimpleJson radioTable = dev.get("radio_table");
		if (!radioTable.isNull()) {
			for (SimpleJson rt : radioTable.asList()) {
				if (radioCode.equals(rt.get("radio").asString(""))) {
					prop(out, "Configuration|ht", rt.get("ht").asString(""));
					// Review N1: blank when absent, never "0".
					prop(out, "Configuration|min_txpower",
							rt.get("min_txpower").asString(""));
					prop(out, "Configuration|max_txpower",
							rt.get("max_txpower").asString(""));
					break;
				}
			}
		}
	}

	private void collectWirelessAggregate(ResourceConfig rc, Snapshot s,
			List<MetricData> out) throws IOException {
		String aggId = getIdentifier(rc, "aggregate_id"); // "<siteName>_wlan_aggregate"
		String siteName = aggId.endsWith("_wlan_aggregate")
				? aggId.substring(0, aggId.length() - "_wlan_aggregate".length())
				: aggId;
		s.requireSiteRead(siteName);
		SimpleJson health = s.healthForSite(siteName);
		if (health == null) {
			logWarn("collect: wireless aggregate health for site " + siteName
					+ " absent from snapshot");
			return;
		}
		for (SimpleJson sub : health.get("data").asList()) {
			if (!"wlan".equals(sub.get("subsystem").asString(""))) continue;
			// Build 15 (issue #14): each only when present.
			metricIfPresent(out, "Clients|num_user", sub.get("num_user"));
			metricIfPresent(out, "Clients|num_guest", sub.get("num_guest"));
			metricIfPresent(out, "Clients|num_iot", sub.get("num_iot"));
			metricIfPresent(out, "Clients|num_disconnected", sub.get("num_disconnected"));
			metricIfPresent(out, "Performance|num_ap", sub.get("num_ap"));
			metricIfPresent(out, "Performance|tx_bytes_r", sub.get("tx_bytes-r"));
			metricIfPresent(out, "Performance|rx_bytes_r", sub.get("rx_bytes-r"));
			break;
		}
	}

	private void collectNvr(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		if (s.protect == null) {
			logWarn("collect: NVR resource present but Protect data absent from snapshot");
			return;
		}
		SimpleJson nvr = s.protect.get("nvr");
		if (nvr.isNull()) {
			logWarn("collect: NVR absent from Protect bootstrap");
			return;
		}

		// Build 15 (issue #14): uptime only when upSince is present (it was
		// published as 0 when absent); storage values only when present,
		// usage only when both are and the total is not 0.
		Double upSince = UniFiValues.number(nvr.get("upSince").asString());
		if (upSince != null && upSince > 0) {
			metric(out, "System|uptime",
					(System.currentTimeMillis() / 1000.0) - (upSince / 1000.0));
		}

		SimpleJson storage = nvr.get("storageInfo");
		Double total = UniFiValues.number(storage.get("totalSize").asString());
		Double used = UniFiValues.number(storage.get("usedSize").asString());
		metricIfPresent(out, "Storage|total_bytes", total);
		metricIfPresent(out, "Storage|used_bytes", used);
		if (total != null && used != null && total > 0) {
			metric(out, "Storage|usage_pct", (used / total) * 100.0);
		}

		prop(out, "Configuration|model", nvr.get("modelKey").asString(""));
		prop(out, "Configuration|firmware", nvr.get("firmwareVersion").asString(""));
		prop(out, "Configuration|name", nvr.get("name").asString("NVR"));
		prop(out, "Configuration|host_type", nvr.get("type").asString(""));
		// Build 15 (issue #16): replaces recording_retention_mode, which
		// carried the raw millisecond duration as text under a "mode" name.
		// Published only when the NVR sends the duration.
		Double retentionDays = UniFiValues.millisToDays(UniFiValues.number(
				nvr.get("recordingRetentionDurationMs").asString()));
		if (retentionDays != null) {
			numericProp(out, "Configuration|recording_retention_days", retentionDays);
		}

		SimpleJson cameras = s.protect.get("cameras");
		// Peer review 6: only cameras discovery registers (with a MAC).
		int cameraCount = 0;
		for (SimpleJson cam : cameras.asList()) {
			if (UniFiIds.mac(cam.get("mac").asString()) != null) cameraCount++;
		}
		prop(out, "Configuration|camera_count", String.valueOf(cameraCount));
	}

	private void collectCamera(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		String camMac = getIdentifier(rc, "camera_mac");
		SimpleJson cam = s.findCamera(camMac);
		if (cam == null) {
			logWarn("collect: camera " + camMac + " absent from snapshot");
			return;
		}

		// Protect uptime / lastMotion are milliseconds — normalize to seconds.
		// Build 15 (issue #14): uptime only when present.
		Double camUptime = UniFiValues.number(cam.get("uptime").asString());
		if (camUptime != null) metric(out, "Status|uptime", camUptime / 1000.0);
		// Build 15 (issue #16): an age in seconds, replacing last_motion
		// (the epoch timestamp divided by 1000 published as "sec"), so "no
		// motion for X" can be alerted on. Absent when the camera never
		// recorded motion.
		metricIfPresent(out, "Status|seconds_since_last_motion",
				UniFiValues.secondsSince(
						UniFiValues.number(cam.get("lastMotion").asString()),
						System.currentTimeMillis()));

		prop(out, "Status|state", cam.get("state").asString(""));
		// Build 17 (review W2 on build 16): only when Protect sends them.
		flagPropIfPresent(out, "Status|is_connected", cam.get("isConnected"));
		flagPropIfPresent(out, "Status|is_recording", cam.get("isRecording"));
		// Build 17 (review W1 on build 16): the camera's recording mode
		// (recordingSettings.mode: always, detections, schedule, never, as
		// Protect sends it), so Camera Not Recording can leave out a camera
		// set never to record. Only when present.
		textPropIfPresent(out, "Configuration|recording_mode",
				cam.get("recordingSettings").get("mode"));

		prop(out, "Hardware|model", cam.get("modelKey").asString(""));
		prop(out, "Hardware|firmware", cam.get("firmwareVersion").asString(""));
		prop(out, "Hardware|type", cam.get("type").asString(""));
		// isWireless is null on wired cameras, not false.
		boolean wireless = !cam.get("isWireless").isNull()
				&& cam.get("isWireless").asBoolean();
		prop(out, "Hardware|is_wireless", wireless ? "true" : "false");
		SimpleJson wired = cam.get("wiredConnectionState");
		prop(out, "Hardware|phy_rate", !wired.isNull()
				? wired.get("phyRate").asString("") : ""); // review N1

		prop(out, "Network|ip", cam.get("host").asString(""));
		prop(out, "Network|mac_address", camMac);
	}

	// -----------------------------------------------------------------------
	// Relationships: internal topology + LLDP→HostSystem cross-link
	// -----------------------------------------------------------------------

	private Relationships buildRelationships(Snapshot s) {
		RelationshipBuilder rb = new RelationshipBuilder(ADAPTER_KIND);

		ResourceKey world = rb.resource("UniFiWorld", "UniFi World",
				"world_id", "unifi_world");

		for (SimpleJson site : s.sites.get("data").asList()) {
			String siteName = site.get("name").asString();
			String siteDesc = site.get("desc").asString(siteName);
			ResourceKey siteKey = rb.resource("UniFiSite", siteDesc,
					"site_name", siteName);
			rb.parent(world, siteKey);

			// Build 14 (issue #19): a site not read this cycle is never a
			// parent here (full-set would drop its device edges); see
			// SiteIsolation.emitsChildren.
			if (!SiteIsolation.emitsChildren(s.failedSites, siteName)) {
				continue;
			}

			SimpleJson devices = s.devicesForSite(siteName);
			Map<String, ResourceKey> deviceByMac = new HashMap<>();

			for (SimpleJson dev : devices.get("data").asList()) {
				String type = dev.get("type").asString("");
				String mac = deviceMac(dev);
				if (mac == null) continue; // build 15, issue #23
				if (!isDiscovered(dev)) continue; // Codex P2: as discovery
				String name = deviceDisplayName(dev);
				ResourceKey devKey;

				if (isGateway(type)) {
					devKey = rb.resource("UniFiGateway", name, "mac", mac);
					if (!dev.get("wan1").isNull()) {
						rb.parent(devKey, rb.resource("UniFiWanInterface", "WAN 1",
								"wan_name", mac + "_wan1"));
					}
					if (!dev.get("wan2").isNull()) {
						rb.parent(devKey, rb.resource("UniFiWanInterface", "WAN 2",
								"wan_name", mac + "_wan2"));
					}
				} else if ("usw".equals(type)) {
					devKey = rb.resource("UniFiSwitch", name, "mac", mac);
					SimpleJson ports = dev.get("port_table");
					if (!ports.isNull()) {
						for (SimpleJson port : ports.asList()) {
							Integer idx = portIdx(port);
							if (idx == null) continue; // build 15, issue #23
							rb.parent(devKey, rb.resource("UniFiSwitchPort",
									portDisplayName(port, idx),
									"port_key", mac + "_" + idx));
						}
					}
				} else if ("uap".equals(type)) {
					devKey = rb.resource("UniFiAccessPoint", name, "mac", mac);
					SimpleJson radios = dev.get("radio_table_stats");
					if (!radios.isNull()) {
						for (SimpleJson radio : radios.asList()) {
							String code = radioCode(radio);
							if (code == null) continue; // peer review 7
							rb.parent(devKey, rb.resource("UniFiRadio",
									radioDisplayName(code), "radio_key", mac + "_" + code));
						}
					}
				} else {
					continue;
				}
				deviceByMac.put(mac, devKey);
			}

			// Wire device topology by uplink_mac; no uplink → direct child of site.
			for (SimpleJson dev : devices.get("data").asList()) {
				String mac = deviceMac(dev);
				if (mac == null) continue; // build 15, issue #23
				ResourceKey devKey = deviceByMac.get(mac);
				if (devKey == null) continue;
				String uplinkMac = dev.get("uplink").get("uplink_mac").asString("");
				ResourceKey parentKey = deviceByMac.get(uplinkMac);
				if (parentKey != null && !uplinkMac.equals(mac)) {
					rb.parent(parentKey, devKey);
				} else {
					rb.parent(siteKey, devKey);
				}
			}

			rb.parent(siteKey, rb.resource("UniFiWirelessAggregate",
					"Wireless Summary", "aggregate_id", siteName + "_wlan_aggregate"));
		}

		// Protect: NVR → child of first site; cameras → child of NVR (v1 heuristic).
		if (s.protect != null) {
			SimpleJson nvr = s.protect.get("nvr");
			if (!nvr.isNull() && s.sites.get("data").size() > 0) {
				SimpleJson firstSite = s.sites.get("data").get(0);
				String siteName = firstSite.get("name").asString();
				String siteDesc = firstSite.get("desc").asString(siteName);
				ResourceKey siteKey = rb.resource("UniFiSite", siteDesc,
						"site_name", siteName);
				// Peer review 7: an NVR with no MAC is not registered, so it
				// has no edges: no site to NVR edge and no NVR to camera
				// edges. Its cameras are still registered and collect, with
				// no parent in the tree.
				String nvrMac = UniFiIds.mac(nvr.get("mac").asString());
				ResourceKey nvrKey = nvrMac == null ? null : rb.resource("UniFiNvr",
						nvr.get("name").asString("NVR"), "nvr_mac", nvrMac);
				// Build 14 (issue #19): not under a site that was not read
				// (full-set would drop that site's device edges); the NVR
				// keeps its existing site edge and its cameras below. See
				// SiteIsolation.nvrParentSite.
				if (nvrKey != null && java.util.Objects.equals(siteName,
						SiteIsolation.nvrParentSite(s.siteNames(), s.failedSites))) {
					rb.parent(siteKey, nvrKey);
				}

				SimpleJson cameras = s.protect.get("cameras");
				if (nvrKey != null && !cameras.isNull()) {
					for (SimpleJson cam : cameras.asList()) {
						String camMac = UniFiIds.mac(cam.get("mac").asString());
						if (camMac == null) continue; // build 15, issue #23
						rb.parent(nvrKey, rb.resource("UniFiCamera",
								cam.get("name").asString(camMac),
								"camera_mac", camMac));
					}
				}
			}
		}

		// vCenter-side vmnic→port stitch (build 9; optional — replaces the
		// dead controller-side LLDP read).
		emitVmnicHostStitch(rb, s);

		logInfo("Relationships built: World>Site>{Gateway>WAN, Switch>Port, "
				+ "AP>Radio, WirelessAggregate, NVR>Camera} tree"
				+ (stitcher != null ? " + vmnic→port stitch" : ""));
		return rb.build();
	}

	/**
	 * Emit the build-9 {@code HostSystem → UniFiSwitchPort} foreign edge: for
	 * every vmnic→port match {@link Snapshot#build} already computed this
	 * cycle ({@link Snapshot#vmnicEdges}, via {@link UniFiStitcher
	 * #listHostsWithVmnicLldp()} joined against the adapter's own inventory —
	 * see {@code computeVmnicStitch}), make the matched UniFiSwitchPort a
	 * foreign child of the matching VMWARE {@code HostSystem}.
	 *
	 * <p>No-op when {@code stitcher == null} (Suite API unavailable). Matching
	 * itself already happened in {@code Snapshot.build()} (crash-safe there
	 * too); this method only walks the precomputed edge list and logs the
	 * one-line per-cycle summary. Any failure here is still caught and logged
	 * WARN — the internal topology already built must always be returned, so
	 * a stitch fault never costs the cycle its relationships.
	 */
	private void emitVmnicHostStitch(RelationshipBuilder rb, Snapshot s) {
		if (this.stitcher == null) return;
		try {
			int edges = 0;
			for (VmnicEdge e : s.vmnicEdges) {
				ResourceKey portRk = rb.resource("UniFiSwitchPort", e.portName,
						"port_key", e.portKey);
				rb.parentForeign(e.hostKey, portRk);
				edges++;
			}
			logInfo("vmnic→port stitch: " + edges + " edges (" + s.vmnicHostCount
					+ " hosts, " + s.vmnicNeighbourCount + " vmnics w/ LLDP, "
					+ s.vmnicAmbiguousCount + " ambiguous, " + s.vmnicUnmatchedCount
					+ " unmatched, " + s.vmnicConflictedCount + " conflicted)");
		} catch (Exception e) {
			logWarn("vmnic→port stitch failed (internal topology unaffected): "
					+ e.getMessage());
		}
	}

	// -----------------------------------------------------------------------
	// Display name helpers
	// -----------------------------------------------------------------------

	private static boolean isGateway(String type) {
		return UniFiIds.isGatewayType(type);
	}

	/** Build 15 (issue #23): the device MAC, or null when it has none. */
	private static String deviceMac(SimpleJson dev) {
		return UniFiIds.mac(dev.get("mac").asString());
	}

	/**
	 * Codex P2: whether discovery registers this device, the one rule
	 * enumeration, relationships, the stitch port index and the site's
	 * Device Count share; see {@link UniFiIds#discoveredDevice}.
	 */
	private static boolean isDiscovered(SimpleJson dev) {
		SimpleJson a = dev.get("adopted");
		return UniFiIds.discoveredDevice(dev.get("type").asString(""),
				dev.get("mac").asString(), a.isNull() ? null : a.asBoolean());
	}

	/** Build 15 (peer review 7): the radio code, or null when it has none. */
	private static String radioCode(SimpleJson radio) {
		return UniFiIds.radioCode(radio.get("radio").asString());
	}

	/** Build 15 (issue #23): the port index, or null when it has none. */
	private static Integer portIdx(SimpleJson port) {
		return UniFiIds.portIdx(port.get("port_idx").asString());
	}

	private static String deviceDisplayName(SimpleJson dev) {
		String name = dev.get("name").asString("");
		if (!name.isEmpty()) return name;
		String model = dev.get("model").asString("");
		String mac = dev.get("mac").asString("");
		if (!model.isEmpty()) {
			return model + " " + mac.substring(Math.max(0, mac.length() - 8));
		}
		return mac;
	}

	private static String portDisplayName(SimpleJson port, int idx) {
		String name = port.get("name").asString("");
		return name.isEmpty() ? "Port " + idx : name;
	}

	private static String radioDisplayName(String radioCode) {
		switch (radioCode) {
			case "ng": return "2.4 GHz";
			case "na": return "5 GHz";
			case "6e": return "6 GHz";
			default: return radioCode;
		}
	}

	private static SimpleJson findPort(SimpleJson dev, int idx) {
		SimpleJson ports = dev.get("port_table");
		if (ports.isNull()) return null;
		for (SimpleJson port : ports.asList()) {
			// Build 15 (issue #23): a port with no port_idx is never port 0.
			Integer portIdx = portIdx(port);
			if (portIdx != null && portIdx == idx) return port;
		}
		return null;
	}

	private static SimpleJson findRadioStats(SimpleJson dev, String radioCode) {
		SimpleJson radios = dev.get("radio_table_stats");
		if (radios.isNull()) return null;
		// Peer review 7: a blank code is no radio's identity.
		if (UniFiIds.radioCode(radioCode) == null) return null;
		for (SimpleJson radio : radios.asList()) {
			if (radioCode.equals(radio.get("radio").asString(""))) return radio;
		}
		return null;
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	// -----------------------------------------------------------------------
	// onDiscard
	// -----------------------------------------------------------------------

	@Override
	public void onDiscard() {
		// Build 15 (issue #24): the login-only client and the stitcher;
		// super.onDiscard() releases the main client (this.httpClient).
		discardConfigured();
		this.configured = null;
		super.onDiscard();
	}

	/**
	 * Build 15 (issue #24): releases the current configuration's login-only
	 * HTTP client and the Suite API stitcher, on reconfigure and on discard.
	 * The main client is the framework's to release (review N2).
	 */
	private void discardConfigured() {
		Configured c = this.configured;
		if (c != null) {
			// Review N2: the login-only client only. The framework releases
			// the main client (onConfigure before configureAdapter, and
			// super.onDiscard()); Test Connection's finally discards both.
			HttpClients.discardQuietly(c.clients.login);
		}
		SuiteApiStitcher st = this.suiteStitcher;
		this.suiteStitcher = null;
		this.stitcher = null;
		if (st != null) {
			try {
				st.discard();
			} catch (RuntimeException ignored) {
				// Best effort; the token TTL is the platform's safety net.
			}
		}
	}

	// -----------------------------------------------------------------------
	// Per-cycle API snapshot
	// -----------------------------------------------------------------------

	/**
	 * One immutable pull of the UniFi Network/Protect API per cycle, shared across
	 * the per-resource collect calls. Built by {@link #build(Configured,
	 * UniFiAdapter)} which asserts the critical {@code /self/sites} contract and
	 * logs an INFO breadcrumb of the resource counts. Protect is optional: a
	 * Protect failure leaves {@code protect == null} (logged INFO) and the NVR /
	 * camera resources are simply absent — never a sentinel.
	 */
	private static final class Snapshot {
		// Build 14 (issue #12): no builtAt here any more. It was a field
		// initializer, stamped before the API calls ran; SnapshotCache now
		// stamps the time when build() returns.

		SimpleJson sites;
		final Map<String, SimpleJson> devicesBySite = new HashMap<>();
		final Map<String, SimpleJson> healthBySite = new HashMap<>();
		// Build 15 (issue #18, peer review 4): stat/sysinfo data[0], read
		// once per build for the controller; null when not read or when
		// the call failed or carried no record.
		SimpleJson sysinfo;
		SimpleJson protect; // nullable — Protect optional

		// Build 14 (issue #19): sites whose stat/device failed this cycle,
		// site name to reason. Their resources fail (ERROR) instead of
		// collecting nothing; the other sites collect normally.
		final Map<String, String> failedSites = new LinkedHashMap<>();

		// Derived lookups across all sites.
		final Map<String, SimpleJson> deviceByMac = new HashMap<>();
		final Map<String, SimpleJson> cameraByMac = new HashMap<>();

		// vmnic→port stitch (build 9) — computed once per snapshot refresh,
		// shared between emitVmnicHostStitch (relationship edges) and
		// collectSwitchPort (LLDP|* property repurpose). Empty/zero when the
		// stitcher is unavailable or the fetch/match failed this cycle —
		// never a fabricated edge or property. Build 11: a portKey with
		// contradictory claims from two different hosts in the same cycle is
		// absent here (no LLDP|* property written) even though it still has
		// an edge in vmnicEdges for each claimant — see computeVmnicStitch.
		final List<VmnicEdge> vmnicEdges = new ArrayList<>();
		final Map<String, String[]> vmnicLldpByPortKey = new HashMap<>();
		int vmnicHostCount;
		int vmnicNeighbourCount;
		int vmnicAmbiguousCount;
		int vmnicUnmatchedCount;
		int vmnicConflictedCount;

		static Snapshot build(Configured c, UniFiAdapter adapter)
				throws Exception {
			UniFiApiClient api = c.api;
			SiteReads.Cooldowns siteCooldowns = c.siteCooldowns;
			Logger inventoryLog = c.inventoryLog;
			Snapshot s = new Snapshot();
			s.sites = api.listSites();

			// Contract-assert the critical entry endpoint. SimpleJson is
			// null-tolerant (asDouble()->0.0), so a "success-shaped but empty"
			// payload would otherwise publish a healthy-looking but data-free
			// instance. A controller always carries at least the default site, so
			// a missing/unreadable site list is unreadable, not "no sites".
			SimpleJson siteData = s.sites.get("data");
			if (siteData.isNull() || !siteData.isList()) {
				throw new IOException("UniFi /self/sites returned a 200 payload with "
						+ "no readable 'data' site list — treating as unreadable (no "
						+ "sentinel metrics published)");
			}

			// Build 14 (issue #19): one site's failed stat/device marks that
			// site failed (one WARN) and the other sites are read; the build
			// fails only when every site fails (or /self/sites, above).
			// Codex P1: a site refused with 401, 403 or 429 is not asked again
			// for 15 minutes (60 s after any other failure); it stays failed.
			List<String> siteNames = new ArrayList<>();
			for (SimpleJson site : siteData.asList()) {
				siteNames.add(site.get("name").asString());
			}
			SiteReads.Result<SimpleJson> deviceReads = SiteReads.readAll(
					"stat/device", siteNames, api::statDevice, adapter::logWarn,
					siteCooldowns, adapter::logInfo);
			s.failedSites.putAll(deviceReads.failed);

			int devTotal = 0;
			// Build 15 (issue #23): no-identity records, skipped everywhere.
			int skippedDevices = 0, skippedPorts = 0, skippedCameras = 0;
			int skippedRadios = 0, skippedNvrs = 0; // peer review 7
			for (String siteName : siteNames) {
				SimpleJson devices = deviceReads.bySite.get(siteName);
				if (devices == null) {
					continue; // failed this cycle: no devices, no health call
				}
				s.devicesBySite.put(siteName, devices);
				SimpleJson devData = devices.get("data");
				if (!devData.isNull() && devData.isList()) {
					for (SimpleJson dev : devData.asList()) {
						String mac = deviceMac(dev);
						if (mac == null) {
							skippedDevices++;
							debugQuietly(inventoryLog, "UniFi inventory: skipped a "
									+ dev.get("type").asString("?") + " device on site "
									+ siteName + " with no MAC (model="
									+ dev.get("model").asString("?") + ", name="
									+ dev.get("name").asString("") + ")");
							continue;
						}
						s.deviceByMac.put(mac, dev);
						devTotal++;
						if ("uap".equals(dev.get("type").asString(""))) {
							for (SimpleJson radio : dev.get("radio_table_stats").asList()) {
								if (radioCode(radio) == null) {
									skippedRadios++;
									debugQuietly(inventoryLog, "UniFi inventory: "
											+ "skipped a radio with no radio code "
											+ "on access point " + mac);
								}
							}
						}
						if ("usw".equals(dev.get("type").asString(""))) {
							for (SimpleJson port : dev.get("port_table").asList()) {
								if (portIdx(port) == null) {
									skippedPorts++;
									debugQuietly(inventoryLog, "UniFi inventory: "
											+ "skipped a port with no port_idx on "
											+ "switch " + mac + " (name="
											+ port.get("name").asString("") + ")");
								}
							}
						}
					}
				}
				try {
					s.healthBySite.put(siteName, api.statHealth(siteName));
				} catch (Exception e) {
					adapter.logWarn("Snapshot: stat/health failed for site "
							+ siteName + ": " + e.getMessage());
				}
			}

			// Build 15 (issue #18, peer review 4): stat/sysinfo once per
			// build, through the first site read this build (it describes
			// the controller, not the site), for every site's Version and
			// Timezone properties only. A failure costs those properties,
			// nothing else; after a 401, 403 or 429 it is not asked again for
			// 15 minutes (SysinfoGate), so a refusing account (View Only is
			// to be confirmed on devel, review W3) does not cost one extra
			// login per cycle.
			String sysinfoSite = null;
			for (String siteName : siteNames) {
				if (s.devicesBySite.containsKey(siteName)) {
					sysinfoSite = siteName;
					break;
				}
			}
			if (sysinfoSite != null && c.sysinfoGate.shouldAsk(adapter::logInfo)) {
				try {
					SimpleJson info = api.statSysinfo(sysinfoSite).get("data").get(0);
					if (info.isObject()) s.sysinfo = info;
				} catch (InterruptedException e) {
					throw e; // review N4: Stop Collecting reaches the framework
				} catch (Exception e) {
					c.sysinfoGate.failed(e, adapter::logWarn, adapter::logInfo);
				}
			}

			// vmnic→port stitch (build 9; optional, crash-safe). Fetches the
			// VMWARE HostSystem enumeration + per-vmnic LLDP properties once
			// per snapshot refresh and joins against the own-inventory index
			// just built above, so the result is ready for both
			// emitVmnicHostStitch (relationship edges) and collectSwitchPort
			// (LLDP|* property repurpose) without a second Suite API round
			// trip. A fetch/match failure is caught inside
			// computeVmnicStitch — it must never fail this Snapshot build,
			// since the internal UniFi topology this method exists to
			// produce does not depend on the optional cross-adapter join.
			computeVmnicStitch(s, adapter, c.vmnicHosts);

			// Protect (optional sub-tree). Fetched every cycle, as in build
			// 14 (review B1: never cached, so camera and NVR values are never
			// older than one interval). A failed fetch leaves Protect absent
			// for the cycle: the NVR and cameras publish nothing that cycle.
			try {
				SimpleJson protect = api.protectBootstrap();
				if (!protect.get("nvr").isNull()) {
					s.protect = protect;
					if (UniFiIds.mac(protect.get("nvr").get("mac").asString()) == null) {
						skippedNvrs++;
						debugQuietly(inventoryLog, "UniFi inventory: skipped the "
								+ "NVR with no MAC (name=" + protect.get("nvr")
										.get("name").asString("") + "); its cameras "
								+ "are registered with no parent");
					}
					SimpleJson cams = protect.get("cameras");
					if (!cams.isNull()) {
						for (SimpleJson cam : cams.asList()) {
							String mac = UniFiIds.mac(cam.get("mac").asString());
							if (mac == null) {
								skippedCameras++;
								debugQuietly(inventoryLog, "UniFi inventory: "
										+ "skipped a camera with no MAC (name="
										+ cam.get("name").asString("") + ")");
								continue;
							}
							s.cameraByMac.put(mac, cam);
						}
					}
				}
			} catch (InterruptedException e) {
				throw e;
			} catch (Exception e) {
				adapter.logInfo("Snapshot: Protect not available: " + e.getMessage());
			}

			adapter.logInfo("UniFi API snapshot: " + siteData.size() + " sites / "
					+ devTotal + " devices / " + s.cameraByMac.size() + " cameras / "
					+ "protect=" + (s.protect != null)
					+ (s.failedSites.isEmpty() ? ""
							: " / unreadable sites=" + s.failedSites.keySet())
					+ (skippedDevices + skippedPorts + skippedCameras
							+ skippedRadios + skippedNvrs == 0 ? ""
							: " / skipped with no identity: " + skippedDevices
									+ " devices, " + skippedPorts + " ports, "
									+ skippedRadios + " radios, "
									+ skippedCameras + " cameras, "
									+ skippedNvrs + " nvr"));
			return s;
		}

		/** A logger fault never reaches the snapshot build. */
		private static void debugQuietly(Logger log, String message) {
			if (log == null) return;
			try {
				log.debug(message);
			} catch (RuntimeException ignored) {
				// logging is best effort
			}
		}

		/** Site names in /self/sites order. */
		List<String> siteNames() {
			List<String> names = new ArrayList<>();
			for (SimpleJson site : sites.get("data").asList()) {
				names.add(site.get("name").asString());
			}
			return names;
		}

		SimpleJson devicesForSite(String siteName) {
			SimpleJson d = devicesBySite.get(siteName);
			return d != null ? d : SimpleJson.parse("{\"data\":[]}");
		}

		SimpleJson healthForSite(String siteName) {
			return healthBySite.get(siteName);
		}

		/** Build 15 (issue #18): stat/sysinfo data[0], or null. */
		SimpleJson sysinfo() {
			return sysinfo;
		}

		SimpleJson findSite(String siteName) {
			for (SimpleJson site : sites.get("data").asList()) {
				if (siteName.equals(site.get("name").asString())) return site;
			}
			return null;
		}

		/**
		 * The device with this MAC, or {@code null} when it is not in the
		 * snapshot and every site was read (it is gone from the controller).
		 * Build 14 (issue #19): throws when a site could not be read this
		 * cycle and the device is not found; see
		 * {@link SiteIsolation#findDevice}.
		 */
		SimpleJson findDevice(String mac) throws IOException {
			return SiteIsolation.findDevice(deviceByMac, failedSites, mac);
		}

		/**
		 * Build 14 (issue #19): throws when this site was not read this
		 * cycle; see {@link SiteIsolation#requireSiteRead}.
		 */
		void requireSiteRead(String siteName) throws IOException {
			SiteIsolation.requireSiteRead(failedSites, siteName);
		}

		SimpleJson findCamera(String mac) {
			return cameraByMac.get(mac);
		}
	}

	// -----------------------------------------------------------------------
	// vmnic→port stitch (build 9) — join computation
	// -----------------------------------------------------------------------

	/**
	 * One matched vmnic→port edge: {@code hostKey} (the VMWARE {@code
	 * HostSystem}, honest uniqueness flags from the Suite API) becomes the
	 * foreign parent of the local UniFiSwitchPort identified by {@code
	 * portKey} / {@code portName}.
	 */
	private static final class VmnicEdge {
		final ResourceKey hostKey;
		final String portKey;
		final String portName;

		VmnicEdge(ResourceKey hostKey, String portKey, String portName) {
			this.hostKey = hostKey;
			this.portKey = portKey;
			this.portName = portName;
		}
	}

	/** One of the adapter's own UniFiSwitchPort candidates for a
	 * (switch, port) join key — see {@link #buildOwnPortIndex}. */
	private static final class OwnPort {
		final String portKey;
		final String portName;

		OwnPort(String portKey, String portName) {
			this.portKey = portKey;
			this.portName = portName;
		}
	}

	/**
	 * Fetch the VMWARE HostSystem vmnic LLDP enumeration and join it against
	 * the adapter's own UniFiSwitch / UniFiSwitchPort inventory (design §3),
	 * populating {@code s.vmnicEdges}, {@code s.vmnicLldpByPortKey}, and the
	 * summary counters. No-op when {@code adapter.stitcher == null} (Suite API
	 * unavailable). Any exception — Call A/B failure or anything else — is
	 * caught here and logged WARN: this join is optional and must never fail
	 * the Snapshot build it is part of.
	 *
	 * <p><b>Build 11 — conflicted-port property honesty.</b> Edge emission
	 * (the {@code host key, portKey} pair pushed onto {@code s.vmnicEdges})
	 * is unconditional per match, exactly as build 9/10 left it — every
	 * matched neighbour still yields a {@code parentForeign} candidate, so
	 * the exactly-one/zero/ambiguous relationship discipline is byte-
	 * identical to before this build. The {@code LLDP|*} *property* path is
	 * different: {@code s.vmnicLldpByPortKey} tracks, per {@code portKey},
	 * which host resource name is the current claimant this cycle. A second
	 * claim on an already-claimed portKey from a <em>different</em> host is
	 * contradictory LLDP data (two hosts both reporting the same switch
	 * port as a neighbour) — build 9/10 silently let the later claim win
	 * (last-write-wins), which is a cosmetically-plausible but potentially
	 * wrong property. Build 11 instead marks the portKey <em>conflicted</em>
	 * for the rest of this cycle: the earlier claim's property is removed,
	 * no property is written for either claimant, both claimant host names
	 * are logged at debug, and the portKey is counted in
	 * {@code s.vmnicConflictedCount}. Once a portKey is marked conflicted
	 * this cycle it stays conflicted — a third claim (from either original
	 * claimant or a new host) cannot resurrect the property. A second claim
	 * from the <em>same</em> host (e.g. two port aliases folding to the same
	 * joint key, or identical values) is not a conflict and remains
	 * idempotent: the property write stays in place.
	 */
	private static void computeVmnicStitch(Snapshot s, UniFiAdapter adapter,
			RefreshCache<List<UniFiStitcher.ForeignHost>> hostCache)
			throws InterruptedException {
		UniFiStitcher st = adapter.stitcher;
		if (st == null) return;
		try {
			Map<String, List<OwnPort>> ownIndex = buildOwnPortIndex(s);
			// Build 15 (issue #21): the Suite API host list is fetched at most
			// every SLOW_REFRESH_MS (bulk properties reads inside) and reused
			// between fetches; the join against this cycle's own inventory
			// below still runs every snapshot.
			List<UniFiStitcher.ForeignHost> hosts = hostCache.get(
					st::listHostsWithVmnicLldp,
					(e, age) -> adapter.logWarn("vmnic→port stitch: refresh failed ("
							+ e.getMessage() + "); reusing the host list fetched "
							+ (age / 60_000L) + " min ago"));
			s.vmnicHostCount = hosts.size();

			// Per-cycle claimant tracking for the LLDP|* property (build 11).
			// Local to this call — a fresh Snapshot/cycle starts with no
			// claims and no conflicts. Edge emission (s.vmnicEdges) does not
			// consult these; it stays unconditional per match.
			Map<String, String> claimantHostByPortKey = new HashMap<>();
			Set<String> conflictedPortKeysThisCycle = new LinkedHashSet<>();

			for (UniFiStitcher.ForeignHost host : hosts) {
				for (UniFiStitcher.VmnicNeighbour n : host.vmnics) {
					s.vmnicNeighbourCount++;
					String joinKey = normSwitch(n.systemName) + " " + normPort(n.portName);
					List<OwnPort> candidates = ownIndex.get(joinKey);

					if (candidates == null || candidates.isEmpty()) {
						s.vmnicUnmatchedCount++;
						st.debug("vmnic stitch: no port match for "
								+ host.key.getResourceName() + " " + n.vmnic
								+ " -> switch=" + n.systemName
								+ " port=" + n.portName);
						continue;
					}
					if (candidates.size() > 1) {
						// Genuine (switch, port) collision — the joint key
						// disambiguates a duplicate switch dev.name in every
						// other case, so this is the honest, rare tie the
						// API gives us nothing to break. Skip, never emit to
						// every candidate (that would fabricate a wrong
						// edge).
						s.vmnicAmbiguousCount++;
						st.debug("vmnic stitch: ambiguous (" + candidates.size()
								+ " candidates) for " + host.key.getResourceName()
								+ " " + n.vmnic + " -> switch=" + n.systemName
								+ " port=" + n.portName);
						continue;
					}

					OwnPort match = candidates.get(0);
					// Edge emission is unconditional — untouched by the
					// build-11 property-conflict logic below.
					s.vmnicEdges.add(new VmnicEdge(host.key, match.portKey, match.portName));

					String hostName = host.key.getResourceName();
					if (conflictedPortKeysThisCycle.contains(match.portKey)) {
						// Already conflicted earlier this cycle — a further
						// claim (from either original claimant or a third
						// host) must not resurrect the suppressed property.
						st.debug("vmnic stitch: further claim for already-"
								+ "conflicted port " + match.portKey + " from "
								+ hostName + " (LLDP|* stays suppressed)");
						continue;
					}

					String existingHost = claimantHostByPortKey.get(match.portKey);
					if (existingHost == null) {
						// First claim on this portKey this cycle.
						claimantHostByPortKey.put(match.portKey, hostName);
						// LLDP|* repurpose (design §7.3): the matched ESXi
						// host's own resource name + the vmnic label it was
						// seen on — strictly better data than the
						// controller's never-populated lldp_table ever had
						// for these fields.
						s.vmnicLldpByPortKey.put(match.portKey,
								new String[]{hostName, n.vmnic});
					} else if (existingHost.equals(hostName)) {
						// Same-host re-claim (e.g. two port aliases folding
						// to the same joint key, or identical values) — not
						// a conflict, stays idempotent.
						s.vmnicLldpByPortKey.put(match.portKey,
								new String[]{hostName, n.vmnic});
					} else {
						// A DIFFERENT host now claims a portKey already
						// claimed this cycle — contradictory LLDP data.
						// Neither claim is trustworthy: suppress the
						// property entirely (remove the earlier claim too),
						// mark the portKey conflicted so a later third claim
						// can't resurrect it, and count/log it. Edge
						// emission above is unaffected.
						conflictedPortKeysThisCycle.add(match.portKey);
						s.vmnicLldpByPortKey.remove(match.portKey);
						s.vmnicConflictedCount++;
						st.debug("vmnic stitch: conflicting LLDP claims for port "
								+ match.portKey + " -- " + existingHost + " and "
								+ hostName + "; suppressing LLDP|* property this "
								+ "cycle (edges still emitted for both hosts)");
					}
				}
			}
		} catch (InterruptedException e) {
			throw e;
		} catch (Exception e) {
			adapter.logWarn("vmnic→port stitch: fetch/match failed (internal "
					+ "topology unaffected): " + e.getMessage());
		}
	}

	/**
	 * Build the own-inventory join index: {@code normSwitch(dev.name) + " " +
	 * normPort(alias)} → every local UniFiSwitchPort candidate sharing that
	 * joint key (design §3, build 10 amendment). Each port is registered
	 * under <b>two</b> aliases against the same joint key: {@code
	 * normPort(portDisplayName)} (the operator-visible custom label) and
	 * {@code normPort("Port " + port_idx)} (the hardware label). Build 9
	 * indexed the display name only and missed a renamed port (LLDP
	 * advertises the switch's hardware label, not a custom display name —
	 * see design §"Build 10 amendment"); the hardware-label alias covers
	 * today's firmware, the display-name alias future-proofs a firmware
	 * change that starts advertising the custom label instead. When the two
	 * aliases coincide (the common unrenamed case) they collapse to the same
	 * joint key; a per-key portKey presence check keeps that from
	 * double-registering the same candidate (which would otherwise look like
	 * a spurious ambiguous collision). Almost always exactly one candidate
	 * per joint key; more than one is a genuine (switch, port) collision,
	 * surfaced as "ambiguous" by the caller rather than resolved here.
	 */
	private static Map<String, List<OwnPort>> buildOwnPortIndex(Snapshot s) {
		Map<String, List<OwnPort>> idx = new HashMap<>();
		for (SimpleJson site : s.sites.get("data").asList()) {
			String siteName = site.get("name").asString();
			for (SimpleJson dev : s.devicesForSite(siteName).get("data").asList()) {
				if (!"usw".equals(dev.get("type").asString(""))) continue;
				String switchMac = deviceMac(dev);
				if (switchMac == null) continue; // build 15, issue #23
				if (!isDiscovered(dev)) continue; // Codex P2: no port resources
				String devName = dev.get("name").asString("");
				SimpleJson ports = dev.get("port_table");
				if (ports.isNull()) continue;
				for (SimpleJson port : ports.asList()) {
					Integer idx2 = portIdx(port);
					if (idx2 == null) continue; // build 15, issue #23
					String portKey = switchMac + "_" + idx2;
					String portName = portDisplayName(port, idx2);
					OwnPort candidate = new OwnPort(portKey, portName);
					// Dedup via a Set: same joint key from both aliases when
					// display name == hardware label must not double-count
					// into a spurious ambiguity.
					Set<String> aliases = new LinkedHashSet<>();
					aliases.add(portName);
					aliases.add("Port " + idx2);
					for (String alias : aliases) {
						String joinKey = normSwitch(devName) + " " + normPort(alias);
						List<OwnPort> list = idx.computeIfAbsent(joinKey, k -> new ArrayList<>());
						boolean alreadyPresent = false;
						for (OwnPort existing : list) {
							if (existing.portKey.equals(portKey)) {
								alreadyPresent = true;
								break;
							}
						}
						if (!alreadyPresent) list.add(candidate);
					}
				}
			}
		}
		return idx;
	}

	/**
	 * Normalize a UniFi switch-port display name for the vmnic join (design
	 * §3): lowercase, {@code _} → space (the XG SFP-port quirk: vCenter
	 * renders {@code "SFP_ 1"} where UniFi displays {@code "SFP 1"}), collapse
	 * runs of whitespace to one space, trim. Deliberately narrow — must not
	 * merge {@code "Port 1"} and {@code "Port 11"}.
	 */
	private static String normPort(String s) {
		if (s == null) return "";
		String t = s.toLowerCase().replace('_', ' ');
		return t.replaceAll("\\s+", " ").trim();
	}

	/**
	 * Normalize a UniFi switch device name for the vmnic join (design §3):
	 * lowercase, collapse whitespace, trim. No underscore fold — switch names
	 * don't carry the SFP-port quirk {@link #normPort} works around.
	 */
	private static String normSwitch(String s) {
		if (s == null) return "";
		return s.toLowerCase().replaceAll("\\s+", " ").trim();
	}
}
