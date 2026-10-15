package com.vcfcf.adapters.unifi;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
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
		connectTimeoutSiteStopsTheLoop();
		readTimeoutSiteDoesNotStopTheLoop();
		connectLevelFailuresStopTheLoop();
		unreachableFirstSiteFailsTheReadAfterOneCall();
		allSitesReadTimeoutIsStillDown();
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

	/**
	 * Review NIT-4: a site whose call cannot connect (here a connect
	 * timeout) stops the loop; the sites after it are failed without being
	 * asked. Sites read before it keep their payloads.
	 */
	static void connectTimeoutSiteStopsTheLoop() throws Exception {
		List<String> warns = new ArrayList<>();
		List<String> called = new ArrayList<>();
		SiteReads.Result<String> r = SiteReads.readAll("stat/device",
				List.of("default", "branch", "lab", "edge"), site -> {
					called.add(site);
					if ("branch".equals(site)) {
						throw new HttpConnectTimeoutException("HTTP connect timed out");
					}
					return "devices of " + site;
				}, warns::add);
		T.eq(List.of("default", "branch"), called,
				"no call after the site that could not connect");
		T.eq("devices of default", r.bySite.get("default"), "default read");
		T.eq(List.of("branch", "lab", "edge"),
				new ArrayList<>(r.failed.keySet()),
				"the unreachable site and every later one are failed");
		T.check(r.failed.get("lab").contains("not tried"),
				"a skipped site says it was not tried: " + r.failed.get("lab"));
		T.eq(2, warns.size(), "one WARN for the site, one for the skipped");
		T.check(warns.get(1).contains("[lab, edge]"),
				"the second WARN names the skipped sites: " + warns.get(1));

		// A 403 is not unreachable: the loop goes on (no short-circuit).
		List<String> called403 = new ArrayList<>();
		SiteReads.readAll("stat/device", List.of("a", "b", "c"), site -> {
			called403.add(site);
			if ("a".equals(site)) {
				throw new UniFiHttpException("HTTP 403", 403, false);
			}
			return site;
		}, w -> { });
		T.eq(List.of("a", "b", "c"), called403, "a 403 does not stop the loop");
	}

	/**
	 * Review N1: a request (read) timeout on one slow site fails that site
	 * only; the healthy sites after it are still read (before, they were
	 * ERROR for the cycle).
	 */
	static void readTimeoutSiteDoesNotStopTheLoop() throws Exception {
		for (Exception slow : new Exception[] {
				new HttpTimeoutException("request timed out"),
				new SocketTimeoutException("Read timed out"),
				new IOException("UniFi GET stat/device failed",
						new HttpTimeoutException("request timed out"))}) {
			List<String> warns = new ArrayList<>();
			List<String> called = new ArrayList<>();
			SiteReads.Result<String> r = SiteReads.readAll("stat/device",
					List.of("default", "big", "lab", "edge"), site -> {
						called.add(site);
						if ("big".equals(site)) throw slow;
						return "devices of " + site;
					}, warns::add);
			String k = slow.getClass().getSimpleName();
			T.eq(List.of("default", "big", "lab", "edge"), called,
					k + ": every site is asked after the slow one");
			T.eq(List.of("big"), new ArrayList<>(r.failed.keySet()),
					k + ": only the slow site is failed");
			T.eq("devices of lab", r.bySite.get("lab"), k + ": lab read");
			T.eq("devices of edge", r.bySite.get("edge"), k + ": edge read");
			T.eq(1, warns.size(), k + ": one WARN, for the slow site");
		}
	}

	/**
	 * Review N1: every connect-level failure stops the loop (refused,
	 * connect timeout, unknown host, no route), also when wrapped.
	 */
	static void connectLevelFailuresStopTheLoop() throws Exception {
		for (Exception down : new Exception[] {
				new ConnectException("Connection refused"),
				new HttpConnectTimeoutException("HTTP connect timed out"),
				new UnknownHostException("unifi.example"),
				new NoRouteToHostException("No route to host"),
				new IOException("send failed", new ConnectException("refused"))}) {
			List<String> called = new ArrayList<>();
			SiteReads.Result<String> r = SiteReads.readAll("stat/device",
					List.of("a", "b", "c"), site -> {
						called.add(site);
						if ("b".equals(site)) throw down;
						return site;
					}, w -> { });
			String k = down.getClass().getSimpleName();
			T.eq(List.of("a", "b"), called, k + ": no call after it");
			T.check(r.failed.get("c").contains("not tried"),
					k + ": the later site is not tried: " + r.failed.get("c"));
		}
	}

	/**
	 * Review N1, the whole-controller case: when every site times out on
	 * the read, each site was asked, and the read still fails as
	 * unreachable, so the resources are DOWN, not ERROR.
	 */
	static void allSitesReadTimeoutIsStillDown() throws Exception {
		int[] calls = {0};
		try {
			SiteReads.readAll("stat/device", List.of("a", "b", "c"), site -> {
				calls[0]++;
				throw new HttpTimeoutException("request timed out");
			}, w -> { });
			throw new AssertionError("every site failed: the read must fail");
		} catch (IOException e) {
			T.check(UniFiFailures.isUnreachable(e),
					"an all-sites read timeout is DOWN");
			T.check(!UniFiFailures.isConnectFailure(e),
					"but not a connect-level failure");
		}
		T.eq(3, calls[0], "each site was asked");
	}

	/** Review NIT-4: unreachable on the first site costs one call, not one per site. */
	static void unreachableFirstSiteFailsTheReadAfterOneCall() throws Exception {
		int[] calls = {0};
		List<String> warns = new ArrayList<>();
		try {
			SiteReads.readAll("stat/device", List.of("a", "b", "c"), site -> {
				calls[0]++;
				throw new ConnectException("Connection refused");
			}, warns::add);
			throw new AssertionError("every site failed: the read must fail");
		} catch (IOException e) {
			T.check(e.getMessage().contains("every site (3)"),
					"says every site failed: " + e.getMessage());
			T.check(UniFiFailures.isUnreachable(e),
					"the all-sites failure is still unreachable (DOWN)");
		}
		T.eq(1, calls[0], "one call, not one per site");
		T.eq(2, warns.size(), "one WARN for the site, one for the skipped");
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
