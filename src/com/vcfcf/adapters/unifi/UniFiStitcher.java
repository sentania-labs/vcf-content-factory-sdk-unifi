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
 * <p><b>Bulk property read, cached by the caller (build 15, issue #21).</b>
 * {@link #listHostsWithVmnicLldp()} performs Call A ({@code GET
 * /api/resources?adapterKind=VMWARE&resourceKind=HostSystem}) and then reads
 * the hosts' properties in bulk, {@link VmnicLldp#BULK_BATCH} hosts per
 * {@code GET /api/resources/properties?resourceId=..} request, instead of one
 * {@code GET /api/resources/{id}/properties} per host. A batch whose bulk
 * request fails, or whose answer names none of the hosts asked for, is read
 * per host as before (one WARN per such batch), so an appliance that does
 * not answer the bulk form still stitches. Before build 15 this ran on every
 * snapshot build; the adapter now holds the result in a {@link RefreshCache}
 * and calls this at most every 15 minutes.
 *
 * <p><b>Same optional semantics as build ≤8.</b> Callers gate on
 * {@code stitcher == null} (Suite API unavailable — e.g. a remote collector
 * without resolvable ambient credentials); this class performs no such gating
 * itself; a Call A/B failure propagates as an exception so the caller's
 * try/catch can WARN once and skip the cycle's cross-link without touching
 * internal UniFi topology.
 */
public final class UniFiStitcher {

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
	 * ({@code identifier}). Call B then reads the hosts' published LLDP
	 * vmnic properties in bulk (build 15, issue #21: {@code GET
	 * /api/resources/properties?resourceId=..}, {@link VmnicLldp#BULK_BATCH}
	 * hosts per request), falling back to {@code GET
	 * /api/resources/{identifier}/properties} per host for a batch whose bulk
	 * read fails or answers none of its hosts. A host with no top-level
	 * {@code identifier} cannot be queried for Call B and is skipped (debug,
	 * not fabricated). A per-host Call B failure is caught, logged at WARN,
	 * and that host contributes zero vmnic neighbours; it does not abort
	 * enumeration of the remaining hosts. An interrupt (Stop Collecting) is
	 * thrown.
	 *
	 * @return one entry per HostSystem the Suite API returned; never
	 *         {@code null}, may be empty
	 * @throws Exception if Call A itself fails, or its answer carries no
	 *         {@code resourceList} (peer review 2)
	 */
	public List<ForeignHost> listHostsWithVmnicLldp() throws Exception {
		List<ForeignHost> out = new ArrayList<>();
		List<PendingHost> pending = new ArrayList<>();
		String body = stitcher.get("/api/resources?adapterKind="
				+ java.net.URLEncoder.encode("VMWARE", "UTF-8")
				+ "&resourceKind="
				+ java.net.URLEncoder.encode("HostSystem", "UTF-8")
				+ "&pageSize=10000");
		SimpleJson parsed = SimpleJson.parse(body);
		SimpleJson list = parsed == null ? null : parsed.get("resourceList");
		// Peer review 2: no resourceList is a failed read (thrown, so the
		// cache reuses its last good list), never an empty host list.
		VmnicLldp.requireResourceList(parsed != null && parsed.isObject(),
				list != null && list.isList());

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

			// The top-level resource UUID (build 8's bridge never read this;
			// build 9 needs it for Call B). Absent: this host cannot be
			// queried for its LLDP properties; skip it, no fabrication.
			String identifier = r.get("identifier").asString(null);
			if (identifier == null || identifier.isEmpty()) {
				logger.debug("listHostsWithVmnicLldp: host " + name
						+ " has no top-level resource identifier, cannot fetch "
						+ "properties, skipping");
				out.add(new ForeignHost(hostKey, java.util.Collections.emptyList()));
				continue;
			}
			pending.add(new PendingHost(out.size(), hostKey, identifier, name));
			out.add(null); // filled below, keeping Call A order
		}

		// Build 15 (issue #21): Call B in bulk, batches of BULK_BATCH.
		List<String> ids = new ArrayList<>();
		for (PendingHost h : pending) ids.add(h.identifier);
		List<String> paths = VmnicLldp.bulkPropertiesPaths(ids, VmnicLldp.BULK_BATCH);
		List<List<String>> batches = VmnicLldp.batches(ids, VmnicLldp.BULK_BATCH);
		int fallbacks = 0;
		int omitted = 0;
		int unread = 0;
		int next = 0;
		for (int b = 0; b < paths.size(); b++) {
			List<String> batch = batches.get(b);
			Map<String, List<VmnicNeighbour>> byId = fetchBulk(paths.get(b),
					batch.size());
			if (byId == null) fallbacks++;
			// Peer review 1: a host the bulk answer omits is read per host,
			// like a failed batch; one that still cannot be read is unread.
			java.util.Set<String> perHost = new java.util.HashSet<>(byId == null
					? batch : VmnicLldp.missingFromAnswer(batch, byId.keySet()));
			if (byId != null && !perHost.isEmpty()) {
				omitted += perHost.size();
			}
			for (int i = 0; i < batch.size(); i++, next++) {
				PendingHost h = pending.get(next);
				List<VmnicNeighbour> vmnics;
				if (perHost.contains(h.identifier)) {
					vmnics = fetchVmnicNeighbours(h.identifier, h.name);
					if (vmnics == null) {
						unread++;
						vmnics = java.util.Collections.emptyList();
					}
				} else {
					vmnics = byId.get(h.identifier);
				}
				out.set(h.index, new ForeignHost(h.key, vmnics));
			}
		}
		// Build 15 (issue #21): the caller keeps this result for 15 minutes,
		// so a refresh that could read no host's properties at all is a
		// failure (the caller reuses its last good list), not a list of
		// hosts without neighbours.
		if (!pending.isEmpty() && unread == pending.size()) {
			throw new java.io.IOException("vmnic stitch: the properties of none of "
					+ pending.size() + " hosts could be read");
		}
		logger.info("vmnic stitch: read properties of " + pending.size()
				+ " hosts in " + paths.size() + " bulk request(s)"
				+ (fallbacks == 0 ? "" : ", " + fallbacks
						+ " batch(es) read per host after the bulk read failed")
				+ (omitted == 0 ? "" : ", " + omitted
						+ " host(s) missing from a bulk answer read per host")
				+ (unread == 0 ? "" : ", " + unread + " host(s) unread"));
		// Review N5: some hosts unread is a partial refresh. The caller's
		// cache reuses its last good list instead (or, with none, uses this
		// one for the cycle without keeping it), so an unread host does not
		// lose its LLDP claimant for 15 minutes.
		if (unread > 0) {
			throw new RefreshCache.Partial("vmnic stitch: the properties of "
					+ unread + " of " + pending.size() + " hosts could not be read",
					out);
		}
		return out;
	}

	/** A host from Call A waiting for its properties (Call B). */
	private static final class PendingHost {
		final int index;
		final ResourceKey key;
		final String identifier;
		final String name;

		PendingHost(int index, ResourceKey key, String identifier, String name) {
			this.index = index;
			this.key = key;
			this.identifier = identifier;
			this.name = name;
		}
	}

	/**
	 * Build 15 (issue #21): one bulk properties request. Returns each
	 * answered host's vmnic neighbours by resource id, or {@code null} when
	 * the request failed or its answer has no {@code resourcePropertiesList}
	 * naming any host (the caller then reads that batch per host). A host
	 * the answer omits while naming others is read per host by the caller
	 * (peer review 1), never taken for a host with no neighbours.
	 */
	private Map<String, List<VmnicNeighbour>> fetchBulk(String path, int asked)
			throws InterruptedException {
		try {
			SimpleJson parsed = SimpleJson.parse(stitcher.get(path));
			SimpleJson list = parsed.get("resourcePropertiesList");
			Map<String, List<VmnicNeighbour>> out = new HashMap<>();
			for (SimpleJson rp : list.asList()) {
				String id = rp.get("resourceId").asString(null);
				if (id == null || id.isEmpty()) continue;
				out.put(id, toNeighbours(rp.get("property"), id));
			}
			if (out.isEmpty()) {
				logger.warn("vmnic stitch: bulk properties read for " + asked
						+ " hosts answered none of them; reading them one by one");
				return null;
			}
			return out;
		} catch (InterruptedException e) {
			throw e;
		} catch (Exception e) {
			logger.warn("vmnic stitch: bulk properties read for " + asked
					+ " hosts failed (" + e.getMessage()
					+ "); reading them one by one");
			return null;
		}
	}

	/**
	 * Call B for one host (the build 9 per-host read, now the fallback): read
	 * its latest properties and extract every complete vmnic LLDP neighbour
	 * pair. A failure is logged WARN and returns {@code null} (the host
	 * contributes no neighbours; the caller counts it as unread).
	 */
	private List<VmnicNeighbour> fetchVmnicNeighbours(String hostResourceId,
			String hostName) throws InterruptedException {
		try {
			String body = stitcher.get("/api/resources/"
					+ java.net.URLEncoder.encode(hostResourceId, "UTF-8")
					+ "/properties");
			return toNeighbours(SimpleJson.parse(body).get("property"), hostName);
		} catch (InterruptedException e) {
			throw e;
		} catch (Exception e) {
			logger.warn("fetchVmnicNeighbours: properties fetch failed for host "
					+ hostName + " (" + hostResourceId + "): " + e.getMessage());
			return null;
		}
	}

	/** The vmnic LLDP neighbour pairs in one {@code property[]} list. */
	private List<VmnicNeighbour> toNeighbours(SimpleJson props, String hostName) {
		List<String[]> pairs = new ArrayList<>();
		for (SimpleJson p : props.asList()) {
			pairs.add(new String[]{p.get("name").asString(null),
					p.get("value").asString(null)});
		}
		List<VmnicNeighbour> out = new ArrayList<>();
		for (String[] n : VmnicLldp.neighbours(pairs, hostName, logger::debug)) {
			out.add(new VmnicNeighbour(n[0], n[1], n[2]));
		}
		return out;
	}
}
