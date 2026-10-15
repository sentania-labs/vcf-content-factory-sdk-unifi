package com.vcfcf.adapters.unifi;

import java.util.function.Consumer;

/**
 * Build 14: the per-configuration holder of the UniFi snapshot, its last
 * failure and the collection-cycle counter. One instance per configure
 * (the adapter publishes it inside its {@code Configured} holder), so an
 * instance edit starts with an empty cache and may log in at once. No VCF
 * Ops SDK and no framework-jar dependency: the plain-main tests drive it
 * with a fake clock and a counting builder.
 *
 * <p><b>Failures are cached (issue #11).</b> Before build 14 a failed build
 * left nothing behind, so every resource in the cycle started a new build,
 * and with it a new login: with a wrong password one cycle sent one failed
 * login per resource. Now a failed build is remembered and rethrown,
 * without contacting the controller, for the rest of the cycle it failed
 * in and until its back-off has passed. After an authentication-class
 * refusal (401, 403 or 429 anywhere in the cause chain) the back-off is
 * {@code authBackoffMs}; after any other failure it is
 * {@code retryBackoffMs}. The first resource gets the original exception;
 * the others get a {@link CachedFailureException} whose cause is that
 * exception, so status mapping sees the same chain. One WARN per failed
 * build, through the supplied sink.
 *
 * <p>An {@link InterruptedException} (Stop Collecting) is not cached.
 *
 * <p>Thread safety: every method is {@code synchronized}; a build runs under
 * the lock so only one thread contacts the controller at a time.
 */
final class SnapshotCache<S> {

	/** Builds one snapshot (contacts the controller). */
	interface Builder<S> {
		S build() throws Exception;
	}

	/** Milliseconds since the epoch; {@code System::currentTimeMillis} in production. */
	interface Clock {
		long millis();
	}

	/** A cached failure rethrown without contacting the controller. */
	static final class CachedFailureException extends Exception {
		private static final long serialVersionUID = 1L;

		CachedFailureException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private final Clock clock;
	private final long maxAgeMs;
	private final long retryBackoffMs;
	private final long authBackoffMs;

	private long cycle;
	private S value;
	private long builtAt;
	private int builds;

	private Exception failure;
	private long failureCycle;
	private long failedAt;
	private long retryAt;

	/**
	 * @param clock          the time source
	 * @param maxAgeMs       a snapshot older than this is rebuilt
	 * @param retryBackoffMs how long a failure that is not authentication
	 *                       class blocks a new build
	 * @param authBackoffMs  how long an authentication-class failure blocks a
	 *                       new build (and so a new login)
	 */
	SnapshotCache(Clock clock, long maxAgeMs, long retryBackoffMs,
			long authBackoffMs) {
		this.clock = clock;
		this.maxAgeMs = maxAgeMs;
		this.retryBackoffMs = retryBackoffMs;
		this.authBackoffMs = authBackoffMs;
	}

	/** Marks the start of a collection (or discovery) pass. */
	synchronized void beginCycle() {
		cycle++;
	}

	/**
	 * The snapshot for the current cycle: the cached one when it is fresh,
	 * the cached failure while it applies, otherwise a new build.
	 *
	 * @param builder contacts the controller and returns a snapshot
	 * @param warn    receives the one WARN line of a failed build
	 */
	synchronized S get(Builder<S> builder, Consumer<String> warn)
			throws Exception {
		long now = clock.millis();
		if (failure != null) {
			if (failureCycle == cycle || now < retryAt) {
				throw new CachedFailureException("UniFi controller not "
						+ "contacted: the snapshot build failed "
						+ secondsBetween(failedAt, now) + " s ago ("
						+ UniFiFailures.describe(failure) + "); next attempt "
						+ nextAttempt(now), failure);
			}
			failure = null;
		}
		if (value != null && (now - builtAt) < maxAgeMs) {
			return value;
		}
		value = null;
		long started = clock.millis();
		try {
			builds++;
			S s = builder.build();
			value = s;
			builtAt = started;
			return s;
		} catch (InterruptedException ie) {
			throw ie;
		} catch (Exception e) {
			failure = e;
			failureCycle = cycle;
			failedAt = clock.millis();
			int auth = UniFiFailures.authStatus(e);
			retryAt = failedAt + (auth != 0 ? authBackoffMs : retryBackoffMs);
			warn.accept("UniFi snapshot build failed: "
					+ UniFiFailures.describe(e) + ". The other UniFi resources "
					+ "this cycle fail from this result without contacting the "
					+ "controller; next attempt "
					+ nextAttempt(failedAt)
					+ (auth != 0 ? " (HTTP " + auth + " is an authentication "
							+ "refusal: no login is tried before then unless the "
							+ "adapter instance settings are edited)" : "")
					+ ".");
			throw e;
		}
	}

	private String nextAttempt(long now) {
		long secs = secondsBetween(now, retryAt);
		return "at the first cycle after " + secs + " s";
	}

	private static long secondsBetween(long from, long to) {
		return Math.max(0L, (to - from + 999L) / 1000L);
	}

	/** Test hook: how many builds were started. */
	synchronized int builds() {
		return builds;
	}

	/** Test hook: when the cached snapshot counts as built, or 0. */
	synchronized long builtAt() {
		return value != null ? builtAt : 0L;
	}
}
