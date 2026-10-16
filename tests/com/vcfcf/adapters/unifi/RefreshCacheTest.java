package com.vcfcf.adapters.unifi;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Build 15 (issue #21): the vmnic stitch and the Protect bootstrap are
 * fetched at most every refresh interval, a failed refresh keeps the last
 * good value for a bounded time, and nothing is reused past it. Run from
 * the repo root (ci/run_java_tests.sh).
 */
public final class RefreshCacheTest {

	private static final long MIN = 60_000L;

	public static void main(String[] args) throws Exception {
		reusedBetweenRefreshes();
		failureWithNoValueThrows();
		failureKeepsLastGoodWhileFresh();
		failureAfterMaxStaleThrows();
		interruptIsNeverAbsorbed();
		nullCachesNothing();
		zeroRefreshFetchesEveryCallAndCoversFailures();
		System.out.println("RefreshCacheTest: all assertions passed");
	}

	static void reusedBetweenRefreshes() throws Exception {
		AtomicLong now = new AtomicLong(0);
		AtomicInteger loads = new AtomicInteger();
		RefreshCache<String> c = new RefreshCache<>(now::get, 15 * MIN, 60 * MIN);
		RefreshCache.Loader<String> loader = () -> "v" + loads.incrementAndGet();
		T.eq("v1", c.get(loader, null), "first call loads");
		now.set(5 * MIN);
		T.eq("v1", c.get(loader, null), "5 min later: reused");
		now.set(14 * MIN + 59_999);
		T.eq("v1", c.get(loader, null), "just under 15 min: reused");
		T.eq(1, loads.get(), "three cycles, one fetch");
		now.set(15 * MIN);
		T.eq("v2", c.get(loader, null), "at 15 min: refreshed");
		T.eq(2, loads.get(), "second fetch");
	}

	static void failureWithNoValueThrows() {
		RefreshCache<String> c = new RefreshCache<>(() -> 0L, 15 * MIN, 60 * MIN);
		try {
			c.get(() -> { throw new IOException("suite api down"); }, (e, a) -> {
				throw new AssertionError("nothing to reuse");
			});
			throw new AssertionError("a failure with no value must be thrown");
		} catch (Exception e) {
			T.eq("suite api down", e.getMessage(), "original failure");
		}
	}

	static void failureKeepsLastGoodWhileFresh() throws Exception {
		AtomicLong now = new AtomicLong(0);
		RefreshCache<String> c = new RefreshCache<>(now::get, 15 * MIN, 60 * MIN);
		c.get(() -> "good", null);
		now.set(20 * MIN);
		AtomicLong reportedAge = new AtomicLong(-1);
		AtomicInteger loads = new AtomicInteger();
		String got = c.get(() -> {
			loads.incrementAndGet();
			throw new IOException("HTTP 503");
		}, (e, age) -> reportedAge.set(age));
		T.eq("good", got, "last good reused after a failed refresh");
		T.eq(20 * MIN, reportedAge.get(), "reuse reported with its age");
		now.set(21 * MIN);
		c.get(() -> {
			loads.incrementAndGet();
			throw new IOException("HTTP 503");
		}, (e, age) -> { });
		T.eq(2, loads.get(), "a failed refresh is tried again next call");
		now.set(22 * MIN);
		T.eq("new", c.get(() -> "new", null), "recovery replaces it");
		now.set(30 * MIN);
		T.eq("new", c.get(() -> "never", null), "and restarts the interval");
	}

	static void failureAfterMaxStaleThrows() throws Exception {
		AtomicLong now = new AtomicLong(0);
		RefreshCache<String> c = new RefreshCache<>(now::get, 15 * MIN, 60 * MIN);
		c.get(() -> "old", null);
		now.set(60 * MIN);
		try {
			c.get(() -> { throw new IOException("still down"); }, (e, a) -> {
				throw new AssertionError("must not reuse an hour-old value");
			});
			throw new AssertionError("stale past the bound must not be served");
		} catch (IOException e) {
			T.eq("still down", e.getMessage(), "original failure");
		}
	}

	static void interruptIsNeverAbsorbed() throws Exception {
		AtomicLong now = new AtomicLong(0);
		RefreshCache<String> c = new RefreshCache<>(now::get, 15 * MIN, 60 * MIN);
		c.get(() -> "good", null);
		now.set(16 * MIN);
		try {
			c.get(() -> { throw new InterruptedException(); }, (e, a) -> {
				throw new AssertionError("an interrupt is not a failure to cover");
			});
			throw new AssertionError("interrupt swallowed");
		} catch (InterruptedException expected) {
			// Stop Collecting reaches the framework
		}
	}

	/**
	 * The Protect setting (refreshMs 0): fetched on every call, so values
	 * are one cycle fresh; the last good value is used only when a fetch
	 * fails, and only within the stale bound.
	 */
	static void zeroRefreshFetchesEveryCallAndCoversFailures() throws Exception {
		AtomicLong now = new AtomicLong(0);
		AtomicInteger loads = new AtomicInteger();
		RefreshCache<String> c = new RefreshCache<>(now::get, 0L, 60 * MIN);
		RefreshCache.Loader<String> ok = () -> "b" + loads.incrementAndGet();
		T.eq("b1", c.get(ok, null), "first cycle fetches");
		T.eq("b2", c.get(ok, null), "same instant: fetched again, never served cached");
		now.set(5 * MIN);
		T.eq("b3", c.get(ok, null), "next cycle fetches");
		T.eq(3, loads.get(), "one fetch per call");
		now.set(10 * MIN);
		AtomicLong age = new AtomicLong(-1);
		T.eq("b3", c.get(() -> { throw new IOException("protect 502"); },
				(e, a) -> age.set(a)), "failed fetch: last good reused");
		T.eq(5 * MIN, age.get(), "reuse reported with its age");
		now.set(64 * MIN + 59_999);
		T.eq("b3", c.get(() -> { throw new IOException("protect 502"); },
				(e, a) -> { }), "still within the hour");
		now.set(65 * MIN);
		try {
			c.get(() -> { throw new IOException("protect 502"); }, (e, a) -> {
				throw new AssertionError("an hour-old bootstrap must not be reused");
			});
			throw new AssertionError("past the hour the failure is thrown");
		} catch (IOException expected) {
			// Protect absent for the cycle, as in build 14
		}
		T.eq("b4", c.get(ok, null), "recovery fetches fresh");
	}

	static void nullCachesNothing() throws Exception {
		AtomicInteger loads = new AtomicInteger();
		RefreshCache<String> c = new RefreshCache<>(() -> 0L, 15 * MIN, 60 * MIN);
		T.eq(null, c.get(() -> { loads.incrementAndGet(); return null; }, null), "null");
		c.get(() -> { loads.incrementAndGet(); return null; }, null);
		T.eq(2, loads.get(), "null is not cached");
	}
}
