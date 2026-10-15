package com.vcfcf.adapters.unifi;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Build 14 (issue #19): reads one payload per site, isolating each site's
 * failure. No VCF Ops SDK and no framework-jar dependency (generic over the
 * payload type), so the plain-main tests drive it directly.
 *
 * <p>Before build 14 a failed {@code stat/device} for any one site (a
 * permission on that site, a timeout, a transient 5xx) failed the whole
 * snapshot, and every resource on every site went to ERROR. Now that site
 * is recorded as failed, with one WARN, and the other sites are read. The
 * read fails as a whole only when every site fails; its cause is the first
 * site's failure, so an all-sites 401 or timeout is classified the same way
 * as a single failed call.
 *
 * <p>Review NIT-4: a site whose call cannot connect to the controller
 * ({@link UniFiFailures#isConnectFailure}: connection refused, connect
 * timeout, unknown host, no route) stops the loop. The sites after it are
 * recorded as failed without being asked, with one WARN naming them,
 * because each would cost the same timeout and retries again (about 90 s
 * per site at the client's 30 s timeout and three attempts).
 *
 * <p>Review N1: a request (read) timeout does not stop the loop. The
 * controller accepted the connection and was slow to answer that site
 * (a large site, for example), so that site is failed and the next site is
 * still asked; before, one slow site made every healthy site after it
 * ERROR for the cycle. The cost is that a controller that accepts
 * connections but answers nothing costs one timeout per site. When every
 * site fails, the read's cause is the first failure, so an all-sites read
 * timeout is still DOWN ({@link UniFiFailures#isUnreachable}).
 *
 * <p>Codex P1: a site's refusal cools that site down. A site whose call
 * ends in an authentication-class refusal (401, 403 or 429, see
 * {@link UniFiFailures#authStatus}) while another site is read used to
 * leave a partial snapshot, so {@link SnapshotCache} never saw the refusal
 * and applied no back-off: every later cycle asked the refused site again,
 * one more refused request (after the framework's own re-login) per
 * refused site per cycle, which defeated issue #11. Now the site is put in
 * a {@link Cooldowns} window held in the per-configuration holder, the same
 * windows as the snapshot back-off (15 minutes after 401, 403 or 429, 60 s
 * after any other failure), and later builds skip it, keeping it marked
 * failed (its objects stay ERROR, relationships kept), until the window
 * has passed. One WARN when an authentication-class cool-down starts, one
 * INFO when it has passed and the site is asked again; the shorter window
 * after any other failure is silent beyond the per-site WARN (at the
 * default 5-minute interval it has passed by the next cycle, so a 5xx site
 * is asked again every cycle as before). When every site fails, cooled-down
 * sites included, the read fails as a whole as before, into the snapshot
 * back-off; when no site was asked at all, its cause is the first cooled
 * site's original failure, so the classification is the same.
 */
final class SiteReads {

	private SiteReads() {}

	/** Reads one site's payload (contacts the controller). */
	interface Fetch<J> {
		J fetch(String site) throws Exception;
	}

	/** Per-site payloads and per-site failures, in site order. */
	static final class Result<J> {
		final Map<String, J> bySite;
		/** Site name to a one-line reason. */
		final Map<String, String> failed;

		Result(Map<String, J> bySite, Map<String, String> failed) {
			this.bySite = Collections.unmodifiableMap(bySite);
			this.failed = Collections.unmodifiableMap(failed);
		}
	}

	/**
	 * Codex P1: per-site cool-down windows for one configuration. Held in
	 * the adapter's per-configuration holder, so an instance edit starts with
	 * none. Thread safe.
	 */
	static final class Cooldowns {
		private static final class Entry {
			final long until;
			final Exception cause;
			final int status;

			Entry(long until, Exception cause, int status) {
				this.until = until;
				this.cause = cause;
				this.status = status;
			}
		}

		private final SnapshotCache.Clock clock;
		private final long authMs;
		private final long retryMs;
		private final Map<String, Entry> bySite = new HashMap<>();

		/**
		 * @param clock   the time source
		 * @param authMs  the window after a 401, 403 or 429
		 * @param retryMs the window after any other failure
		 */
		Cooldowns(SnapshotCache.Clock clock, long authMs, long retryMs) {
			this.clock = clock;
			this.authMs = authMs;
			this.retryMs = retryMs;
		}

		/**
		 * The reason {@code site} is skipped this build, or {@code null}
		 * when it is to be asked. A window that has passed is cleared here,
		 * with one INFO for an authentication-class one.
		 */
		synchronized String skip(String what, String site, Consumer<String> info) {
			Entry e = bySite.get(site);
			if (e == null) return null;
			long now = clock.millis();
			if (now < e.until) {
				return "not tried: cooling down after "
						+ (e.status != 0 ? "HTTP " + e.status + " ("
								+ UniFiFailures.refusalKind(e.cause) + ")"
								: UniFiFailures.describe(e.cause))
						+ "; next attempt at the first build after "
						+ secondsBetween(now, e.until) + " s";
			}
			bySite.remove(site);
			if (e.status != 0) {
				info.accept("Snapshot: " + what + " cool-down for site " + site
						+ " (HTTP " + e.status + ") has passed; asking the site "
						+ "again this build.");
			}
			return null;
		}

		/** The original failure behind {@code site}'s window, or null. */
		synchronized Exception cause(String site) {
			Entry e = bySite.get(site);
			return e == null ? null : e.cause;
		}

		/** Starts {@code site}'s window after {@code failure}. */
		synchronized void start(String what, String site, Exception failure,
				Consumer<String> warn) {
			int status = UniFiFailures.authStatus(failure);
			long window = status != 0 ? authMs : retryMs;
			bySite.put(site, new Entry(clock.millis() + window, failure, status));
			if (status != 0) {
				warn.accept("Snapshot: " + what + " for site " + site
						+ " is cooling down after HTTP " + status + " ("
						+ UniFiFailures.refusalKind(failure) + "): the site is "
						+ "not asked again for " + secondsBetween(0L, window)
						+ " s unless the adapter instance settings are edited; "
						+ "its resources stay ERROR until then.");
			}
		}

		/** Test hook: whether {@code site} has a window recorded. */
		synchronized boolean has(String site) {
			return bySite.containsKey(site);
		}

		private static long secondsBetween(long from, long to) {
			return Math.max(0L, (to - from + 999L) / 1000L);
		}
	}

	/** As the five-argument form with no cool-downs (every site asked). */
	static <J> Result<J> readAll(String what, List<String> sites,
			Fetch<J> fetch, Consumer<String> warn)
			throws IOException, InterruptedException {
		return readAll(what, sites, fetch, warn, null, w -> { });
	}

	/**
	 * @param what  the call, for messages (e.g. {@code "stat/device"})
	 * @param sites site names from {@code /self/sites}
	 * @param fetch the per-site call
	 * @param warn  receives one WARN line per failed site, and one when a
	 *              site's authentication-class cool-down starts
	 * @param cooldowns the configuration's per-site windows (Codex P1), or
	 *              {@code null} for none
	 * @param info  receives one INFO line when a site's authentication-class
	 *              cool-down has passed
	 * @throws IOException when there is at least one site and every site
	 *         failed, cooled-down sites included (cause: the first failure,
	 *         or the first cooled site's original failure when no site was
	 *         asked)
	 * @throws InterruptedException on Stop Collecting, never recorded as a
	 *         site failure
	 */
	static <J> Result<J> readAll(String what, List<String> sites,
			Fetch<J> fetch, Consumer<String> warn, Cooldowns cooldowns,
			Consumer<String> info)
			throws IOException, InterruptedException {
		Map<String, J> ok = new LinkedHashMap<>();
		Map<String, String> failed = new LinkedHashMap<>();
		Exception first = null;
		Exception firstCooled = null;
		int failures = 0;
		for (int i = 0; i < sites.size(); i++) {
			String site = sites.get(i);
			String cooling = cooldowns == null ? null
					: cooldowns.skip(what, site, info);
			if (cooling != null) {
				// Codex P1: not asked; stays failed until its window passes.
				failed.put(site, cooling);
				failures++;
				if (firstCooled == null) firstCooled = cooldowns.cause(site);
				continue;
			}
			try {
				ok.put(site, fetch.fetch(site));
			} catch (InterruptedException ie) {
				throw ie;
			} catch (Exception e) {
				if (first == null) first = e;
				failures++;
				String reason = UniFiFailures.describe(e);
				failed.put(site, reason);
				warn.accept("Snapshot: " + what + " failed for site " + site
						+ ": " + reason + ". That site's resources are ERROR "
						+ "this cycle; the other sites are collected.");
				if (cooldowns != null) {
					cooldowns.start(what, site, e, warn);
				}
				if (UniFiFailures.isConnectFailure(e) && i + 1 < sites.size()) {
					List<String> rest = sites.subList(i + 1, sites.size());
					String skipped = "not tried: site " + site
							+ " could not connect to the controller (" + reason + ")";
					for (String r : rest) {
						failed.put(r, skipped);
						failures++;
					}
					warn.accept("Snapshot: " + what + " not tried for site(s) "
							+ rest + " because site " + site + " could not "
							+ "connect to the controller. Those sites' resources "
							+ "are ERROR this cycle.");
					break;
				}
			}
		}
		if (!sites.isEmpty() && failures == sites.size()) {
			Exception cause = first != null ? first : firstCooled;
			throw new IOException("UniFi " + what + " failed for every site ("
					+ sites.size() + "): " + UniFiFailures.describe(cause),
					cause);
		}
		return new Result<>(ok, failed);
	}
}
