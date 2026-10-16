package com.vcfcf.adapters.unifi;

/**
 * Build 15 (issues #23 and #22): the identity guard and the site path
 * encoding. Run from the repo root (ci/run_java_tests.sh).
 */
public final class UniFiIdsTest {

	public static void main(String[] args) {
		macGuard();
		portIdxGuard();
		siteSegment();
		radioGuard();
		discoveredDevices();
		System.out.println("UniFiIdsTest: all assertions passed");
	}

	/** #23: a missing or blank MAC has no identity; a real one is unchanged. */
	static void macGuard() {
		T.eq(null, UniFiIds.mac(null), "missing mac");
		T.eq(null, UniFiIds.mac(""), "empty mac");
		T.eq(null, UniFiIds.mac("   "), "blank mac");
		T.eq("f4:92:bf:00:11:22", UniFiIds.mac("f4:92:bf:00:11:22"),
				"real mac unchanged");
	}

	/** #23: a missing port_idx is skipped, never index 0. */
	static void portIdxGuard() {
		T.eq(null, UniFiIds.portIdx(null), "missing port_idx");
		T.eq(null, UniFiIds.portIdx(""), "empty port_idx");
		T.eq(null, UniFiIds.portIdx("abc"), "non-numeric port_idx");
		T.eq(null, UniFiIds.portIdx("2.5"), "fractional port_idx");
		T.eq(null, UniFiIds.portIdx("true"), "boolean port_idx");
		T.eq(3, UniFiIds.portIdx("3"), "integer port_idx");
		T.eq(3, UniFiIds.portIdx("3.0"), "decimal-form integer port_idx");
		T.eq(0, UniFiIds.portIdx("0"), "a real port 0 is kept");
	}

	/** Peer review 7: a blank radio code has no identity. */
	static void radioGuard() {
		T.eq(null, UniFiIds.radioCode(null), "missing code");
		T.eq(null, UniFiIds.radioCode(""), "empty code");
		T.eq(null, UniFiIds.radioCode(" "), "blank code");
		T.eq("na", UniFiIds.radioCode("na"), "real code unchanged");
	}

	/** Peer review 6: Device Count counts what discovery registers. */
	static void discoveredDevices() {
		for (String t : new String[]{"udm", "ugw", "uxg", "usw", "uap"}) {
			T.check(UniFiIds.discoveredDevice(t, "aa:bb"), t + " with a MAC");
			T.check(!UniFiIds.discoveredDevice(t, ""), t + " without a MAC");
			T.check(!UniFiIds.discoveredDevice(t, null), t + " with no MAC field");
		}
		T.check(!UniFiIds.discoveredDevice("uph", "aa:bb"), "other types are not");
		T.check(!UniFiIds.discoveredDevice("", "aa:bb"), "no type is not");
		T.check(!UniFiIds.discoveredDevice(null, "aa:bb"), "null type is not");
	}

	/** #22: the site is one path segment; ordinary names are unchanged. */
	static void siteSegment() {
		T.eq("default", UniFiIds.pathSegment("default"), "default site");
		T.eq("abc123de", UniFiIds.pathSegment("abc123de"), "generated id");
		T.eq("my-site_1.x", UniFiIds.pathSegment("my-site_1.x"), "safe chars");
		T.eq("a%2F..%2Fb", UniFiIds.pathSegment("a/../b"), "slash encoded");
		T.eq("a%3Fx%3D1%23f", UniFiIds.pathSegment("a?x=1#f"),
				"query and fragment encoded");
		T.eq("two%20words", UniFiIds.pathSegment("two words"),
				"space is %20, not +");
		T.eq("100%25", UniFiIds.pathSegment("100%"), "percent encoded");
		T.eq("caf%C3%A9", UniFiIds.pathSegment("café"), "UTF-8");
	}
}
