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
		refusedSiteCoolsDownWhileOthersCollect(403);
		refusedSiteCoolsDownWhileOthersCollect(429);
		serverErrorSiteDoesNotCoolDown();
		everySiteRefusedStillFailsTheBuild();
		instanceEditStartsWithNoCooldown();
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

	// --- Codex P1: per-site cool-down ---

	private static final long MIN = 60_000L;
	private static final long AUTH = 15 * MIN;
	private static final long RETRY = MIN;

	/** Fake time. */
	static final class Clock implements SnapshotCache.Clock {
		long now = 1_000_000L;

		@Override
		public long millis() {
			return now;
		}
	}

	/** Site A answers; site B answers with {@code status} while it is set. */
	static final class TwoSites implements SiteReads.Fetch<String> {
		final List<String> called = new ArrayList<>();
		int bStatus;

		@Override
		public String fetch(String site) throws Exception {
			called.add(site);
			if ("B".equals(site) && bStatus != 0) {
				throw new UniFiHttpException("UniFi GET /proxy/network/api/s/B/"
						+ "stat/device returned HTTP " + bStatus, bStatus, false);
			}
			return "devices of " + site;
		}

		int callsFor(String site) {
			return Collections.frequency(called, site);
		}
	}

	private static SiteReads.Result<String> build(TwoSites f,
			SiteReads.Cooldowns cd, List<String> warns, List<String> infos)
			throws Exception {
		return SiteReads.readAll("stat/device", List.of("A", "B"), f,
				warns::add, cd, infos::add);
	}

	/**
	 * A refusal on site B while site A is read cools B down for 15 minutes:
	 * later builds (5-minute cycles) do not ask B, keep it failed with a
	 * cooling-down reason, and A still collects. Once the window has passed
	 * B is asked again, with one INFO.
	 */
	static void refusedSiteCoolsDownWhileOthersCollect(int status)
			throws Exception {
		Clock clock = new Clock();
		SiteReads.Cooldowns cd = new SiteReads.Cooldowns(clock, AUTH, RETRY);
		TwoSites f = new TwoSites();
		f.bStatus = status;
		List<String> warns = new ArrayList<>();
		List<String> infos = new ArrayList<>();

		SiteReads.Result<String> r = build(f, cd, warns, infos);
		T.eq(1, f.callsFor("B"), "HTTP " + status + ": B asked once");
		T.eq("devices of A", r.bySite.get("A"), "A collected");
		T.check(r.failed.containsKey("B"), "B failed");
		T.eq(2, warns.size(), "the site WARN and the cool-down WARN");
		T.check(warns.get(1).contains("site B")
				&& warns.get(1).contains("cooling down after HTTP " + status)
				&& warns.get(1).contains("900 s"),
				"the cool-down WARN says why and how long: " + warns.get(1));

		for (int k = 1; k <= 2; k++) {
			clock.now += 5 * MIN;
			r = build(f, cd, warns, infos);
			T.eq(1, f.callsFor("B"), "HTTP " + status + ": B not asked in "
					+ "cycle " + (k + 1) + " inside the window");
			T.eq("devices of A", r.bySite.get("A"), "A still collected");
			T.check(r.failed.get("B").contains("cooling down after HTTP "
					+ status), "B stays failed, cooling down: "
							+ r.failed.get("B"));
		}
		T.eq(2, warns.size(), "no further WARN while cooling down");
		T.eq(0, infos.size(), "no INFO before the window passes");

		f.bStatus = 0; // the permission was fixed
		clock.now += 5 * MIN + 1;
		r = build(f, cd, warns, infos);
		T.eq(2, f.callsFor("B"), "B asked again once the window has passed");
		T.eq("devices of B", r.bySite.get("B"), "B collected again");
		T.check(r.failed.isEmpty(), "nothing failed");
		T.eq(1, infos.size(), "one INFO when the cool-down clears");
		T.check(infos.get(0).contains("site B")
				&& infos.get(0).contains("HTTP " + status),
				"the INFO names the site: " + infos.get(0));
		T.eq(4, f.callsFor("A"), "A asked every build");
	}

	/** A 5xx on site B is asked again on the next cycle, no cool-down WARN. */
	static void serverErrorSiteDoesNotCoolDown() throws Exception {
		Clock clock = new Clock();
		SiteReads.Cooldowns cd = new SiteReads.Cooldowns(clock, AUTH, RETRY);
		TwoSites f = new TwoSites();
		f.bStatus = 502;
		List<String> warns = new ArrayList<>();
		List<String> infos = new ArrayList<>();
		build(f, cd, warns, infos);
		T.eq(1, warns.size(), "only the site WARN for a 502");
		for (int k = 1; k <= 3; k++) {
			clock.now += 5 * MIN;
			build(f, cd, warns, infos);
			T.eq(k + 1, f.callsFor("B"), "a 502 site is asked every cycle");
		}
		T.eq(4, warns.size(), "one site WARN per cycle, no cool-down WARN");
		T.eq(0, infos.size(), "no cool-down INFO for a 5xx");
	}

	/**
	 * A refusal on every site still fails the read as a whole (into the
	 * snapshot back-off), and so does a later read where every site is
	 * cooling down: never asked, classified as the original refusal.
	 */
	static void everySiteRefusedStillFailsTheBuild() throws Exception {
		Clock clock = new Clock();
		SiteReads.Cooldowns cd = new SiteReads.Cooldowns(clock, AUTH, RETRY);
		int[] calls = {0};
		SiteReads.Fetch<String> refused = site -> {
			calls[0]++;
			throw new UniFiHttpException("UniFi GET returned HTTP 403", 403,
					false);
		};
		try {
			SiteReads.readAll("stat/device", List.of("A", "B"), refused,
					w -> { }, cd, i -> { });
			throw new AssertionError("every site refused: the read must fail");
		} catch (IOException e) {
			T.eq(403, UniFiFailures.authStatus(e), "still an auth refusal");
		}
		T.eq(2, calls[0], "both sites asked");
		clock.now += 5 * MIN;
		try {
			SiteReads.readAll("stat/device", List.of("A", "B"), refused,
					w -> { }, cd, i -> { });
			throw new AssertionError("every site cooling down: must fail");
		} catch (IOException e) {
			T.eq(403, UniFiFailures.authStatus(e),
					"classified as the original refusal: " + e.getMessage());
			T.check(e.getMessage().contains("every site (2)"),
					"says every site failed: " + e.getMessage());
		}
		T.eq(2, calls[0], "no site asked while every site cools down");
	}

	/** A new configuration gets new cool-downs: B is asked at once. */
	static void instanceEditStartsWithNoCooldown() throws Exception {
		Clock clock = new Clock();
		TwoSites f = new TwoSites();
		f.bStatus = 403;
		List<String> warns = new ArrayList<>();
		build(f, new SiteReads.Cooldowns(clock, AUTH, RETRY), warns,
				new ArrayList<>());
		f.bStatus = 0;
		SiteReads.Result<String> r = build(f,
				new SiteReads.Cooldowns(clock, AUTH, RETRY), warns,
				new ArrayList<>());
		T.eq(2, f.callsFor("B"), "the edited instance asks B at once");
		T.eq("devices of B", r.bySite.get("B"), "B collected");
	}
}
