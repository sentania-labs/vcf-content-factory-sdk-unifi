package com.vcfcf.adapters.unifi;

import java.util.function.BiConsumer;
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
 * in and until its back-off has passed. After an authentication refusal
 * or rate limiting (401, 403 or 429 anywhere in the cause chain; the WARN
 * says which, see {@link UniFiFailures#refusalKind}) the back-off is
 * {@code authBackoffMs}; after any other failure it is
 * {@code retryBackoffMs}. Every caller, the one whose build failed
 * included, gets a {@link CachedFailureException} that names the original
 * (its class and message chain) in its message and carries its
 * classification (authentication status, unreachable) as fields,
 * classified once when the build failed. It has no cause and no stack
 * trace, so no framework line for it is a stack trace: not the
 * per-resource lines, and not the collect-path discovery WARN on the cycle
 * that tried the login (before the devel_9_1 follow-up that caller got the
 * original exception and the framework logged its 24-line stack on the
 * first cycle and every back-off expiry). The original, with its stack,
 * goes to the optional debug sink given at construction. The adapter
 * passes a logger it resolved once in configureAdapter: the framework's
 * componentLogger resets the logger to INFO on every call, so resolving it
 * per failure would drop every DEBUG line. A fault in the sink is
 * swallowed; it never reaches the collect path. One WARN per failed build, through the
 * supplied sink; the framework still logs its own line for each resource
 * that fails.
 *
 * <p><b>One build per cycle (issue #12).</b> A snapshot serves the cycle it
 * was built in and is rebuilt in the next one; {@code maxAgeMs} is only a
 * safety net for a cycle that never ends (or a framework that stops calling
 * {@link #beginCycle}). Its age counts from when the build finished: before
 * build 14 it counted from when the build started, so a build slower than
 * the refresh interval was stale on arrival and every resource rebuilt it.
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

	/**
	 * A failed build as every caller sees it: thrown by the build that
	 * failed (devel_9_1 follow-up) and rethrown from the cache without
	 * contacting the controller. Review W3: no cause chain and no stack trace (the framework logs the
	 * exception it receives once per failed resource, so a chain here was
	 * one multi-line stack per object per cycle). What status mapping needs
	 * is carried as fields instead, read by {@link UniFiFailures}.
	 */
	static final class CachedFailureException extends Exception {
		private static final long serialVersionUID = 1L;

		private final int authStatus;
		private final boolean unreachable;

		/**
		 * @param message     one line naming the original failure
		 * @param authStatus  {@link UniFiFailures#authStatus} of the original
		 * @param unreachable {@link UniFiFailures#isUnreachable} of the original
		 */
		CachedFailureException(String message, int authStatus,
				boolean unreachable) {
			super(message, null, false, false);
			this.authStatus = authStatus;
			this.unreachable = unreachable;
		}

		/** The original failure's authentication-class status, or 0. */
		int authStatus() {
			return authStatus;
		}

		/** Whether the original failure meant the controller was unreachable. */
		boolean unreachable() {
			return unreachable;
		}
	}

	private final Clock clock;
	private final long maxAgeMs;
	private final long retryBackoffMs;
	private final long authBackoffMs;
	private final BiConsumer<String, Exception> debug;

	private long cycle;
	private S value;
	private long valueCycle;
	private long builtAt;
	private int builds;

	private String failure;
	private int failureAuth;
	private boolean failureUnreachable;
	private long failureCycle;
	private long failedAt;
	private long retryAt;

	/**
	 * @param clock          the time source
	 * @param maxAgeMs       a snapshot older than this (counted from the end
	 *                       of its build) is rebuilt even in its own cycle
	 * @param retryBackoffMs how long a failure that is not authentication
	 *                       class blocks a new build
	 * @param authBackoffMs  how long an authentication-class failure blocks a
	 *                       new build (and so a new login)
	 */
	SnapshotCache(Clock clock, long maxAgeMs, long retryBackoffMs,
			long authBackoffMs) {
		this(clock, maxAgeMs, retryBackoffMs, authBackoffMs, null);
	}

	/**
	 * As the four-argument constructor, plus the sink that receives the WARN
	 * line and the original exception (stack included) of each failed
	 * build, since the exception thrown to the caller has none.
	 *
	 * @param debug the debug sink, stored for the life of this cache (the
	 *              adapter passes the {@code debug} method of a logger it
	 *              resolved once in configureAdapter); may be {@code null}
	 */
	SnapshotCache(Clock clock, long maxAgeMs, long retryBackoffMs,
			long authBackoffMs, BiConsumer<String, Exception> debug) {
		this.clock = clock;
		this.maxAgeMs = maxAgeMs;
		this.retryBackoffMs = retryBackoffMs;
		this.authBackoffMs = authBackoffMs;
		this.debug = debug;
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
						+ failure + "); next attempt "
						+ nextAttempt(now), failureAuth, failureUnreachable);
			}
			failure = null;
		}
		if (value != null && valueCycle == cycle && (now - builtAt) < maxAgeMs) {
			return value;
		}
		value = null;
		try {
			builds++;
			S s = builder.build();
			value = s;
			valueCycle = cycle;
			// Issue #12: stamped when the build finished, not when it began.
			builtAt = clock.millis();
			return s;
		} catch (InterruptedException ie) {
			throw ie;
		} catch (Exception e) {
			// devel_9_1 follow-up: the class name and the message chain of the
			// original, kept as text; the original itself is not rethrown.
			failure = e.getClass().getSimpleName() + ": "
					+ UniFiFailures.describe(e);
			failureCycle = cycle;
			failedAt = clock.millis();
			int auth = UniFiFailures.authStatus(e);
			failureAuth = auth;
			failureUnreachable = UniFiFailures.isUnreachable(e);
			retryAt = failedAt + (auth != 0 ? authBackoffMs : retryBackoffMs);
			String line = "UniFi snapshot build failed: "
					+ UniFiFailures.describe(e) + ". The other UniFi resources "
					+ "this cycle fail from this result without contacting the "
					+ "controller; next attempt "
					+ nextAttempt(failedAt)
					+ (auth != 0 ? " (HTTP " + auth + ": "
							+ UniFiFailures.refusalKind(e) + "; no login is tried "
							+ "before then unless the adapter instance settings "
							+ "are edited)" : "")
					+ ".";
			warn.accept(line);
			if (debug != null) {
				try {
					debug.accept(line, e);
				} catch (RuntimeException | LinkageError sinkFault) {
					// A logger fault must never reach the collect path; the
					// WARN above already carries the failure.
				}
			}
			// devel_9_1 follow-up: the caller whose build failed gets the
			// same stackless, classified exception the cache serves later,
			// so the framework's discovery WARN on this cycle is one line.
			throw new CachedFailureException("UniFi snapshot build failed ("
					+ failure + "); next attempt " + nextAttempt(failedAt),
					failureAuth, failureUnreachable);
		}
	}

	private String nextAttempt(long now) {
		long secs = secondsBetween(now, retryAt);
		return "at the first cycle after " + secs + " s";
	}

	private static long secondsBetween(long from, long to) {
		return Math.max(0L, (to - from + 999L) / 1000L);
	}

	/** Test hook: the debug sink given at construction, or null. */
	BiConsumer<String, Exception> debugSink() {
		return debug;
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
