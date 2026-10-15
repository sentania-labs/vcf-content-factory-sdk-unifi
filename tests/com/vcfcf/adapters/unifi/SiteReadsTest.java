package com.vcfcf.adapters.unifi;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Build 14 (issue #19): one site's failed stat/device leaves the other
 * sites read; the read fails only when every site fails. Run from the repo
 * root (ci/run_java_tests.sh).
 */
public final class SiteReadsTest {

	public static void main(String[] args) throws Exception {
		oneFailedSiteLeavesTheOthersCollected();
		everySiteFailedFailsTheRead();
		noSitesIsNotAFailure();
		interruptIsNotASiteFailure();
		System.out.println("SiteReadsTest: all assertions passed");
	}

	static void oneFailedSiteLeavesTheOthersCollected() throws Exception {
		List<String> warns = new ArrayList<>();
		List<String> called = new ArrayList<>();
		SiteReads.Result<String> r = SiteReads.readAll("stat/device",
				List.of("default", "branch", "lab"), site -> {
					called.add(site);
					if ("branch".equals(site)) {
						throw new UniFiHttpException("UniFi GET /proxy/network/"
								+ "api/s/branch/stat/device returned HTTP 403",
								403, false);
					}
					return "devices of " + site;
				}, warns::add);
		T.eq(List.of("default", "branch", "lab"), called,
				"every site is asked, including those after the failed one");
		T.eq("devices of default", r.bySite.get("default"), "default read");
		T.eq("devices of lab", r.bySite.get("lab"), "lab read");
		T.eq(null, r.bySite.get("branch"), "branch has no payload");
		T.eq(List.of("branch"), new ArrayList<>(r.failed.keySet()),
				"branch is marked failed");
		T.check(r.failed.get("branch").contains("HTTP 403"),
				"the reason is kept: " + r.failed.get("branch"));
		T.eq(1, warns.size(), "one WARN for the failed site");
		T.check(warns.get(0).contains("site branch"),
				"the WARN names the site: " + warns.get(0));
	}

	static void everySiteFailedFailsTheRead() throws Exception {
		List<String> warns = new ArrayList<>();
		try {
			SiteReads.readAll("stat/device", List.of("a", "b"), site -> {
				throw new UniFiHttpException("UniFi GET returned HTTP 401",
						401, false);
			}, warns::add);
			throw new AssertionError("all sites failed: the read must fail");
		} catch (IOException e) {
			T.check(e.getMessage().contains("every site (2)"),
					"says every site failed: " + e.getMessage());
			T.eq(401, UniFiFailures.authStatus(e),
					"an all-sites 401 is still an authentication refusal");
		}
		T.eq(2, warns.size(), "one WARN per site");

		try {
			SiteReads.readAll("stat/device", List.of("a"), site -> {
				throw new HttpTimeoutException("request timed out");
			}, w -> { });
			throw new AssertionError("the only site failed: the read must fail");
		} catch (IOException e) {
			T.check(e.getCause() instanceof HttpTimeoutException,
					"the first failure is the cause");
		}
	}

	static void noSitesIsNotAFailure() throws Exception {
		SiteReads.Result<String> r = SiteReads.readAll("stat/device",
				Collections.emptyList(), site -> "x", w -> { });
		T.eq(0, r.bySite.size(), "nothing read");
		T.eq(0, r.failed.size(), "nothing failed");
	}

	static void interruptIsNotASiteFailure() throws Exception {
		try {
			SiteReads.readAll("stat/device", List.of("a", "b"), site -> {
				throw new InterruptedException("stop");
			}, w -> { });
			throw new AssertionError("interrupt expected");
		} catch (InterruptedException expected) {
			// expected
		}
	}
}
