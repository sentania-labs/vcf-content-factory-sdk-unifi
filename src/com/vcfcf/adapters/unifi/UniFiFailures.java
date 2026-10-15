package com.vcfcf.adapters.unifi;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Build 14: how a failed UniFi read is classified. No VCF Ops SDK and no
 * framework-jar dependency, so the plain-main tests exercise it directly.
 *
 * <p>Every check walks the cause chain (cycle safe): the framework wraps a
 * failed login in {@code RuntimeException("SessionCookieAuth: failed to
 * acquire session token", cause)}, and the snapshot cache wraps a cached
 * failure, so the meaningful exception is rarely the outermost one.
 */
final class UniFiFailures {

	private UniFiFailures() {}

	/**
	 * Issue #11: the HTTP status of the first authentication-class refusal
	 * (401, 403 or 429) in {@code t}'s cause chain, or 0 when there is none.
	 * After such a refusal a new login would be refused the same way, and
	 * repeated failed logins can rate-limit or lock the controller account.
	 */
	static int authStatus(Throwable t) {
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
			if (c instanceof UniFiHttpException) {
				int st = ((UniFiHttpException) c).status();
				if (st == 401 || st == 403 || st == 429) {
					return st;
				}
			}
		}
		return 0;
	}

	/**
	 * One line for a log or a resource error: each distinct message in the
	 * cause chain, outermost first, joined with ": " (so the framework's
	 * "failed to acquire session token" wrapper still shows the login
	 * status). A link with no message shows its simple class name. Messages
	 * here are already redacted by {@link UniFiApiClient}.
	 */
	static String describe(Throwable t) {
		if (t == null) return "unknown failure";
		StringBuilder sb = new StringBuilder();
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		String last = null;
		for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
			String m = c.getMessage();
			if (m == null || m.isEmpty()) m = c.getClass().getSimpleName();
			if (last != null && last.contains(m)) continue;
			if (sb.length() > 0) sb.append(": ");
			sb.append(m);
			last = m;
		}
		return sb.toString();
	}
}
