package com.vcfcf.adapters.unifi;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Issue #20: what the adapter declares read in full each cycle
 * ({@link ReadKinds}), which is all the framework counts misses for. The
 * counting and the 12-cycle mark are the framework's (buildkit 1.0.22,
 * VanishedResourceTest); {@link #completeReadCountsMisses} drives a model of
 * that rule over the declared kinds, the same order of operations as
 * {@code VcfCfAdapter.markVanished}, so the pack-side cases read end to end.
 * Run from the repo root (ci/run_java_tests.sh).
 */
public final class ReadKindsTest {

	static final String CONNECTED = "1";

	public static void main(String[] args) {
		completeReadNamesEveryKind();
		completeReadCountsMisses();
		failedSiteMarksNothing();
		siteWithNoDeviceListIsNotRead();
		protectOutageMarksNoCamera();
		protectWithoutNvrMacKeepsCameras();
		cameraListGapsAreNotRead();
		disconnectedSwitchKeepsItsPorts();
		portWithNoIndexLeavesPortsOut();
		accessPointWithoutRadioTableKeepsRadios();
		gatewayNotInformingKeepsWans();
		deviceWithNoMacLeavesDevicesOut();
		noSiteListNamesNothing();
		emptySiteListNamesNothing();
		siteRcNotOkIsNotRead();
		failedSiteWithAListIsNotRead();
		emptyCameraListWithNvrIsNotRead();
		emptyPortTableIsNotRead();
		emptyRadioTableIsNotRead();
		deviceWithNoTypeLeavesDevicesOut();
		zeroDevicesNamesNoDeviceKind();
		oneEmptySiteAmongOthersIsRead();
		gatewayWithoutWan1KeepsWans();
		siteListRcNotOkNamesNothing();
		siteWithoutNameLeavesSiteOut();
		leftOutMatchesKinds();
		worldIsExcluded();
		informingStates();
		System.out.println("ReadKindsTest: all assertions passed");
	}

	/** A healthy devel-shaped cycle: one site, every table, Protect up. */
	private static ReadKinds complete() {
		ReadKinds r = new ReadKinds();
		r.sitesRead(1, true);
		r.site(false, true, true, 3);
		r.gateway(true, true);
		r.switchPorts(true, true, 8, 0);
		r.accessPointRadios(true, true, 2, 0);
		r.protect(true, true, 3, 0);
		return r;
	}

	private static Set<String> set(String... kinds) {
		return new LinkedHashSet<>(Arrays.asList(kinds));
	}

	private static final Set<String> NETWORK = set(ReadKinds.GATEWAY,
			ReadKinds.WAN, ReadKinds.SWITCH, ReadKinds.PORT,
			ReadKinds.ACCESS_POINT, ReadKinds.RADIO);

	static void completeReadNamesEveryKind() {
		T.eq(set(ReadKinds.SITE, ReadKinds.AGGREGATE, ReadKinds.GATEWAY,
				ReadKinds.SWITCH, ReadKinds.ACCESS_POINT, ReadKinds.WAN,
				ReadKinds.PORT, ReadKinds.RADIO, ReadKinds.NVR,
				ReadKinds.CAMERA), complete().kinds(),
				"a complete read names every kind but the world");
	}

	/**
	 * The framework's rule, modelled: per cycle, a known object of a
	 * declared, non-excluded kind that the enumeration did not deliver
	 * takes a miss; a delivered one is reset; at the threshold it is marked.
	 * A kind not declared this cycle neither counts nor resets.
	 */
	private static final class Model {
		final Map<String, Integer> misses = new HashMap<>();
		final Set<String> marked = new LinkedHashSet<>();

		void cycle(Map<String, String> known, Set<String> delivered,
				Set<String> declared) {
			Set<String> read = new LinkedHashSet<>();
			for (String k : declared) read.add(k.toLowerCase());
			for (Map.Entry<String, String> e : known.entrySet()) {
				String key = e.getKey();
				String kind = e.getValue().toLowerCase();
				if (!read.contains(kind)) continue;
				if (ReadKinds.EXCLUDED.stream()
						.anyMatch(x -> x.equalsIgnoreCase(kind))) continue;
				if (delivered.contains(key)) {
					misses.remove(key);
					continue;
				}
				if (marked.contains(key)) continue;
				int n = misses.merge(key, 1, Integer::sum);
				if (n >= 12) marked.add(key);
			}
		}
	}

	/** The platform's objects on devel, legacy orphans included. */
	private static Map<String, String> devel() {
		Map<String, String> known = new HashMap<>();
		known.put("world", ReadKinds.WORLD);
		known.put("site:default", ReadKinds.SITE);
		known.put("gw:live", ReadKinds.GATEWAY);
		known.put("gw:old", ReadKinds.GATEWAY);
		known.put("sw:live", ReadKinds.SWITCH);
		known.put("port:live_3", ReadKinds.PORT);
		known.put("port:live_0", ReadKinds.PORT);
		known.put("cam:live", ReadKinds.CAMERA);
		known.put("cam:backyard", ReadKinds.CAMERA);
		return known;
	}

	private static final Set<String> DELIVERED = set("world", "site:default",
			"gw:live", "sw:live", "port:live_3", "cam:live");

	/** A complete read: every orphan takes a miss and is marked at 12. */
	static void completeReadCountsMisses() {
		Model m = new Model();
		for (int i = 1; i <= 11; i++) {
			m.cycle(devel(), DELIVERED, complete().kinds());
		}
		T.eq(11, m.misses.get("gw:old"), "old gateway: 11 misses");
		T.eq(11, m.misses.get("port:live_0"), "<mac>_0 port: 11 misses");
		T.eq(11, m.misses.get("cam:backyard"), "removed camera: 11 misses");
		T.check(m.marked.isEmpty(), "nothing marked before 12");
		m.cycle(devel(), DELIVERED, complete().kinds());
		T.eq(set("gw:old", "port:live_0", "cam:backyard"), m.marked,
				"the three orphans are marked at the 12th complete cycle");
		T.check(!m.misses.containsKey("gw:live")
				&& !m.misses.containsKey("world"), "delivered objects take no miss");
	}

	/**
	 * Issue #20, review B1 on the framework: one site's stat/device failed,
	 * so its devices are not delivered while the same kinds arrive from the
	 * other site. No device kind is declared; nothing is counted or marked.
	 */
	static void failedSiteMarksNothing() {
		ReadKinds r = new ReadKinds();
		r.sitesRead(1, true);
		r.site(false, true, true, 3);  // site A read
		r.site(true, false, true, 0);  // site B failed (or cooling down)
		r.gateway(true, true);
		r.switchPorts(true, true, 8, 0);
		r.accessPointRadios(true, true, 2, 0);
		r.protect(true, true, 3, 0);
		Set<String> k = r.kinds();
		for (String kind : NETWORK) {
			T.check(!k.contains(kind), kind + " not read with a failed site");
		}
		T.check(k.contains(ReadKinds.SITE) && k.contains(ReadKinds.AGGREGATE),
				"sites come from /self/sites, still read");
		T.check(k.contains(ReadKinds.CAMERA), "Protect is independent of sites");

		Map<String, String> known = new HashMap<>();
		known.put("sw:siteA", ReadKinds.SWITCH);
		known.put("sw:siteB", ReadKinds.SWITCH);
		known.put("port:siteB_1", ReadKinds.PORT);
		Model m = new Model();
		for (int i = 0; i < 30; i++) m.cycle(known, set("sw:siteA"), k);
		T.check(m.misses.isEmpty() && m.marked.isEmpty(),
				"30 cycles with site B unread: no miss, no mark");
	}

	/** A 200 whose data is not a device list is as unread as a failure. */
	static void siteWithNoDeviceListIsNotRead() {
		ReadKinds r = new ReadKinds();
		r.sitesRead(1, true);
		r.site(false, false, true, 3);
		T.eq(set(ReadKinds.SITE, ReadKinds.AGGREGATE), r.kinds(),
				"no device list: only site kinds");
	}

	/** Protect bootstrap failed: protect() is never called. */
	static void protectOutageMarksNoCamera() {
		ReadKinds r = new ReadKinds();
		r.sitesRead(1, true);
		r.site(false, true, true, 3);
		r.gateway(true, true);
		r.switchPorts(true, true, 8, 0);
		r.accessPointRadios(true, true, 2, 0);
		Set<String> k = r.kinds();
		T.check(!k.contains(ReadKinds.CAMERA) && !k.contains(ReadKinds.NVR),
				"Protect outage: no camera or NVR kind");
		T.check(k.containsAll(NETWORK), "the network read still counts");

		Map<String, String> known = new HashMap<>();
		known.put("nvr", ReadKinds.NVR);
		known.put("cam:1", ReadKinds.CAMERA);
		known.put("cam:2", ReadKinds.CAMERA);
		Model m = new Model();
		for (int i = 0; i < 30; i++) m.cycle(known, set(), k);
		T.check(m.misses.isEmpty() && m.marked.isEmpty(),
				"30 cycles of Protect outage: no camera counted or marked");
	}

	static void protectWithoutNvrMacKeepsCameras() {
		ReadKinds r = new ReadKinds();
		r.sitesRead(1, true);
		r.protect(false, true, 3, 0);
		Set<String> k = r.kinds();
		T.check(!k.contains(ReadKinds.NVR), "NVR with no MAC: NVR kind not read");
		T.check(k.contains(ReadKinds.CAMERA), "its cameras are still read");
	}

	static void cameraListGapsAreNotRead() {
		ReadKinds a = new ReadKinds();
		a.sitesRead(1, true);
		a.protect(true, false, 3, 0);
		T.check(!a.kinds().contains(ReadKinds.CAMERA), "cameras not a list");
		T.check(a.kinds().contains(ReadKinds.NVR), "NVR still read");
		ReadKinds b = new ReadKinds();
		b.sitesRead(1, true);
		b.protect(true, true, 3, 1);
		T.check(!b.kinds().contains(ReadKinds.CAMERA), "a camera with no MAC");
	}

	static void disconnectedSwitchKeepsItsPorts() {
		ReadKinds r = complete();
		r.switchPorts(false, true, 8, 0); // a second switch, disconnected
		Set<String> k = r.kinds();
		T.check(!k.contains(ReadKinds.PORT), "a disconnected switch: no port kind");
		T.check(k.contains(ReadKinds.SWITCH), "the switch kind is still read");
		ReadKinds n = complete();
		n.switchPorts(true, false, 8, 0);
		T.check(!n.kinds().contains(ReadKinds.PORT), "no port_table list");
	}

	static void portWithNoIndexLeavesPortsOut() {
		ReadKinds r = complete();
		r.switchPorts(true, true, 8, 1);
		T.check(!r.kinds().contains(ReadKinds.PORT), "a port with no port_idx");
	}

	static void accessPointWithoutRadioTableKeepsRadios() {
		ReadKinds a = complete();
		a.accessPointRadios(true, false, 2, 0);
		T.check(!a.kinds().contains(ReadKinds.RADIO), "no radio_table_stats");
		ReadKinds b = complete();
		b.accessPointRadios(false, true, 2, 0);
		T.check(!b.kinds().contains(ReadKinds.RADIO), "an AP not informing");
		ReadKinds c = complete();
		c.accessPointRadios(true, true, 2, 1);
		T.check(!c.kinds().contains(ReadKinds.RADIO), "a radio with no code");
		T.check(c.kinds().contains(ReadKinds.ACCESS_POINT), "APs still read");
	}

	static void gatewayNotInformingKeepsWans() {
		ReadKinds r = complete();
		r.gateway(false, true);
		T.check(!r.kinds().contains(ReadKinds.WAN), "gateway down: no WAN kind");
		T.check(r.kinds().contains(ReadKinds.GATEWAY), "gateway kind still read");
	}

	static void deviceWithNoMacLeavesDevicesOut() {
		ReadKinds r = complete();
		r.deviceWithoutIdentity();
		Set<String> k = r.kinds();
		for (String kind : NETWORK) {
			T.check(!k.contains(kind), kind + " not read with a MAC-less device");
		}
	}

	static void noSiteListNamesNothing() {
		ReadKinds r = new ReadKinds();
		r.protect(true, true, 3, 0);
		T.check(r.kinds().isEmpty(), "no site list, nothing read");
	}

	static void worldIsExcluded() {
		T.check(ReadKinds.EXCLUDED.contains(ReadKinds.WORLD), "world excluded");
		T.check(!complete().kinds().contains(ReadKinds.WORLD), "world never read");
	}

	static void informingStates() {
		T.check(ReadKinds.informing(CONNECTED), "1 informs");
		T.check(ReadKinds.informing("4") && ReadKinds.informing("5")
				&& ReadKinds.informing("7"), "4, 5, 7 inform");
		T.check(!ReadKinds.informing("0"), "0 disconnected");
		T.check(!ReadKinds.informing("6"), "6 heartbeat missed");
		T.check(!ReadKinds.informing(null), "absent state is not trusted");
		T.check(!ReadKinds.informing("x"), "a non-number is not trusted");
	}

	/**
	 * Review R2-B1: {@code /self/sites} answered {@code {"data":[]}}. World is
	 * still delivered, so the framework's "delivered nothing" guard does not
	 * fire; the adapter must declare nothing, or twelve such cycles mark the
	 * whole inventory.
	 */
	static void emptySiteListNamesNothing() {
		ReadKinds r = new ReadKinds();
		r.sitesRead(0, true);
		r.protect(true, true, 3, 0);
		T.check(r.kinds().isEmpty(), "an empty site list declares nothing");

		Map<String, String> known = devel();
		Model m = new Model();
		for (int i = 0; i < 30; i++) m.cycle(known, set("world"), r.kinds());
		T.check(m.misses.isEmpty() && m.marked.isEmpty(),
				"30 cycles of an empty site list: no miss, no mark");
	}

	/** Review R2-W1(a): a 200 whose meta.rc is not ok is not a read. */
	static void siteRcNotOkIsNotRead() {
		ReadKinds r = new ReadKinds();
		r.sitesRead(1, true);
		r.site(false, true, ReadKinds.rcOk("error"), 3);
		r.gateway(true, true);
		r.switchPorts(true, true, 8, 0);
		r.accessPointRadios(true, true, 2, 0);
		Set<String> k = r.kinds();
		for (String kind : NETWORK) {
			T.check(!k.contains(kind), kind + " not read with meta.rc error");
		}
		T.check(ReadKinds.rcOk("ok") && ReadKinds.rcOk("OK"), "ok is ok");
		T.check(!ReadKinds.rcOk(""), "no meta.rc is not ok");
		T.check(!ReadKinds.rcOk(null), "null meta.rc is not ok");
	}

	/**
	 * Review R2-N1: a site marked failed that still has a device list (not
	 * reachable today, SiteReads keeps them disjoint) is not read.
	 */
	static void failedSiteWithAListIsNotRead() {
		ReadKinds r = new ReadKinds();
		r.sitesRead(1, true);
		r.site(true, true, true, 3);
		Set<String> k = r.kinds();
		for (String kind : NETWORK) {
			T.check(!k.contains(kind), kind + " not read: failed site with a list");
		}
	}

	/** Review R2-W1(b): cameras: [] with an NVR present is not proof. */
	static void emptyCameraListWithNvrIsNotRead() {
		ReadKinds r = complete();
		r.protect(true, true, 0, 0);
		Set<String> k = r.kinds();
		T.check(!k.contains(ReadKinds.CAMERA), "cameras: [] declares no camera");
		T.check(k.contains(ReadKinds.NVR), "the NVR is still read");
	}

	/** Review R2-W1(c): port_table: [] in any state is incomplete. */
	static void emptyPortTableIsNotRead() {
		for (boolean informing : new boolean[] {true, false}) {
			ReadKinds r = complete();
			r.switchPorts(informing, true, 0, 0);
			T.check(!r.kinds().contains(ReadKinds.PORT),
					"port_table: [] (informing=" + informing + ")");
			T.check(r.kinds().contains(ReadKinds.SWITCH), "switches still read");
		}
	}

	/** Review R2-W1(d): radio_table_stats: [] on an AP is incomplete. */
	static void emptyRadioTableIsNotRead() {
		for (boolean informing : new boolean[] {true, false}) {
			ReadKinds r = complete();
			r.accessPointRadios(informing, true, 0, 0);
			T.check(!r.kinds().contains(ReadKinds.RADIO),
					"radio_table_stats: [] (informing=" + informing + ")");
			T.check(r.kinds().contains(ReadKinds.ACCESS_POINT), "APs still read");
		}
	}

	/**
	 * Review R2-N2: the adapter calls deviceWithoutIdentity for a record with
	 * no type (MAC or not), so the result matches a MAC-less device.
	 */
	static void deviceWithNoTypeLeavesDevicesOut() {
		ReadKinds r = complete();
		r.deviceWithoutIdentity();
		Set<String> k = r.kinds();
		for (String kind : NETWORK) {
			T.check(!k.contains(kind), kind + " not read with a typeless device");
		}
		T.check(k.contains(ReadKinds.SITE) && k.contains(ReadKinds.CAMERA),
				"site and Protect kinds unaffected");
	}

	/**
	 * Review R3-B1: every site answered stat/device with meta.rc ok and
	 * {@code data: []}. No device was walked, so no table flag was set; the
	 * device count alone keeps all six device kinds out, for 30 cycles.
	 */
	static void zeroDevicesNamesNoDeviceKind() {
		for (int siteCount : new int[] {1, 2}) {
			ReadKinds r = new ReadKinds();
			r.sitesRead(siteCount, true);
			for (int i = 0; i < siteCount; i++) r.site(false, true, true, 0);
			r.protect(true, true, 3, 0);
			Set<String> k = r.kinds();
			T.eq(set(ReadKinds.SITE, ReadKinds.AGGREGATE, ReadKinds.NVR,
					ReadKinds.CAMERA), k,
					siteCount + " site(s), zero devices: no device kind");
			T.check(r.leftOut().containsKey("devices-none"),
					"zero devices is given as the reason");

			Map<String, String> known = devel();
			known.put("ap:live", ReadKinds.ACCESS_POINT);
			known.put("radio:live_ng", ReadKinds.RADIO);
			known.put("wan:live_wan1", ReadKinds.WAN);
			Model m = new Model();
			for (int i = 0; i < 30; i++) {
				m.cycle(known, set("world", "site:default", "cam:live"), k);
			}
			for (String key : new String[] {"gw:live", "gw:old", "sw:live",
					"port:live_3", "ap:live", "radio:live_ng", "wan:live_wan1"}) {
				T.check(!m.misses.containsKey(key) && !m.marked.contains(key),
						key + ": no miss, no mark over 30 empty device cycles");
			}
		}
	}

	/**
	 * Review R3-B1, accepted residual: one empty site among sites that
	 * listed devices is a real inventory (a site can be empty).
	 */
	static void oneEmptySiteAmongOthersIsRead() {
		ReadKinds r = new ReadKinds();
		r.sitesRead(2, true);
		r.site(false, true, true, 4);
		r.site(false, true, true, 0);
		r.gateway(true, true);
		r.switchPorts(true, true, 8, 0);
		r.accessPointRadios(true, true, 2, 0);
		T.check(r.kinds().containsAll(NETWORK), "devices listed elsewhere: read");
	}

	/** Review R3-W1: an informing gateway with no wan1 keeps its WANs. */
	static void gatewayWithoutWan1KeepsWans() {
		ReadKinds r = complete();
		r.gateway(true, false);
		T.check(!r.kinds().contains(ReadKinds.WAN), "no wan1: no WAN kind");
		T.check(r.kinds().contains(ReadKinds.GATEWAY), "gateway kind still read");
		T.check(r.leftOut().containsKey("wan"), "wan1 gap is given as a reason");
		T.check(complete().kinds().contains(ReadKinds.WAN),
				"wan1 present (wan2 not reported here): WAN read");
	}

	/** Review R3-N1: a site list whose meta.rc is not ok declares nothing. */
	static void siteListRcNotOkNamesNothing() {
		ReadKinds r = new ReadKinds();
		r.sitesRead(1, ReadKinds.rcOk("error"));
		r.site(false, true, true, 3);
		r.protect(true, true, 3, 0);
		T.check(r.kinds().isEmpty(), "site list rc error: nothing read");
		T.check(!r.siteListRead(), "site list not read");
		T.check(r.leftOut().containsKey("sites"), "reason given");
	}

	/**
	 * Review R3-N2: a complete read gives no reason; each gap gives its own
	 * key, and every kind left out has a reason.
	 */
	static void leftOutMatchesKinds() {
		T.check(complete().leftOut().isEmpty(), "complete read: no reason");
		ReadKinds a = complete();
		a.switchPorts(false, true, 8, 0);
		T.eq(set("port"), a.leftOut().keySet(), "port gap only");
		ReadKinds b = complete();
		b.site(true, false, true, 0);
		T.eq(set("devices-site"), b.leftOut().keySet(), "unread site");
		ReadKinds c = complete();
		c.deviceWithoutIdentity();
		T.eq(set("devices-identity"), c.leftOut().keySet(), "identity");
		ReadKinds d = new ReadKinds();
		d.sitesRead(1, true);
		d.site(false, true, true, 3);
		d.gateway(true, true);
		d.switchPorts(true, true, 8, 0);
		d.accessPointRadios(true, true, 2, 0);
		T.eq(set("protect"), d.leftOut().keySet(), "no Protect");
		ReadKinds e = complete();
		e.protect(false, true, 0, 0);
		T.eq(set("nvr", "camera"), e.leftOut().keySet(), "NVR and camera gaps");
		ReadKinds f = new ReadKinds();
		f.sitesRead(0, true);
		T.eq(set("sites"), f.leftOut().keySet(), "empty site list");
		ReadKinds g = complete();
		g.siteWithoutIdentity();
		T.eq(set("site-identity"), g.leftOut().keySet(), "nameless site");
	}

	/**
	 * Review R4-N1: a /self/sites row with no name is skipped, and leaves
	 * Site, Wireless Summary and the device kinds out (its devices were not
	 * read); Protect is unaffected. Alongside a fully read named site, too.
	 */
	static void siteWithoutNameLeavesSiteOut() {
		ReadKinds r = complete();
		T.check(!r.siteWithoutIdentitySeen(), "R5-N2: no nameless row yet");
		r.siteWithoutIdentity();
		T.check(r.siteWithoutIdentitySeen(), "R5-N2: nameless row seen, no site watch update");
		T.check(r.siteListRead(), "the list itself was read");
		T.eq(set(ReadKinds.NVR, ReadKinds.CAMERA), r.kinds(),
				"nameless site: only Protect kinds read");
		ReadKinds two = new ReadKinds();
		two.sitesRead(2, true);
		two.site(false, true, true, 3);
		two.siteWithoutIdentity();
		Set<String> k = two.kinds();
		T.check(!k.contains(ReadKinds.SITE), "Site left out");
		T.check(!k.contains(ReadKinds.AGGREGATE), "Wireless Summary left out");
		for (String kind : NETWORK) {
			T.check(!k.contains(kind), kind + " left out with a nameless site");
		}
	}
}
