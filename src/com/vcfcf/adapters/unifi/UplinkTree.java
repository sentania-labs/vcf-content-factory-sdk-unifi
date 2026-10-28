package com.vcfcf.adapters.unifi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build 18 (issue #15): which device each Network device hangs under in the
 * relationship tree. No VCF Ops SDK and no framework-jar dependency, so the
 * plain-main tests drive exactly the logic the adapter runs.
 *
 * <p>A device is a child of its uplink device ({@code uplink.uplink_mac}),
 * which serves impact analysis the way vSphere's Cluster &gt; Host &gt; VM
 * does: a switch going down shows on everything cabled below it. The
 * "UniFi Controller Infrastructure" navigation tree declares the shapes
 * Site &gt; [Gateway] &gt; Switch* &gt; Access Point* (describe.xml), so a
 * device is a child of its uplink only when the pair fits that shape:
 * a switch under a gateway or a switch, an access point under a gateway,
 * a switch or an access point. Anything else is a child of the Site, so
 * every device stays reachable in the tree and the parent graph has no
 * cycle:
 *
 * <ul>
 *   <li>no uplink, or a gateway (always under the Site): no log;</li>
 *   <li>an uplink to a device not in this site's discovered set: {@link
 *       Reason#UNKNOWN_UPLINK};</li>
 *   <li>an uplink that does not fit the tree's shape (a switch behind an
 *       access point, through a wireless or pass-through uplink): {@link
 *       Reason#UNSUPPORTED_NESTING};</li>
 *   <li>every device on an uplink loop (itself included, A to B to A, and
 *       longer): {@link Reason#LOOP}. A device below the loop keeps its
 *       own uplink parent, which now reaches the Site.</li>
 * </ul>
 */
final class UplinkTree {

	private UplinkTree() {}

	enum Kind { GATEWAY, SWITCH, ACCESS_POINT }

	enum Reason {
		UNKNOWN_UPLINK("its uplink is not a discovered device of this site"),
		UNSUPPORTED_NESTING("its uplink is a device it cannot nest under "
				+ "in the navigation tree"),
		LOOP("its uplink chain loops back on itself");

		final String text;

		Reason(String text) {
			this.text = text;
		}
	}

	static final class Node {
		final String mac;
		final Kind kind;
		final String uplinkMac;

		Node(String mac, Kind kind, String uplinkMac) {
			this.mac = mac;
			this.kind = kind;
			this.uplinkMac = uplinkMac == null ? "" : uplinkMac.trim();
		}
	}

	static final class Result {
		/** Device MAC to its parent device's MAC; absent means the Site. */
		final Map<String, String> parent;
		/** Devices put under the Site for a reason worth one log line. */
		final Map<String, Reason> fallback;

		Result(Map<String, String> parent, Map<String, Reason> fallback) {
			this.parent = parent;
			this.fallback = fallback;
		}
	}

	/** Whether {@code child} may hang under {@code parent} in the tree. */
	static boolean nests(Kind child, Kind parent) {
		switch (child) {
			case SWITCH:
				return parent == Kind.GATEWAY || parent == Kind.SWITCH;
			case ACCESS_POINT:
				return parent != null;
			default:
				return false; // a gateway is always under the Site
		}
	}

	/**
	 * Resolve every node's parent. Nodes with a duplicate MAC keep the
	 * first; nodes are visited in the order given, so the result is
	 * deterministic.
	 */
	static Result resolve(Collection<Node> nodes) {
		Map<String, Node> byMac = new LinkedHashMap<>();
		for (Node n : nodes) {
			if (n != null && n.mac != null) byMac.putIfAbsent(n.mac, n);
		}

		Map<String, String> parent = new LinkedHashMap<>();
		Map<String, Reason> fallback = new LinkedHashMap<>();
		for (Node n : byMac.values()) {
			if (n.kind == Kind.GATEWAY || n.uplinkMac.isEmpty()) continue;
			Node up = byMac.get(n.uplinkMac);
			if (up == null) {
				fallback.put(n.mac, Reason.UNKNOWN_UPLINK);
			} else if (up == n) {
				fallback.put(n.mac, Reason.LOOP);
			} else if (!nests(n.kind, up.kind)) {
				fallback.put(n.mac, Reason.UNSUPPORTED_NESTING);
			} else {
				parent.put(n.mac, up.mac);
			}
		}

		// Walk each chain with a visited set; cut every device on a loop.
		Map<String, Integer> state = new HashMap<>(); // 1 on this walk, 2 done
		for (String start : byMac.keySet()) {
			List<String> walk = new ArrayList<>();
			String cur = start;
			while (cur != null && !state.containsKey(cur)) {
				state.put(cur, 1);
				walk.add(cur);
				cur = parent.get(cur);
			}
			if (cur != null && state.get(cur) == 1) {
				for (int i = walk.indexOf(cur); i < walk.size(); i++) {
					String m = walk.get(i);
					parent.remove(m);
					fallback.put(m, Reason.LOOP);
				}
			}
			for (String m : walk) state.put(m, 2);
		}
		return new Result(parent, fallback);
	}
}
