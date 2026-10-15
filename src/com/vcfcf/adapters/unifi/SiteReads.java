package com.vcfcf.adapters.unifi;

import java.io.IOException;
import java.util.Collections;
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
	 * @param what  the call, for messages (e.g. {@code "stat/device"})
	 * @param sites site names from {@code /self/sites}
	 * @param fetch the per-site call
	 * @param warn  receives one WARN line per failed site
	 * @throws IOException when there is at least one site and every site
	 *         failed (cause: the first failure)
	 * @throws InterruptedException on Stop Collecting, never recorded as a
	 *         site failure
	 */
	static <J> Result<J> readAll(String what, List<String> sites,
			Fetch<J> fetch, Consumer<String> warn)
			throws IOException, InterruptedException {
		Map<String, J> ok = new LinkedHashMap<>();
		Map<String, String> failed = new LinkedHashMap<>();
		Exception first = null;
		int failures = 0;
		for (String site : sites) {
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
			}
		}
		if (!sites.isEmpty() && failures == sites.size()) {
			throw new IOException("UniFi " + what + " failed for every site ("
					+ sites.size() + "): " + UniFiFailures.describe(first),
					first);
		}
		return new Result<>(ok, failed);
	}
}
