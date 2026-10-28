package com.vcfcf.adapters.unifi;

import java.util.function.Consumer;

/**
 * Build 15 (issue #18, peer review 4): whether this snapshot build asks the
 * controller for {@code stat/sysinfo}. It is asked once per build (it
 * describes the controller, not a site). After an authentication-class
 * refusal (401, 403 or 429, see {@link UniFiFailures#authStatus}) it is not
 * asked again for {@code authMs}, so an account refused sysinfo (for
 * example a View Only role, to be confirmed on devel) does not cost one
 * extra login per cycle: the framework's session handling logs in again
 * and replays every refused request once. One WARN when the window starts,
 * one INFO when it has passed. Any other failure is retried on the next
 * build (one INFO). One instance per configure, in the adapter's
 * {@code Configured} holder, so an instance edit starts with no window. No
 * VCF Ops SDK and no framework-jar dependency: the plain-main tests drive it.
 */
final class SysinfoGate {

	private final SnapshotCache.Clock clock;
	private final long authMs;
	/** The call, as logged ({@code stat/sysinfo}). */
	private final String call;
	/** What a refusal costs, as logged. */
	private final String loss;
	private long until;
	private int status;

	SysinfoGate(SnapshotCache.Clock clock, long authMs) {
		this(clock, authMs, "stat/sysinfo",
				"the sites' Version and Timezone are not published");
	}

	/**
	 * Build 19 review B2: the same gate for another once-per-build call
	 * ({@code /api/system}), named {@code call} in its log lines, with
	 * {@code loss} saying what a refusal costs.
	 */
	SysinfoGate(SnapshotCache.Clock clock, long authMs, String call, String loss) {
		this.clock = clock;
		this.authMs = authMs;
		this.call = call;
		this.loss = loss;
	}

	/** Whether to ask this build; INFO once when a window has passed. */
	synchronized boolean shouldAsk(Consumer<String> info) {
		if (status == 0) return true;
		long now = clock.millis();
		if (now < until && now >= until - authMs) return false;
		// Passed (or the clock stepped back past its start): ask again.
		info.accept("Snapshot: " + call + " cool-down (HTTP " + status
				+ ") has passed; asking again this build.");
		status = 0;
		return true;
	}

	/** Records a failed ask; starts the window after an auth-class refusal. */
	synchronized void failed(Exception e, Consumer<String> warn,
			Consumer<String> info) {
		int s = UniFiFailures.authStatus(e);
		if (s != 0) {
			status = s;
			until = clock.millis() + authMs;
			warn.accept("Snapshot: " + call + " refused with HTTP " + s + " ("
					+ UniFiFailures.refusalKind(e) + "): not asked again for "
					+ (authMs / 1000L) + " s unless the adapter instance settings "
					+ "are edited; " + loss + " until then.");
		} else {
			info.accept("Snapshot: " + call + " not available this build ("
					+ loss + "): " + e.getMessage());
		}
	}

	/** Test hook: whether a refusal window is open. */
	synchronized boolean coolingDown() {
		return status != 0 && clock.millis() < until;
	}
}
