package com.vcfcf.adapters.unifi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Build 18 review R3-N3: a site that leaves a fully read site list is named
 * once, when it first goes missing. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class SiteListWatchTest {

	public static void main(String[] args) {
		firstReadOnlyRecords();
		vanishedSiteNamedOnce();
		returnedSiteNamedAgainIfItLeavesAgain();
		System.out.println("SiteListWatchTest: all assertions passed");
	}

	private static Map<String, String> sites(String... nameDesc) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i < nameDesc.length; i += 2) m.put(nameDesc[i], nameDesc[i + 1]);
		return m;
	}

	static void firstReadOnlyRecords() {
		SiteListWatch w = new SiteListWatch();
		T.check(w.update(sites("default", "Home")).isEmpty(), "first read: nothing");
	}

	static void vanishedSiteNamedOnce() {
		SiteListWatch w = new SiteListWatch();
		w.update(sites("default", "Home", "lab", "Lab"));
		Map<String, String> gone = w.update(sites("default", "Home"));
		T.eq(sites("lab", "Lab"), gone, "lab named when it first goes missing");
		T.check(w.update(sites("default", "Home")).isEmpty(),
				"not named again while it stays out");
	}

	static void returnedSiteNamedAgainIfItLeavesAgain() {
		SiteListWatch w = new SiteListWatch();
		w.update(sites("default", "Home", "lab", "Lab"));
		w.update(sites("default", "Home"));
		T.check(w.update(sites("default", "Home", "lab", "Lab")).isEmpty(),
				"a returning site is not a removal");
		T.eq(sites("lab", "Lab"), w.update(sites("default", "Home")),
				"named again when it leaves again");
	}
}
