package com.vcfcf.adapters.unifi;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build 14 (issue #19, review W2): the adapter-side site isolation. A
 * device on a failed site is never collected with defaults, a failed site
 * is never emitted as a parent (that would drop its edges), and the NVR is
 * not re-parented under a failed first site. Includes the device that moved
 * between a failed and a readable site (review NIT-3). Run from the repo
 * root (ci/run_java_tests.sh).
 */
public final class SiteIsolationTest {

	public static void main(String[] args) throws Exception {
		allSitesReadMissingDeviceIsAbsent();
		deviceOnFailedSiteIsUnreadable();
		deviceOnReadableSiteCollectsDespiteAFailedSite();
		failedSiteResourcesFail();
		failedSiteIsNeverAParent();
		nvrNotUnderAFailedFirstSite();
		deviceMovedFromFailedToReadableSite();
		deviceMovedFromReadableIntoFailedSite();
		namelessRowsAreNotSites();
		System.out.println("SiteIsolationTest: all assertions passed");
	}

	private static Map<String, String> failed(String... sites) {
		Map<String, String> m = new LinkedHashMap<>();
		for (String s : sites) m.put(s, "HTTP 403");
		return m;
	}

	/** Every site read: a device not in the snapshot is gone (null, no throw). */
	static void allSitesReadMissingDeviceIsAbsent() throws Exception {
		Map<String, String> byMac = new HashMap<>();
		byMac.put("aa", "switch aa");
		T.eq("switch aa", SiteIsolation.findDevice(byMac, failed(), "aa"),
				"found");
		T.eq(null, SiteIsolation.findDevice(byMac, failed(), "bb"),
				"absent is null when nothing failed");
	}

	/** A site failed: a device not found may be there, so it is unreadable. */
	static void deviceOnFailedSiteIsUnreadable() {
		try {
			SiteIsolation.findDevice(Collections.<String, String>emptyMap(),
					failed("branch"), "bb");
			throw new AssertionError("a device not read must not collect");
		} catch (IOException e) {
			T.check(e.getMessage().contains("bb")
					&& e.getMessage().contains("[branch]")
					&& e.getMessage().contains("HTTP 403"),
					"names the device, the site and the reason: "
							+ e.getMessage());
		}
	}

	static void deviceOnReadableSiteCollectsDespiteAFailedSite()
			throws Exception {
		Map<String, String> byMac = new HashMap<>();
		byMac.put("aa", "switch aa");
		T.eq("switch aa", SiteIsolation.findDevice(byMac, failed("branch"),
				"aa"), "a readable site's device collects");
	}

	/** Site and Wireless Summary of a failed site go to ERROR; others pass. */
	static void failedSiteResourcesFail() throws Exception {
		SiteIsolation.requireSiteRead(failed("branch"), "default");
		try {
			SiteIsolation.requireSiteRead(failed("branch"), "branch");
			throw new AssertionError("a failed site must not collect");
		} catch (IOException e) {
			T.check(e.getMessage().contains("site branch"),
					"names the site: " + e.getMessage());
		}
	}

	static void failedSiteIsNeverAParent() {
		Map<String, String> f = failed("branch");
		T.check(SiteIsolation.emitsChildren(f, "default"),
				"a readable site is a parent");
		T.check(!SiteIsolation.emitsChildren(f, "branch"),
				"a failed site is not a parent (keeps its stored edges)");
		T.check(SiteIsolation.emitsChildren(failed(), "branch"),
				"every site is a parent when nothing failed");
	}

	static void nvrNotUnderAFailedFirstSite() {
		List<String> sites = List.of("default", "branch");
		T.eq("default", SiteIsolation.nvrParentSite(sites, failed()),
				"NVR under the first site");
		T.eq("default", SiteIsolation.nvrParentSite(sites, failed("branch")),
				"a failed second site does not matter");
		T.eq(null, SiteIsolation.nvrParentSite(sites, failed("default")),
				"no NVR parent when the first site failed");
		T.eq(null, SiteIsolation.nvrParentSite(List.<String>of(), failed()),
				"no NVR parent without sites");
	}

	/**
	 * NIT-3: moved from failed site F to readable site R. The device is in
	 * the snapshot (from R), so it collects; R is emitted with it, F is not
	 * emitted, so F's stored edge stays: briefly two site parents.
	 */
	static void deviceMovedFromFailedToReadableSite() throws Exception {
		Map<String, String> byMac = new HashMap<>();
		byMac.put("mm", "switch mm on R");
		Map<String, String> f = failed("F");
		T.eq("switch mm on R", SiteIsolation.findDevice(byMac, f, "mm"),
				"collects from its new site");
		T.check(SiteIsolation.emitsChildren(f, "R"), "R emits it");
		T.check(!SiteIsolation.emitsChildren(f, "F"),
				"F is not emitted, so its old edge stays (two parents)");
	}

	/**
	 * NIT-3: moved from readable site R into failed site F. Not in the
	 * snapshot, so its collect is ERROR (never defaults); R is emitted
	 * without it and F is not emitted: briefly no site parent.
	 */
	static void deviceMovedFromReadableIntoFailedSite() {
		Map<String, String> f = failed("F");
		try {
			SiteIsolation.findDevice(Collections.<String, String>emptyMap(), f,
					"mm");
			throw new AssertionError("must not collect defaults");
		} catch (IOException expected) {
			// expected: ERROR
		}
		T.check(SiteIsolation.emitsChildren(f, "R"),
				"R is emitted without it (its old edge drops)");
		T.check(!SiteIsolation.emitsChildren(f, "F"),
				"F is not emitted yet (no new edge until F reads)");
	}

	/**
	 * Review R5-N1: a nameless /self/sites row is not a stat/device target
	 * and not the NVR's parent; the first named site is, as before.
	 */
	static void namelessRowsAreNotSites() {
		T.check(!SiteIsolation.hasName(null), "null name");
		T.check(!SiteIsolation.hasName(""), "empty name");
		T.check(!SiteIsolation.hasName("  "), "blank name");
		T.check(SiteIsolation.hasName("default"), "named");
		List<String> rows = new java.util.ArrayList<>();
		rows.add(null);
		rows.add("default");
		rows.add(" ");
		rows.add("branch");
		T.eq(List.of("default", "branch"), SiteIsolation.named(rows),
				"nameless rows dropped, order kept");
		T.eq(List.<String>of(), SiteIsolation.named(java.util.Collections
				.singletonList((String) null)), "only a nameless row");
		T.eq(null, SiteIsolation.nvrParentSite(SiteIsolation.named(
				java.util.Collections.singletonList((String) null)), failed()),
				"only a nameless row: no NVR parent");
		T.eq("default", SiteIsolation.nvrParentSite(
				SiteIsolation.named(List.of("default", "branch")), failed()),
				"all named: first site, unchanged");
	}
}
