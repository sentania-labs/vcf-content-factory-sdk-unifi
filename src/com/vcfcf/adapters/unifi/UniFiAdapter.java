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

	private volatile UniFiApiClient api;

	/**
	 * Ambient Suite API transport for the optional vmnic→port stitch, and the
	 * UniFi-specific resolver over it. Both null when the Suite API is
	 * unavailable (remote collector with no {@code maintenanceuser.properties}) —
	 * the stitch is then skipped for the cycle and collection proceeds.
	 */
	private volatile SuiteApiStitcher suiteStitcher;
	private volatile UniFiStitcher stitcher;

	/**
	 * Per-cycle snapshot of the UniFi API responses, shared across all per-resource
	 * {@code collect()} calls within one collection cycle. {@code volatile} for
	 * cross-thread visibility; {@link #currentSnapshot()} is {@code synchronized} so
	 * the check-then-refresh is atomic and only one thread performs the API pull.
	 */
	private volatile Snapshot snapshot;
	private static final long MIN_REFRESH_INTERVAL_MS = 60_000L;

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
		this.httpClient = buildHttpClient(cfg);
		this.api = new UniFiApiClient(this.httpClient,
				componentLogger(UniFiApiClient.class));
		this.snapshot = null;

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
	}

	private UniFiConfig buildConfig(ResourceConfig rc) {
		String host = getIdentifier(rc, "host");
		String port = getIdentifier(rc, "port");
		String allowInsecure = getIdentifier(rc, "allowInsecure");
		String username = getCredentialField(rc, "username");
		String password = getCredentialField(rc, "password");
		return new UniFiConfig(host, port, username, password, allowInsecure);
	}

	private ManagedHttpClient buildHttpClient(UniFiConfig cfg) {
		// Raw client for the login round-trip (no auth strategy attached yet).
		ManagedHttpClient rawHttp = baseBuilder(cfg).build();

		// Session cookie auth: TOKEN cookie minted by POST /api/auth/login. The
		// login closure captures only the raw client and the credentials; the
		// password never leaves this client.
		SessionCookieAuth auth = new SessionCookieAuth("TOKEN",
				() -> UniFiApiClient.login(rawHttp,
						componentLogger(UniFiApiClient.class),
						cfg.username, cfg.password));

		return baseBuilder(cfg).auth(auth).build();
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
	// getTester — self-contained (controller calls it on a BARE instance:
	// configureAdapter has NOT run, so this.api / this.config are null). Derive
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
			ManagedHttpClient testHttp = buildHttpClient(testCfg);
			try {
				UniFiApiClient testApi = new UniFiApiClient(testHttp,
						componentLogger(UniFiApiClient.class));
				SimpleJson sites = testApi.listSites();
				SimpleJson data = sites.get("data");
				if (data.isNull() || !data.isList()) {
					throw new IOException("UniFi /self/sites returned a 200 payload "
							+ "with no readable 'data' site list");
				}
				logInfo("Test OK: connected to " + testCfg.host + ", "
						+ data.size() + " site(s)");
			} finally {
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
				String mac = dev.get("mac").asString();
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
							int idx = (int) port.get("port_idx").asLong();
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
							String radioCode = radio.get("radio").asString("");
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
				sink.accept(rcOf("UniFiNvr", nvr.get("name").asString("NVR"),
						"nvr_mac", nvr.get("mac").asString("")));
				nvrs++;
				SimpleJson cams = s.protect.get("cameras");
				if (!cams.isNull()) {
					for (SimpleJson cam : cams.asList()) {
						String camMac = cam.get("mac").asString();
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
			// enumerateResources(ResourceSink) (§22) — no needsRediscovery()/
			// rediscover() overrides here. The framework calls
			// enumerateResources(this::registerNewResource) at the top of every
			// cycle before this collect loop runs.

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

			@Override
			public ResourceStatusEnum mapCollectException(Exception e) {
				if (e instanceof java.net.ConnectException) {
					return ResourceStatusEnum.RESOURCE_STATUS_DOWN;
				}
				return ResourceStatusEnum.RESOURCE_STATUS_ERROR;
			}
		};
	}

	/**
	 * Return the snapshot for this cycle, refreshing it if it is null or older than
	 * {@link #MIN_REFRESH_INTERVAL_MS}. A refresh failure (session/REST error, or an
	 * unreadable {@code /self/sites} payload) propagates out so the framework marks
	 * the resource ERROR/DOWN — never a silent empty snapshot.
	 */
	private synchronized Snapshot currentSnapshot() throws Exception {
		Snapshot s = this.snapshot;
		long now = System.currentTimeMillis();
		if (s == null || (now - s.builtAt) >= MIN_REFRESH_INTERVAL_MS) {
			s = Snapshot.build(api, this);
			this.snapshot = s;
		}
		return s;
	}

	private void dispatchCollect(ResourceConfig rc, Snapshot s, List<MetricData> out) {
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

	private static void prop(List<MetricData> out, String key, String value) {
		out.add(new MetricData(new MetricKey(true, key),
				System.currentTimeMillis(), value != null ? value : ""));
	}

	// -----------------------------------------------------------------------
	// Per-kind collectors (value semantics preserved from v1)
	// -----------------------------------------------------------------------

	private void collectSite(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		String siteName = getIdentifier(rc, "site_name");
		SimpleJson site = s.findSite(siteName);
		if (site == null) {
			logWarn("collect: site " + siteName + " absent from snapshot");
			return;
		}
		prop(out, "Configuration|description", site.get("desc").asString(""));
		prop(out, "Configuration|device_count",
				String.valueOf(site.get("device_count").asLong()));
	}

	private void collectGateway(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		String mac = getIdentifier(rc, "mac");
		SimpleJson dev = s.findDevice(mac);
		if (dev == null) {
			logWarn("collect: gateway " + mac + " absent from snapshot");
			return;
		}

		// UDM system-stats are STRINGS — coerce.
		SimpleJson ss = dev.get("system-stats");
		if (!ss.isNull()) {
			metric(out, "System|cpu_pct", parseDouble(ss.get("cpu").asString("0")));
			metric(out, "System|mem_pct", parseDouble(ss.get("mem").asString("0")));
		}
		metric(out, "System|uptime", dev.get("uptime").asDouble());
		metric(out, "System|num_sta", dev.get("num_sta").asDouble());

		SimpleJson temps = dev.get("temperatures");
		if (!temps.isNull()) {
			for (SimpleJson t : temps.asList()) {
				String tName = t.get("name").asString("");
				double tValue = t.get("value").asDouble();
				if ("CPU".equals(tName)) metric(out, "Temperature|temp_cpu", tValue);
				else if ("Local".equals(tName)) metric(out, "Temperature|temp_local", tValue);
				else if ("PHY".equals(tName)) metric(out, "Temperature|temp_phy", tValue);
			}
		}

		SimpleJson st = dev.get("speedtest-status");
		if (!st.isNull()) {
			metric(out, "Speedtest|xput_up", st.get("xput_upload").asDouble());
			metric(out, "Speedtest|xput_down", st.get("xput_download").asDouble());
			metric(out, "Speedtest|speedtest_latency", st.get("latency").asDouble());
		}

		prop(out, "Configuration|model", dev.get("model").asString(""));
		prop(out, "Configuration|firmware", dev.get("version").asString(""));
		prop(out, "Configuration|serial", dev.get("serial").asString(""));
		prop(out, "Configuration|ip", dev.get("ip").asString(""));
		prop(out, "Configuration|mac_address", mac);
		prop(out, "Configuration|name", dev.get("name").asString(""));
	}

	private void collectWanInterface(ResourceConfig rc, Snapshot s, List<MetricData> out) {
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

		metric(out, "Traffic|tx_bytes", wan.get("tx_bytes").asDouble());
		metric(out, "Traffic|rx_bytes", wan.get("rx_bytes").asDouble());
		metric(out, "Health|latency", wan.get("latency").asDouble());
		metric(out, "Health|availability", wan.get("availability").asDouble());
		metric(out, "Health|speed", wan.get("speed").asDouble());

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

	private void collectSwitch(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		String mac = getIdentifier(rc, "mac");
		SimpleJson dev = s.findDevice(mac);
		if (dev == null) {
			logWarn("collect: switch " + mac + " absent from snapshot");
			return;
		}

		SimpleJson ss = dev.get("system-stats");
		if (!ss.isNull()) {
			metric(out, "System|cpu_pct", parseDouble(ss.get("cpu").asString("0")));
			metric(out, "System|mem_pct", parseDouble(ss.get("mem").asString("0")));
		}
		metric(out, "System|uptime", dev.get("uptime").asDouble());
		metric(out, "System|num_sta", dev.get("num_sta").asDouble());
		metric(out, "System|satisfaction", dev.get("satisfaction").asDouble());

		double maxPower = dev.get("total_max_power").asDouble();
		boolean poeCapable = maxPower > 0;
		metric(out, "PoE|total_max_power", maxPower);

		prop(out, "Configuration|model", dev.get("model").asString(""));
		prop(out, "Configuration|firmware", dev.get("version").asString(""));
		prop(out, "Configuration|serial", dev.get("serial").asString(""));
		prop(out, "Configuration|ip", dev.get("ip").asString(""));
		prop(out, "Configuration|mac_address", mac);
		prop(out, "Configuration|name", dev.get("name").asString(""));
		prop(out, "Configuration|poe_capable", poeCapable ? "true" : "false");
		prop(out, "Configuration|has_fan",
				dev.get("has_fan").asBoolean() ? "true" : "false");

		// PoE budget — sum each port's draw (same fields collectSwitchPort reads).
		SimpleJson ports = dev.get("port_table");
		int portCount = 0;
		double poeConsumption = 0;
		if (!ports.isNull()) {
			portCount = ports.size();
			for (SimpleJson port : ports.asList()) {
				if (port.get("port_poe").asBoolean()) {
					poeConsumption += parseDouble(port.get("poe_power").asString("0"));
				}
			}
		}
		prop(out, "Configuration|port_count", String.valueOf(portCount));
		metric(out, "PoE|poe_consumption", poeConsumption);
		metric(out, "PoE|poe_budget_remaining", maxPower - poeConsumption);
	}

	private void collectSwitchPort(ResourceConfig rc, Snapshot s, List<MetricData> out) {
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

		metric(out, "Traffic|tx_bytes", port.get("tx_bytes").asDouble());
		metric(out, "Traffic|rx_bytes", port.get("rx_bytes").asDouble());
		metric(out, "Traffic|tx_errors", port.get("tx_errors").asDouble());
		metric(out, "Traffic|rx_errors", port.get("rx_errors").asDouble());

		metric(out, "Status|satisfaction", port.get("satisfaction").asDouble());
		metric(out, "Status|mac_table_count", (double) port.get("mac_table").size());
		prop(out, "Status|up", port.get("up").asBoolean() ? "true" : "false");
		prop(out, "Status|speed", String.valueOf(port.get("speed").asLong()));
		prop(out, "Status|duplex",
				port.get("full_duplex").asBoolean() ? "Full" : "Half");
		prop(out, "Status|is_uplink",
				port.get("is_uplink").asBoolean() ? "true" : "false");
		prop(out, "Status|media", port.get("media").asString(""));
		prop(out, "Status|stp_state", port.get("stp_state").asString(""));
		prop(out, "Status|port_name", port.get("name").asString(""));

		if (port.get("port_poe").asBoolean()) {
			// poe_power / voltage / current are STRINGS — coerce.
			metric(out, "PoE|poe_power", parseDouble(port.get("poe_power").asString("0")));
			metric(out, "PoE|poe_voltage", parseDouble(port.get("poe_voltage").asString("0")));
			metric(out, "PoE|poe_current", parseDouble(port.get("poe_current").asString("0")));
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
		// vmnic for this port) → no LLDP properties this cycle, not a
		// sentinel.
		String[] matched = s.vmnicLldpByPortKey.get(portKey);
		if (matched != null) {
			prop(out, "LLDP|lldp_system_name", matched[0]);
			prop(out, "LLDP|lldp_port_id", matched[1]);
		}
	}

	private void collectAccessPoint(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		String mac = getIdentifier(rc, "mac");
		SimpleJson dev = s.findDevice(mac);
		if (dev == null) {
			logWarn("collect: access-point " + mac + " absent from snapshot");
			return;
		}

		SimpleJson ss = dev.get("system-stats");
		if (!ss.isNull()) {
			metric(out, "System|cpu_pct", parseDouble(ss.get("cpu").asString("0")));
			metric(out, "System|mem_pct", parseDouble(ss.get("mem").asString("0")));
		}
		metric(out, "System|uptime", dev.get("uptime").asDouble());
		metric(out, "System|num_sta", dev.get("num_sta").asDouble());
		metric(out, "System|satisfaction", dev.get("satisfaction").asDouble());

		prop(out, "Configuration|model", dev.get("model").asString(""));
		prop(out, "Configuration|firmware", dev.get("version").asString(""));
		prop(out, "Configuration|serial", dev.get("serial").asString(""));
		prop(out, "Configuration|ip", dev.get("ip").asString(""));
		prop(out, "Configuration|mac_address", mac);
		prop(out, "Configuration|name", dev.get("name").asString(""));
	}

	private void collectRadio(ResourceConfig rc, Snapshot s, List<MetricData> out) {
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

		metric(out, "RF|channel", radio.get("channel").asDouble());
		metric(out, "RF|tx_power", radio.get("tx_power").asDouble());
		metric(out, "RF|cu_total", radio.get("cu_total").asDouble());
		metric(out, "RF|satisfaction", radio.get("satisfaction").asDouble());
		metric(out, "RF|tx_retries_pct", radio.get("tx_retries_pct").asDouble());

		metric(out, "Clients|user_num_sta", radio.get("user-num_sta").asDouble());

		String radioName = radio.get("name").asString("");
		if (!radioName.isEmpty()) {
			SimpleJson statAp = dev.get("stat").get("ap");
			if (!statAp.isNull()) {
				metric(out, "Traffic|tx_bytes",
						statAp.get(radioName + "-tx_bytes").asDouble());
				metric(out, "Traffic|rx_bytes",
						statAp.get(radioName + "-rx_bytes").asDouble());
			}
		}

		prop(out, "Configuration|radio_type", radioCode);
		prop(out, "Configuration|radio_name", radioName);
		SimpleJson radioTable = dev.get("radio_table");
		if (!radioTable.isNull()) {
			for (SimpleJson rt : radioTable.asList()) {
				if (radioCode.equals(rt.get("radio").asString(""))) {
					prop(out, "Configuration|ht", rt.get("ht").asString(""));
					prop(out, "Configuration|min_txpower",
							String.valueOf(rt.get("min_txpower").asLong()));
					prop(out, "Configuration|max_txpower",
							String.valueOf(rt.get("max_txpower").asLong()));
					break;
				}
			}
		}
	}

	private void collectWirelessAggregate(ResourceConfig rc, Snapshot s,
			List<MetricData> out) {
		String aggId = getIdentifier(rc, "aggregate_id"); // "<siteName>_wlan_aggregate"
		String siteName = aggId.endsWith("_wlan_aggregate")
				? aggId.substring(0, aggId.length() - "_wlan_aggregate".length())
				: aggId;
		SimpleJson health = s.healthForSite(siteName);
		if (health == null) {
			logWarn("collect: wireless aggregate health for site " + siteName
					+ " absent from snapshot");
			return;
		}
		for (SimpleJson sub : health.get("data").asList()) {
			if (!"wlan".equals(sub.get("subsystem").asString(""))) continue;
			metric(out, "Clients|num_user", sub.get("num_user").asDouble());
			metric(out, "Clients|num_guest", sub.get("num_guest").asDouble());
			metric(out, "Clients|num_iot", sub.get("num_iot").asDouble());
			metric(out, "Clients|num_disconnected", sub.get("num_disconnected").asDouble());
			metric(out, "Performance|num_ap", sub.get("num_ap").asDouble());
			metric(out, "Performance|tx_bytes_r", sub.get("tx_bytes-r").asDouble());
			metric(out, "Performance|rx_bytes_r", sub.get("rx_bytes-r").asDouble());
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

		double upSince = nvr.get("upSince").asDouble();
		metric(out, "System|uptime", upSince > 0
				? (System.currentTimeMillis() / 1000.0) - (upSince / 1000.0)
				: 0);

		SimpleJson storage = nvr.get("storageInfo");
		if (!storage.isNull()) {
			double total = storage.get("totalSize").asDouble();
			double used = storage.get("usedSize").asDouble();
			metric(out, "Storage|total_bytes", total);
			metric(out, "Storage|used_bytes", used);
			metric(out, "Storage|usage_pct", total > 0 ? (used / total) * 100.0 : 0);
		}

		prop(out, "Configuration|model", nvr.get("modelKey").asString(""));
		prop(out, "Configuration|firmware", nvr.get("firmwareVersion").asString(""));
		prop(out, "Configuration|name", nvr.get("name").asString("NVR"));
		prop(out, "Configuration|host_type", nvr.get("type").asString(""));
		prop(out, "Configuration|recording_retention_mode",
				nvr.get("recordingRetentionDurationMs").asString(""));

		SimpleJson cameras = s.protect.get("cameras");
		prop(out, "Configuration|camera_count",
				String.valueOf(cameras.isNull() ? 0 : cameras.size()));
	}

	private void collectCamera(ResourceConfig rc, Snapshot s, List<MetricData> out) {
		String camMac = getIdentifier(rc, "camera_mac");
		SimpleJson cam = s.findCamera(camMac);
		if (cam == null) {
			logWarn("collect: camera " + camMac + " absent from snapshot");
			return;
		}

		// Protect uptime / lastMotion are milliseconds — normalize to seconds.
		metric(out, "Status|uptime", cam.get("uptime").asLong() / 1000.0);
		long lastMotion = cam.get("lastMotion").asLong();
		if (lastMotion > 0) {
			metric(out, "Status|last_motion", lastMotion / 1000.0);
		}

		prop(out, "Status|state", cam.get("state").asString(""));
		prop(out, "Status|is_connected",
				cam.get("isConnected").asBoolean() ? "true" : "false");
		prop(out, "Status|is_recording",
				cam.get("isRecording").asBoolean() ? "true" : "false");

		prop(out, "Hardware|model", cam.get("modelKey").asString(""));
		prop(out, "Hardware|firmware", cam.get("firmwareVersion").asString(""));
		prop(out, "Hardware|type", cam.get("type").asString(""));
		// isWireless is null on wired cameras, not false.
		boolean wireless = !cam.get("isWireless").isNull()
				&& cam.get("isWireless").asBoolean();
		prop(out, "Hardware|is_wireless", wireless ? "true" : "false");
		SimpleJson wired = cam.get("wiredConnectionState");
		prop(out, "Hardware|phy_rate", !wired.isNull()
				? String.valueOf(wired.get("phyRate").asLong()) : "");

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

			SimpleJson devices = s.devicesForSite(siteName);
			Map<String, ResourceKey> deviceByMac = new HashMap<>();

			for (SimpleJson dev : devices.get("data").asList()) {
				String type = dev.get("type").asString("");
				String mac = dev.get("mac").asString();
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
							int idx = (int) port.get("port_idx").asLong();
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
							String code = radio.get("radio").asString("");
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
				String mac = dev.get("mac").asString();
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
				ResourceKey nvrKey = rb.resource("UniFiNvr",
						nvr.get("name").asString("NVR"),
						"nvr_mac", nvr.get("mac").asString(""));
				rb.parent(siteKey, nvrKey);

				SimpleJson cameras = s.protect.get("cameras");
				if (!cameras.isNull()) {
					for (SimpleJson cam : cameras.asList()) {
						String camMac = cam.get("mac").asString();
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
					+ " unmatched)");
		} catch (Exception e) {
			logWarn("vmnic→port stitch failed (internal topology unaffected): "
					+ e.getMessage());
		}
	}

	// -----------------------------------------------------------------------
	// Display name helpers
	// -----------------------------------------------------------------------

	private static boolean isGateway(String type) {
		return "udm".equals(type) || "ugw".equals(type) || "uxg".equals(type);
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
			if ((int) port.get("port_idx").asLong() == idx) return port;
		}
		return null;
	}

	private static SimpleJson findRadioStats(SimpleJson dev, String radioCode) {
		SimpleJson radios = dev.get("radio_table_stats");
		if (radios.isNull()) return null;
		for (SimpleJson radio : radios.asList()) {
			if (radioCode.equals(radio.get("radio").asString(""))) return radio;
		}
		return null;
	}

	private static double parseDouble(String s) {
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return 0;
		}
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
		SuiteApiStitcher st = this.suiteStitcher;
		if (st != null) st.discard();
		this.suiteStitcher = null;
		this.stitcher = null;
		this.snapshot = null;
		super.onDiscard();
	}

	// -----------------------------------------------------------------------
	// Per-cycle API snapshot
	// -----------------------------------------------------------------------

	/**
	 * One immutable pull of the UniFi Network/Protect API per cycle, shared across
	 * the per-resource collect calls. Built by {@link #build(UniFiApiClient,
	 * UniFiAdapter)} which asserts the critical {@code /self/sites} contract and
	 * logs an INFO breadcrumb of the resource counts. Protect is optional: a
	 * Protect failure leaves {@code protect == null} (logged INFO) and the NVR /
	 * camera resources are simply absent — never a sentinel.
	 */
	private static final class Snapshot {
		final long builtAt = System.currentTimeMillis();

		SimpleJson sites;
		final Map<String, SimpleJson> devicesBySite = new HashMap<>();
		final Map<String, SimpleJson> healthBySite = new HashMap<>();
		SimpleJson protect; // nullable — Protect optional

		// Derived lookups across all sites.
		final Map<String, SimpleJson> deviceByMac = new HashMap<>();
		final Map<String, SimpleJson> cameraByMac = new HashMap<>();

		// vmnic→port stitch (build 9) — computed once per snapshot refresh,
		// shared between emitVmnicHostStitch (relationship edges) and
		// collectSwitchPort (LLDP|* property repurpose). Empty/zero when the
		// stitcher is unavailable or the fetch/match failed this cycle —
		// never a fabricated edge or property.
		final List<VmnicEdge> vmnicEdges = new ArrayList<>();
		final Map<String, String[]> vmnicLldpByPortKey = new HashMap<>();
		int vmnicHostCount;
		int vmnicNeighbourCount;
		int vmnicAmbiguousCount;
		int vmnicUnmatchedCount;

		static Snapshot build(UniFiApiClient api, UniFiAdapter adapter)
				throws Exception {
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

			int devTotal = 0;
			for (SimpleJson site : siteData.asList()) {
				String siteName = site.get("name").asString();
				SimpleJson devices = api.statDevice(siteName);
				s.devicesBySite.put(siteName, devices);
				SimpleJson devData = devices.get("data");
				if (!devData.isNull() && devData.isList()) {
					for (SimpleJson dev : devData.asList()) {
						String mac = dev.get("mac").asString("");
						if (!mac.isEmpty()) {
							s.deviceByMac.put(mac, dev);
							devTotal++;
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
			computeVmnicStitch(s, adapter);

			// Protect (optional sub-tree).
			try {
				SimpleJson protect = api.protectBootstrap();
				if (!protect.get("nvr").isNull()) {
					s.protect = protect;
					SimpleJson cams = protect.get("cameras");
					if (!cams.isNull()) {
						for (SimpleJson cam : cams.asList()) {
							String mac = cam.get("mac").asString("");
							if (!mac.isEmpty()) s.cameraByMac.put(mac, cam);
						}
					}
				}
			} catch (Exception e) {
				adapter.logInfo("Snapshot: Protect not available: " + e.getMessage());
			}

			adapter.logInfo("UniFi API snapshot: " + siteData.size() + " sites / "
					+ devTotal + " devices / " + s.cameraByMac.size() + " cameras / "
					+ "protect=" + (s.protect != null));
			return s;
		}

		SimpleJson devicesForSite(String siteName) {
			SimpleJson d = devicesBySite.get(siteName);
			return d != null ? d : SimpleJson.parse("{\"data\":[]}");
		}

		SimpleJson healthForSite(String siteName) {
			return healthBySite.get(siteName);
		}

		SimpleJson findSite(String siteName) {
			for (SimpleJson site : sites.get("data").asList()) {
				if (siteName.equals(site.get("name").asString())) return site;
			}
			return null;
		}

		SimpleJson findDevice(String mac) {
			return deviceByMac.get(mac);
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
	 */
	private static void computeVmnicStitch(Snapshot s, UniFiAdapter adapter) {
		UniFiStitcher st = adapter.stitcher;
		if (st == null) return;
		try {
			Map<String, List<OwnPort>> ownIndex = buildOwnPortIndex(s);
			List<UniFiStitcher.ForeignHost> hosts = st.listHostsWithVmnicLldp();
			s.vmnicHostCount = hosts.size();

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
					s.vmnicEdges.add(new VmnicEdge(host.key, match.portKey, match.portName));
					// LLDP|* repurpose (design §7.3): the matched ESXi
					// host's own resource name + the vmnic label it was
					// seen on — strictly better data than the controller's
					// never-populated lldp_table ever had for these fields.
					s.vmnicLldpByPortKey.put(match.portKey,
							new String[]{host.key.getResourceName(), n.vmnic});
				}
			}
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
				String switchMac = dev.get("mac").asString();
				String devName = dev.get("name").asString("");
				SimpleJson ports = dev.get("port_table");
				if (ports.isNull()) continue;
				for (SimpleJson port : ports.asList()) {
					int idx2 = (int) port.get("port_idx").asLong();
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
