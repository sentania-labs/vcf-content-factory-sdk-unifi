package com.vcfcf.adapters.unifi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Build 18 (issue #15): every device reaches the Site, the parent graph has
 * no cycle, and the live devel uplink graph nests as captured. Run from the
 * repo root (ci/run_java_tests.sh).
 */
public final class UplinkTreeTest {

	private static final UplinkTree.Kind GW = UplinkTree.Kind.GATEWAY;
	private static final UplinkTree.Kind SW = UplinkTree.Kind.SWITCH;
	private static final UplinkTree.Kind AP = UplinkTree.Kind.ACCESS_POINT;

	public static void main(String[] args) {
		liveDevelGraph();
		twoNodeLoop();
		longLoopWithTail();
		selfUplink();
		unknownUplink();
		unsupportedNesting();
		gatewayAlwaysUnderSite();
		duplicateMacKeepsFirst();
		System.out.println("UplinkTreeTest: all assertions passed");
	}

	static UplinkTree.Node n(String mac, UplinkTree.Kind k, String up) {
		return new UplinkTree.Node(mac, k, up);
	}

	/** Every node reaches the Site through parents, with no repeat. */
	static void assertAcyclic(UplinkTree.Result r, List<UplinkTree.Node> nodes) {
		for (UplinkTree.Node x : nodes) {
			Set<String> seen = new HashSet<>();
			String cur = x.mac;
			while (cur != null) {
				T.check(seen.add(cur), "cycle through " + cur);
				cur = r.parent.get(cur);
			}
		}
	}

	static int depth(UplinkTree.Result r, String mac) {
		int d = 0;
		for (String cur = r.parent.get(mac); cur != null; cur = r.parent.get(cur)) d++;
		return d;
	}

	/**
	 * Devel console, 2026-10-28: 1 site, 17 devices (MAC tails), UDM Pro
	 * gateway; switch chains up to four deep, an AP behind an in-wall AP.
	 */
	static void liveDevelGraph() {
		List<UplinkTree.Node> nodes = Arrays.asList(
				n("51:a2", SW, "0e:82"), n("46:29", SW, "51:a2"),
				n("21:56", AP, "cb:e2"), n("cb:e2", SW, "c5:f7"),
				n("72:2a", SW, "cb:e2"), n("50:10", SW, "f9:47"),
				n("dc:68", AP, "3a:79"), n("ab:80", AP, "c5:f7"),
				n("f9:47", SW, "0e:82"), n("3c:35", SW, "cb:e2"),
				n("0e:82", GW, ""), n("77:c2", AP, "ab:80"),
				n("ef:48", AP, "c5:f7"), n("eb:ef", SW, "f9:47"),
				n("c5:f7", SW, "51:a2"), n("3a:79", SW, "cb:e2"),
				n("ae:18", AP, "72:2a"));
		UplinkTree.Result r = UplinkTree.resolve(nodes);
		T.eq(0, r.fallback.size(), "no fallback on devel");
		T.eq(16, r.parent.size(), "every device but the gateway nests");
		T.eq(null, r.parent.get("0e:82"), "gateway under the site");
		T.eq("ab:80", r.parent.get("77:c2"), "AP behind an in-wall AP");
		T.eq(5, depth(r, "ae:18"), "deepest: GW > SW > SW > SW > SW > AP");
		T.eq(5, depth(r, "dc:68"), "GW > SW > SW > SW > SW > AP");
		assertAcyclic(r, nodes);
	}

	/** A to B to A: both under the Site, both logged as a loop. */
	static void twoNodeLoop() {
		List<UplinkTree.Node> nodes = Arrays.asList(
				n("a", SW, "b"), n("b", SW, "a"), n("c", AP, "a"));
		UplinkTree.Result r = UplinkTree.resolve(nodes);
		T.eq(null, r.parent.get("a"), "a under site");
		T.eq(null, r.parent.get("b"), "b under site");
		T.eq(UplinkTree.Reason.LOOP, r.fallback.get("a"), "a loop");
		T.eq(UplinkTree.Reason.LOOP, r.fallback.get("b"), "b loop");
		T.eq("a", r.parent.get("c"), "a device below the loop keeps its parent");
		T.eq(null, r.fallback.get("c"), "and is not logged");
		assertAcyclic(r, nodes);
	}

	/** A tail walked first into a three-node loop: only the loop is cut. */
	static void longLoopWithTail() {
		List<UplinkTree.Node> nodes = Arrays.asList(
				n("t", SW, "x"), n("x", SW, "y"), n("y", SW, "z"),
				n("z", SW, "x"), n("g", GW, ""), n("s", SW, "g"));
		UplinkTree.Result r = UplinkTree.resolve(nodes);
		T.eq("x", r.parent.get("t"), "tail keeps its parent");
		for (String m : new String[] {"x", "y", "z"}) {
			T.eq(null, r.parent.get(m), m + " under site");
			T.eq(UplinkTree.Reason.LOOP, r.fallback.get(m), m + " loop");
		}
		T.eq("g", r.parent.get("s"), "unrelated chain untouched");
		T.eq(3, r.fallback.size(), "only the loop is logged");
		assertAcyclic(r, nodes);
	}

	static void selfUplink() {
		UplinkTree.Result r = UplinkTree.resolve(Arrays.asList(n("a", SW, "a")));
		T.eq(null, r.parent.get("a"), "self uplink under site");
		T.eq(UplinkTree.Reason.LOOP, r.fallback.get("a"), "self uplink is a loop");
	}

	static void unknownUplink() {
		UplinkTree.Result r = UplinkTree.resolve(Arrays.asList(
				n("a", SW, "ff:ff"), n("b", AP, "")));
		T.eq(null, r.parent.get("a"), "unknown uplink under site");
		T.eq(UplinkTree.Reason.UNKNOWN_UPLINK, r.fallback.get("a"), "logged");
		T.eq(null, r.fallback.get("b"), "no uplink is not logged");
	}

	/** A switch behind an AP cannot show in the tree: Site, logged. */
	static void unsupportedNesting() {
		List<UplinkTree.Node> nodes = Arrays.asList(
				n("ap", AP, "sw0"), n("sw0", SW, ""), n("flex", SW, "ap"),
				n("ap2", AP, "flex"));
		UplinkTree.Result r = UplinkTree.resolve(nodes);
		T.eq(null, r.parent.get("flex"), "switch behind an AP under site");
		T.eq(UplinkTree.Reason.UNSUPPORTED_NESTING, r.fallback.get("flex"), "logged");
		T.eq("flex", r.parent.get("ap2"), "its own AP still nests under it");
		T.eq("sw0", r.parent.get("ap"), "AP under switch");
		assertAcyclic(r, nodes);
	}

	static void gatewayAlwaysUnderSite() {
		UplinkTree.Result r = UplinkTree.resolve(Arrays.asList(
				n("g", GW, "s"), n("s", SW, "g")));
		T.eq(null, r.parent.get("g"), "gateway under site even with an uplink");
		T.eq(null, r.fallback.get("g"), "not logged");
		T.eq("g", r.parent.get("s"), "switch under gateway, no loop");
		T.check(UplinkTree.nests(AP, GW) && UplinkTree.nests(AP, SW)
				&& UplinkTree.nests(AP, AP) && UplinkTree.nests(SW, GW)
				&& UplinkTree.nests(SW, SW), "allowed pairs");
		T.check(!UplinkTree.nests(SW, AP) && !UplinkTree.nests(GW, SW)
				&& !UplinkTree.nests(GW, GW), "refused pairs");
	}

	static void duplicateMacKeepsFirst() {
		List<UplinkTree.Node> nodes = new ArrayList<>(Arrays.asList(
				n("a", SW, ""), n("a", AP, "b"), n("b", SW, "a")));
		UplinkTree.Result r = UplinkTree.resolve(nodes);
		T.eq(null, r.parent.get("a"), "first entry wins: a has no uplink");
		T.eq("a", r.parent.get("b"), "b under a");
		assertAcyclic(r, nodes);
	}
}
