package com.vcfcf.adapters.unifi;

import com.vcfcf.adapter.json.SimpleJson;
import com.vcfcf.adapter.stitch.SuiteApiStitcher;

import com.integrien.alive.common.adapter3.Logger;
import com.integrien.alive.common.adapter3.ResourceKey;
import com.integrien.alive.common.adapter3.config.ResourceIdentifierConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UniFi → VMWARE HostSystem vmnic↔switch-port join (build 9, vCenter-side).
 *
 * <p><b>Why the join direction inverted.</b> Builds ≤8 tried to resolve each
 * switch port's LLDP neighbour ({@code port_table[].lldp_table[].lldp_system_name})
 * against a VMWARE {@code HostSystem} by name — a controller-side read. On
 * Network App 10.2.105 that table is empty for every port; the controller does
 * not re-publish the switch's own LLDP daemon's neighbours over any API surface
 * (classic / v2 / Integration). See
 * {@code context/investigations/unifi-lldp-switchport-esxi-2026-07-05.md}.
 * vCenter, however, publishes the same neighbour data per HostSystem
 * ({@code net:vmnic<N>|discoveryProtocol|lldp|systemName} /
 * {@code ...|portName}), live-proven on all DEVEL hosts. Build 9 enumerates
 * HostSystems and reads that per-vmnic LLDP data instead, then matches it back
 * into the adapter's own UniFiSwitch / UniFiSwitchPort inventory — the
 * opposite direction of build ≤8's {@code matchHostByName}, which is removed.
 *
 * <p><b>No cross-cycle cache.</b> {@link #listHostsWithVmnicLldp()} performs a
 * fresh Call A ({@code GET /api/resources?adapterKind=VMWARE&resourceKind=
 * HostSystem}) plus one Call B per host ({@code GET /api/resources/{id}/
 * properties}) on every invocation — the caller ({@code Snapshot.build()})
 * already throttles the overall refresh to once per {@code
 * MIN_REFRESH_INTERVAL_MS}, so no additional TTL cache is needed here (the
 * build ≤8 {@code ForeignResourceResolver} 5-minute cache is gone along with
 * the resolver itself).
 *
 * <p><b>Same optional semantics as build ≤8.</b> Callers gate on
 * {@code stitcher == null} (Suite API unavailable — e.g. a remote collector
 * without resolvable ambient credentials); this class performs no such gating
 * itself; a Call A/B failure propagates as an exception so the caller's
 * try/catch can WARN once and skip the cycle's cross-link without touching
 * internal UniFi topology.
 */
public final class UniFiStitcher {

	private static final String LLDP_ANCHOR = "|discoveryProtocol|lldp|";

	private final SuiteApiStitcher stitcher;
	private final Logger logger;

	public UniFiStitcher(SuiteApiStitcher stitcher, Logger logger) {
		this.stitcher = stitcher;
		this.logger = logger;
	}

	/**
	 * Debug-level passthrough for the caller's per-no-match / per-ambiguous-
	 * match lines (§5 of the build-9 design: unmatched and ambiguous vmnic
	 * pairs are debug-only, never per-cycle WARN spam — only the aggregate
	 * counts surface at INFO, in the caller's one-line cycle summary).
	 */
	public void debug(String message) {
		logger.debug(message);
	}

	/** One VMWARE HostSystem discovered on the Suite API, its identity, and the
	 * per-vmnic LLDP neighbour pairs it advertises. */
	public static final class ForeignHost {
		public final ResourceKey key;
		public final List<VmnicNeighbour> vmnics;

		ForeignHost(ResourceKey key, List<VmnicNeighbour> vmnics) {
			this.key = key;
			this.vmnics = vmnics;
		}
	}

	/** One vmnic's LLDP neighbour pair — the UniFi switch device name it sees
	 * and the UniFi switch-port display name it is plugged into. */
	public static final class VmnicNeighbour {
		public final String vmnic;
		public final String systemName;
		public final String portName;

		VmnicNeighbour(String vmnic, String systemName, String portName) {
			this.vmnic = vmnic;
			this.systemName = systemName;
			this.portName = portName;
		}
	}

	/**
	 * Enumerate every VMWARE {@code HostSystem} the Suite API knows about, each
	 * with its per-vmnic LLDP neighbour pairs (§2 of the build-9 design:
	 * {@code net:vmnic<N>|discoveryProtocol|lldp|systemName} /
	 * {@code ...|portName}).
	 *
	 * <p>Call A ({@code GET /api/resources?adapterKind=VMWARE&resourceKind=
	 * HostSystem&pageSize=10000}) supplies each host's honest-uniqueness
	 * {@link ResourceKey} plus its Suite API resource UUID
	 * ({@code identifier}). Call B ({@code GET /api/resources/{identifier}/
	 * properties}) is then issued per host to read its published LLDP vmnic
	 * properties. A host with no top-level {@code identifier} cannot be
	 * queried for Call B and is skipped (debug, not fabricated). A Call B
	 * failure for one host is caught, logged at WARN, and that host
	 * contributes zero vmnic neighbours — it does not abort enumeration of the
	 * remaining hosts.
	 *
	 * @return one entry per HostSystem the Suite API returned; never
	 *         {@code null}, may be empty
	 * @throws Exception if Call A itself fails (no host list at all)
	 */
	public List<ForeignHost> listHostsWithVmnicLldp() throws Exception {
		List<ForeignHost> out = new ArrayList<>();
		String body = stitcher.get("/api/resources?adapterKind="
				+ java.net.URLEncoder.encode("VMWARE", "UTF-8")
				+ "&resourceKind="
				+ java.net.URLEncoder.encode("HostSystem", "UTF-8")
				+ "&pageSize=10000");
		SimpleJson parsed = SimpleJson.parse(body);
		if (parsed == null || parsed.isNull()) return out;
		SimpleJson list = parsed.get("resourceList");
		if (list == null || !list.isList()) return out;

		for (SimpleJson r : list.asList()) {
			if (r == null || r.isNull()) continue;
			SimpleJson keyJson = r.get("resourceKey");
			if (keyJson == null || keyJson.isNull()) continue;
			String name = keyJson.get("name").asString(null);

			ResourceKey hostKey = new ResourceKey(name, "HostSystem", "VMWARE");
			SimpleJson ids = keyJson.get("resourceIdentifiers");
			if (ids != null && ids.isList()) {
				for (SimpleJson id : ids.asList()) {
					String idName = id.get("identifierType").get("name").asString(null);
					String idVal = id.get("value").asString(null);
					if (idName == null) continue;
					// Propagate the REAL isPartOfUniqueness flag from the Suite
					// API response — never hardcode "true" for every identifier.
					// A HostSystem's uniqueness-bearing identifier is its
					// (VMEntityObjectID, VMEntityVCID) pair; over-marking a
					// descriptive identifier corrupts the resolved key's
					// effective identity so the platform can never bind the
					// edge — the failure is silent, the edge is emitted every
					// cycle but never persists, with zero error anywhere
					// (lessons/cross-mp-foreign-key-uniqueness-flags.md, the
					// synology .18-.21 reproducer of this exact bug). Absent/
					// null defaults to false — never over-mark.
					boolean isUnique = id.get("identifierType")
							.get("isPartOfUniqueness").asBoolean();
					hostKey.addIdentifier(new ResourceIdentifierConfig(
							idName, idVal == null ? "" : idVal, isUnique));
				}
			}

			// The top-level resource UUID (build 8's bridge never read this —
			// build 9 needs it for Call B). Absent → this host cannot be
			// queried for its LLDP properties; skip it, no fabrication.
			String identifier = r.get("identifier").asString(null);
			if (identifier == null || identifier.isEmpty()) {
				logger.debug("listHostsWithVmnicLldp: host " + name
						+ " has no top-level resource identifier — cannot fetch "
						+ "properties, skipping");
				out.add(new ForeignHost(hostKey, java.util.Collections.emptyList()));
				continue;
			}

			out.add(new ForeignHost(hostKey, fetchVmnicNeighbours(identifier, name)));
		}
		return out;
	}

	/**
	 * Call B for one host: read its latest properties and extract every
	 * complete vmnic LLDP neighbour pair (§2). A vmnic with only one of
	 * {@code systemName}/{@code portName} present is a no-op (debug, no
	 * candidate). CDP-carrying keys ({@code |discoveryProtocol|cdp|...}) never
	 * match the {@code |discoveryProtocol|lldp|} anchor and are silently
	 * excluded — out of scope for build 9.
	 */
	private List<VmnicNeighbour> fetchVmnicNeighbours(String hostResourceId,
			String hostName) {
		List<VmnicNeighbour> out = new ArrayList<>();
		try {
			String body = stitcher.get("/api/resources/"
					+ java.net.URLEncoder.encode(hostResourceId, "UTF-8")
					+ "/properties");
			SimpleJson parsed = SimpleJson.parse(body);
			if (parsed == null || parsed.isNull()) return out;
			SimpleJson props = parsed.get("property");
			if (props == null || !props.isList()) return out;

			Map<String, String> systemNameByVmnic = new HashMap<>();
			Map<String, String> portNameByVmnic = new HashMap<>();
			for (SimpleJson p : props.asList()) {
				String pname = p.get("name").asString(null);
				if (pname == null || !pname.startsWith("net:vmnic")) continue;
				int firstPipe = pname.indexOf('|');
				if (firstPipe < 0) continue;
				String vmnicToken = pname.substring(4, firstPipe); // "vmnic0"
				String rest = pname.substring(firstPipe);
				// Anchor on the literal LLDP leaf path — deliberately excludes
				// CDP hosts (|discoveryProtocol|cdp|...), out of scope here.
				if (!rest.startsWith(LLDP_ANCHOR)) continue;
				String leaf = rest.substring(LLDP_ANCHOR.length());
				String val = p.get("value").asString(null);
				if (val == null || val.isEmpty()) continue;
				if ("systemName".equals(leaf)) {
					systemNameByVmnic.put(vmnicToken, val);
				} else if ("portName".equals(leaf)) {
					portNameByVmnic.put(vmnicToken, val);
				}
			}

			for (Map.Entry<String, String> e : systemNameByVmnic.entrySet()) {
				String vmnic = e.getKey();
				String portName = portNameByVmnic.get(vmnic);
				if (portName == null || portName.isEmpty()) {
					logger.debug("fetchVmnicNeighbours: host " + hostName + " "
							+ vmnic + " has systemName but no portName — no "
							+ "candidate");
					continue;
				}
				out.add(new VmnicNeighbour(vmnic, e.getValue(), portName));
			}
		} catch (Exception e) {
			logger.warn("fetchVmnicNeighbours: properties fetch failed for host "
					+ hostName + " (" + hostResourceId + "): " + e.getMessage());
		}
		return out;
	}
}
