package com.vcfcf.adapters.unifi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Issue #20, second half: a cycle without Protect does not drop and re-add
 * the NVR edges ({@link ProtectEdgeHold}), and a cycle without the Suite API
 * does not drop the vmnic stitch edges. The model below is the framework's
 * relationship write as RelationshipBuilderTest pins it: an own parent that
 * is published replaces its children (clear first), an own parent that is
 * not published keeps them, a foreign parent (the stitch's HostSystem) is
 * only ever added to. The adapter's part, which parents it publishes on
 * which cycle, is driven through ProtectEdgeHold and mirrors
 * buildRelationships (tests/test_vanished_wiring.py pins that wiring). Run
 * from the repo root (ci/run_java_tests.sh).
 */
public final class ProtectEdgeHoldTest {

	public static void main(String[] args) {
		protectBlipKeepsCameraEdges();
		suiteApiBlipKeepsStitchEdges();
		recoveredReadUpdatesEdges();
		graceExpiresAfterTwelveCycles();
		noKnownNvrPublishesAsUsual();
		unreadablePlatformListHolds();
		System.out.println("ProtectEdgeHoldTest: all assertions passed");
	}

	/** The platform's edges: parent to children. */
	private static final class Platform {
		final Map<String, Set<String>> children = new LinkedHashMap<>();
		final List<String> changes = new ArrayList<>();

		void set(String parent, Set<String> kids) {
			Set<String> before = children.getOrDefault(parent, new LinkedHashSet<>());
			if (!before.equals(kids)) changes.add(parent + " " + before + " -> " + kids);
			children.put(parent, new LinkedHashSet<>(kids));
		}

		void add(String parent, Set<String> kids) {
			Set<String> cur = children.computeIfAbsent(parent, k -> new LinkedHashSet<>());
			for (String k : kids) {
				if (cur.add(k)) changes.add(parent + " +" + k);
			}
		}
	}

	private static Set<String> set(String... s) {
		return new LinkedHashSet<>(java.util.Arrays.asList(s));
	}

	/**
	 * One cycle as buildRelationships publishes it: the site (unless held)
	 * with its switch and, when Protect was read, the NVR; the NVR with its
	 * cameras only when Protect was read; the stitch's HostSystem parents
	 * added to only when the Suite API answered.
	 */
	private static void cycle(Platform p, ProtectEdgeHold hold, boolean protect,
			Set<String> cameras, boolean suiteApi, Set<String> stitched) {
		ProtectEdgeHold.Decision d = hold.cycle(protect, Boolean.TRUE);
		boolean held = d == ProtectEdgeHold.Decision.HOLD;
		if (!held) {
			Set<String> site = set("switch-1");
			if (protect) site.add("nvr");
			p.set("site", site);
		}
		if (protect) p.set("nvr", cameras);
		if (suiteApi) p.add("host-1", stitched);
	}

	private static Platform steady(ProtectEdgeHold hold) {
		Platform p = new Platform();
		cycle(p, hold, true, set("cam-1", "cam-2"), true, set("port-7"));
		p.changes.clear();
		return p;
	}

	static void protectBlipKeepsCameraEdges() {
		ProtectEdgeHold hold = new ProtectEdgeHold();
		Platform p = steady(hold);
		for (int i = 0; i < 3; i++) {
			cycle(p, hold, false, set(), true, set("port-7"));
		}
		T.eq(set("switch-1", "nvr"), p.children.get("site"), "site keeps the NVR");
		T.eq(set("cam-1", "cam-2"), p.children.get("nvr"), "NVR keeps its cameras");
		cycle(p, hold, true, set("cam-1", "cam-2"), true, set("port-7"));
		T.check(p.changes.isEmpty(), "no edge dropped or re-added: " + p.changes);
	}

	static void suiteApiBlipKeepsStitchEdges() {
		ProtectEdgeHold hold = new ProtectEdgeHold();
		Platform p = steady(hold);
		cycle(p, hold, true, set("cam-1", "cam-2"), false, set());
		T.eq(set("port-7"), p.children.get("host-1"), "stitch edge kept");
		cycle(p, hold, true, set("cam-1", "cam-2"), true, set("port-7"));
		T.check(p.changes.isEmpty(), "no stitch edge dropped or re-added: " + p.changes);
	}

	static void recoveredReadUpdatesEdges() {
		ProtectEdgeHold hold = new ProtectEdgeHold();
		Platform p = steady(hold);
		cycle(p, hold, false, set(), true, set("port-7"));
		T.eq(1, hold.unreadCycles(), "one cycle without Protect");
		// Protect is back with a camera replaced: the edges follow it.
		cycle(p, hold, true, set("cam-1", "cam-3"), true, set("port-7"));
		T.eq(0, hold.unreadCycles(), "a good read resets the count");
		T.eq(set("cam-1", "cam-3"), p.children.get("nvr"), "camera edges updated");
		T.eq(set("switch-1", "nvr"), p.children.get("site"), "site published again");
		T.eq(ProtectEdgeHold.Decision.PUBLISH,
				hold.cycle(true, Boolean.TRUE), "a read cycle publishes");
	}

	static void graceExpiresAfterTwelveCycles() {
		ProtectEdgeHold hold = new ProtectEdgeHold();
		for (int i = 1; i <= ProtectEdgeHold.GRACE_CYCLES; i++) {
			T.eq(ProtectEdgeHold.Decision.HOLD, hold.cycle(false, Boolean.TRUE),
					"held at cycle " + i);
		}
		T.eq(ProtectEdgeHold.Decision.EXPIRED, hold.cycle(false, Boolean.TRUE),
				"published again after the grace period");
		T.eq(ProtectEdgeHold.Decision.EXPIRED, hold.cycle(false, Boolean.TRUE),
				"stays published while Protect stays unread");
		T.eq(ProtectEdgeHold.Decision.PUBLISH, hold.cycle(true, null), "read again");
		T.eq(ProtectEdgeHold.Decision.HOLD, hold.cycle(false, Boolean.TRUE),
				"a new blip holds again");
	}

	static void noKnownNvrPublishesAsUsual() {
		ProtectEdgeHold hold = new ProtectEdgeHold();
		for (int i = 0; i < 3; i++) {
			T.eq(ProtectEdgeHold.Decision.PUBLISH, hold.cycle(false, Boolean.FALSE),
					"no NVR known: nothing to keep");
		}
		T.eq(0, hold.unreadCycles(), "no count without a known NVR");
	}

	static void unreadablePlatformListHolds() {
		ProtectEdgeHold hold = new ProtectEdgeHold();
		T.eq(ProtectEdgeHold.Decision.HOLD, hold.cycle(false, null),
				"unknown platform list: hold");
	}
}
