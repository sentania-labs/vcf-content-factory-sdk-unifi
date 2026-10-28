package com.vcfcf.adapters.unifi;

import java.util.HashSet;
import java.util.Set;

/**
 * Build 18 review (N2, N3): log a recurring problem once per streak, not
 * once per instance lifetime and not once per resource. A streak is a run of
 * collection cycles in each of which {@link #fail(String)} was called for
 * the key at least once; a cycle without a failure ends it, so the next
 * failure logs again. No VCF Ops SDK and no framework-jar dependency, so the
 * plain-main tests drive exactly the logic the adapter runs.
 *
 * <p>State is bounded by the keys the caller passes (a fixed name, or one
 * per NVR and source). Synchronized because per-resource collects and the
 * {@code onDiscover} path can call it from different threads.
 */
final class StreakLog {

	/** Keys whose streak is open (a log line was already written). */
	private final Set<String> open = new HashSet<>();
	/** Keys that failed in the current cycle. */
	private final Set<String> failedThisCycle = new HashSet<>();

	/**
	 * Records a failure for {@code key}. Returns {@code true} when this is
	 * the first failure of a streak, so the caller logs; {@code false} when
	 * the streak was already logged.
	 */
	synchronized boolean fail(String key) {
		failedThisCycle.add(key);
		return open.add(key);
	}

	/**
	 * Call once at the start of every collection cycle. A key that did not
	 * fail in the cycle just ended closes its streak.
	 */
	synchronized void cycleStart() {
		open.retainAll(failedThisCycle);
		failedThisCycle.clear();
	}
}
