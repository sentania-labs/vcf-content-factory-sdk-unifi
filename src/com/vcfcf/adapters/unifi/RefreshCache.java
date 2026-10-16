package com.vcfcf.adapters.unifi;

import java.util.function.BiConsumer;

/**
 * Build 15 (issue #21): a value refreshed at most every {@code refreshMs}
 * and reused between refreshes, with the last good value covering a failed
 * refresh. Holds the vmnic stitch's Suite API host list (refreshMs 15
 * minutes) and the Protect bootstrap (refreshMs 0: fetched on every call,
 * the last good value used only when a fetch fails) across collection
 * cycles (one instance each per configure, in
 * the adapter's {@code Configured} holder, so an instance edit starts
 * empty). No VCF Ops SDK and no framework-jar dependency: the plain-main
 * tests drive it with a fake clock and a counting loader.
 *
 * <p><b>A failed refresh keeps the last good value</b>, for at most
 * {@code maxStaleMs} after it was fetched, and reports the reuse through
 * the caller's sink so it can log it; the next call tries again. With no
 * value, or one older than {@code maxStaleMs}, the failure is thrown and
 * the caller degrades as it did before build 15 (no stitch, no Protect
 * objects for the cycle). Nothing is fabricated: a reused value is one the
 * source returned.
 *
 * <p>An {@link InterruptedException} (Stop Collecting) is thrown, never
 * absorbed. Thread safety: every method is {@code synchronized}; a refresh
 * runs under the lock.
 */
final class RefreshCache<T> {

	/** Fetches the value (contacts the source). */
	interface Loader<T> {
		T load() throws Exception;
	}

	private final SnapshotCache.Clock clock;
	private final long refreshMs;
	private final long maxStaleMs;

	private T value;
	private long loadedAt;

	RefreshCache(SnapshotCache.Clock clock, long refreshMs, long maxStaleMs) {
		this.clock = clock;
		this.refreshMs = refreshMs;
		this.maxStaleMs = maxStaleMs;
	}

	/**
	 * The cached value while it is younger than {@code refreshMs}; otherwise
	 * a fresh one from {@code loader}. When the refresh fails and the last
	 * good value is younger than {@code maxStaleMs}, that value is returned
	 * and {@code onReuse} gets the failure and the value's age in
	 * milliseconds; otherwise the failure is thrown. A loader that returns
	 * {@code null} caches nothing.
	 */
	synchronized T get(Loader<T> loader, BiConsumer<Exception, Long> onReuse)
			throws Exception {
		if (value != null && clock.millis() - loadedAt < refreshMs) {
			return value;
		}
		try {
			T fresh = loader.load();
			if (fresh != null) {
				value = fresh;
				loadedAt = clock.millis();
			}
			return fresh;
		} catch (InterruptedException e) {
			throw e;
		} catch (Exception e) {
			long age = clock.millis() - loadedAt;
			if (value != null && age < maxStaleMs) {
				if (onReuse != null) {
					try {
						onReuse.accept(e, age);
					} catch (RuntimeException ignored) {
						// logging is best effort
					}
				}
				return value;
			}
			throw e;
		}
	}
}
