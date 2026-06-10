package com.vcfcf.adapters.unifi;

import com.vcfcf.adapter.json.SimpleJson;
import com.vcfcf.adapter.stitch.ForeignResourceResolver;
import com.vcfcf.adapter.stitch.SuiteApiStitcher;

import com.integrien.alive.common.adapter3.Logger;
import com.integrien.alive.common.adapter3.ResourceKey;

import java.util.ArrayList;
import java.util.List;

/**
 * UniFi → VMWARE HostSystem LLDP cross-link resolver (framework v2, build 3).
 *
 * <p><b>What it restores.</b> v1 emitted an <em>informational</em>
 * {@code HostSystem → UniFiSwitchPort} cross-link: each switch port whose LLDP
 * neighbour {@code lldp_system_name} matches the name of a real VMWARE
 * {@code HostSystem} becomes a child of that host, so the physical switch port a
 * given ESXi host is plugged into shows up in the host's dependency graph. v1
 * resolved the host by its {@code VMEntityName} identity via
 * {@code ForeignResourceResolver.findByIdentifier("VMWARE","HostSystem",
 * "VMEntityName", sysName)} — never a MOID.
 *
 * <p><b>Same optional semantics as v1.</b> v1's {@code stitchLldpToHosts} was
 * gated on Suite API availability ({@code hostResolver == null} → no-op). v2
 * carries that exact behaviour: the resolver is wired through the ambient
 * {@link SuiteApiStitcher}; on a remote collector with no
 * {@code maintenanceuser.properties} the stitcher is unavailable, the cross-link
 * is skipped for the cycle, and all UniFi resources still collect. A port whose
 * LLDP neighbour is not a known ESXi host yields no edge — never a fabricated
 * HostSystem.
 *
 * <p><b>Resolves against real inventory.</b> Like the Synology build-16 stitcher,
 * matches resolve against the actual VMWARE HostSystem inventory pulled over
 * {@code GET /api/resources}; zero matches on a working connection is legitimate
 * (no LLDP-reachable ESXi host) — never WARN spam, never a phantom edge.
 */
public final class UniFiStitcher {

	private final ForeignResourceResolver resolver;
	private final Logger logger;

	public UniFiStitcher(SuiteApiStitcher stitcher, Logger logger) {
		this.logger = logger;
		this.resolver = new ForeignResourceResolver(
				new SuiteApiHostBridge(stitcher, logger), logger);
	}

	/**
	 * Resolve a VMWARE {@code HostSystem} {@link ResourceKey} by its
	 * {@code VMEntityName} value (the LLDP neighbour system name), or {@code null}
	 * when no such host exists in inventory. The underlying resolver caches the
	 * full HostSystem listing, so repeated lookups within a cycle cost one pull.
	 */
	public ResourceKey matchHostByName(String systemName) {
		if (systemName == null || systemName.isEmpty()) return null;
		return resolver.findByIdentifier("VMWARE", "HostSystem",
				"VMEntityName", systemName);
	}

	/** Drop the cached HostSystem listing (called per cycle before stitching). */
	public void invalidateCache() {
		resolver.invalidateCache();
	}

	/**
	 * {@link ForeignResourceResolver.SuiteApiBridge} over the ambient Suite API.
	 * Lists VMWARE HostSystems via {@code GET /api/resources} and maps each entry
	 * to a {@link ForeignResourceResolver.ResourceEntry} carrying its identifiers.
	 * Parse shape matches the compliance / Synology adapters' {@code fetchResources}.
	 */
	private static final class SuiteApiHostBridge
			implements ForeignResourceResolver.SuiteApiBridge {

		private final SuiteApiStitcher stitcher;
		private final Logger logger;

		SuiteApiHostBridge(SuiteApiStitcher stitcher, Logger logger) {
			this.stitcher = stitcher;
			this.logger = logger;
		}

		@Override
		public List<ForeignResourceResolver.ResourceEntry> listResources(
				String adapterKind, String resourceKind) throws Exception {
			List<ForeignResourceResolver.ResourceEntry> out = new ArrayList<>();
			String body = stitcher.get("/api/resources?adapterKind="
					+ java.net.URLEncoder.encode(adapterKind, "UTF-8")
					+ "&resourceKind="
					+ java.net.URLEncoder.encode(resourceKind, "UTF-8")
					+ "&pageSize=10000");
			SimpleJson parsed = SimpleJson.parse(body);
			if (parsed == null || parsed.isNull()) return out;
			SimpleJson list = parsed.get("resourceList");
			if (list == null || !list.isList()) return out;
			for (SimpleJson r : list.asList()) {
				if (r == null || r.isNull()) continue;
				SimpleJson key = r.get("resourceKey");
				if (key == null || key.isNull()) continue;
				String name = key.get("name").asString(null);
				List<String[]> identifiers = new ArrayList<>();
				SimpleJson ids = key.get("resourceIdentifiers");
				if (ids != null && ids.isList()) {
					for (SimpleJson id : ids.asList()) {
						String idName = id.get("identifierType").get("name")
								.asString(null);
						String idVal = id.get("value").asString(null);
						if (idName == null) continue;
						identifiers.add(new String[]{idName,
								idVal == null ? "" : idVal, "true"});
					}
				}
				out.add(new ForeignResourceResolver.ResourceEntry(
						adapterKind, resourceKind, name, identifiers));
			}
			return out;
		}
	}
}
