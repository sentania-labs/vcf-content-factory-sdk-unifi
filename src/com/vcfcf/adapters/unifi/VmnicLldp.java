package com.vcfcf.adapters.unifi;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Build 15 (issue #21): the SDK-free half of the vmnic stitch's Suite API
 * read, so the plain-main tests drive it. {@link UniFiStitcher} does the
 * HTTP and the JSON; this class builds the bulk request paths and picks the
 * per-vmnic LLDP neighbour pairs out of a host's property list (the
 * extraction is the one build 9 introduced, moved here unchanged).
 */
final class VmnicLldp {

	private VmnicLldp() {}

	static final String LLDP_ANCHOR = "|discoveryProtocol|lldp|";

	/** Host resource ids per bulk properties request. */
	static final int BULK_BATCH = 50;

	/**
	 * {@code GET /api/resources/properties?resourceId=..&resourceId=..}
	 * paths (Suite API {@code getResourcePropertiesList}), {@code batch} ids
	 * per path, in order. Empty for no ids.
	 */
	static List<String> bulkPropertiesPaths(List<String> resourceIds, int batch) {
		if (batch < 1) throw new IllegalArgumentException("batch < 1");
		List<String> paths = new ArrayList<>();
		for (int i = 0; i < resourceIds.size(); i += batch) {
			StringBuilder sb = new StringBuilder("/api/resources/properties?");
			List<String> slice = resourceIds.subList(i,
					Math.min(i + batch, resourceIds.size()));
			for (int j = 0; j < slice.size(); j++) {
				if (j > 0) sb.append('&');
				sb.append("resourceId=").append(
						URLEncoder.encode(slice.get(j), StandardCharsets.UTF_8));
			}
			paths.add(sb.toString());
		}
		return paths;
	}

	/** The ids each path from {@link #bulkPropertiesPaths} asks for, in order. */
	static List<List<String>> batches(List<String> resourceIds, int batch) {
		List<List<String>> out = new ArrayList<>();
		for (int i = 0; i < resourceIds.size(); i += batch) {
			out.add(new ArrayList<>(resourceIds.subList(i,
					Math.min(i + batch, resourceIds.size()))));
		}
		return out;
	}

	/**
	 * Every complete vmnic LLDP neighbour pair in one host's properties,
	 * as {@code {vmnic, systemName, portName}}. {@code properties} holds
	 * {@code {name, value}} pairs. Only
	 * {@code net:vmnic<N>|discoveryProtocol|lldp|systemName} and
	 * {@code ...|portName} with a non-empty value count (CDP keys never
	 * match the anchor); a vmnic with a system name but no port name yields
	 * nothing and is reported to {@code debug}.
	 */
	static List<String[]> neighbours(List<String[]> properties, String hostName,
			Consumer<String> debug) {
		Map<String, String> systemNameByVmnic = new HashMap<>();
		Map<String, String> portNameByVmnic = new HashMap<>();
		for (String[] p : properties) {
			String pname = p[0];
			if (pname == null || !pname.startsWith("net:vmnic")) continue;
			int firstPipe = pname.indexOf('|');
			if (firstPipe < 0) continue;
			String vmnicToken = pname.substring(4, firstPipe); // "vmnic0"
			String rest = pname.substring(firstPipe);
			if (!rest.startsWith(LLDP_ANCHOR)) continue;
			String leaf = rest.substring(LLDP_ANCHOR.length());
			String val = p[1];
			if (val == null || val.isEmpty()) continue;
			if ("systemName".equals(leaf)) {
				systemNameByVmnic.put(vmnicToken, val);
			} else if ("portName".equals(leaf)) {
				portNameByVmnic.put(vmnicToken, val);
			}
		}
		List<String[]> out = new ArrayList<>();
		for (Map.Entry<String, String> e : systemNameByVmnic.entrySet()) {
			String vmnic = e.getKey();
			String portName = portNameByVmnic.get(vmnic);
			if (portName == null || portName.isEmpty()) {
				if (debug != null) {
					debug.accept("fetchVmnicNeighbours: host " + hostName + " "
							+ vmnic + " has systemName but no portName, no "
							+ "candidate");
				}
				continue;
			}
			out.add(new String[]{vmnic, e.getValue(), portName});
		}
		return out;
	}
}
