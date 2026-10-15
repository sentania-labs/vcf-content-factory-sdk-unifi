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
 * acquire session token", cause)}, so the meaningful exception is rarely
 * the outermost one. A {@link SnapshotCache.CachedFailureException} has no
 * cause; it carries the original failure's classification as fields, and
 * those answer for it.
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
			if (c instanceof SnapshotCache.CachedFailureException) {
				return ((SnapshotCache.CachedFailureException) c).authStatus();
			}
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
	 * Review NIT-2: what the first authentication-class refusal in
	 * {@code t}'s cause chain means, for the WARN of a failed build, or
	 * {@code null} when there is none. A 429 is rate limiting, never an
	 * authentication refusal; a 401 or 403 on a data call (the framework has
	 * already logged in again and replayed it once) is an authentication or
	 * permission refusal, on the login POST it is a refused login.
	 */
	static String refusalKind(Throwable t) {
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
			if (c instanceof UniFiHttpException) {
				UniFiHttpException h = (UniFiHttpException) c;
				int st = h.status();
				if (st == 429) {
					return h.isLogin() ? "the controller is rate limiting logins"
							: "the controller is rate limiting requests";
				}
				if (st == 401 || st == 403) {
					return h.isLogin() ? "the controller refused the login"
							: "the controller refused a request even after a "
									+ "fresh login (an authentication or "
									+ "permission refusal)";
				}
			}
		}
		return null;
	}

	/**
	 * Issue #25: whether {@code t} means the controller could not be reached,
	 * so the resource is DOWN (the target is down) rather than ERROR
	 * (something is wrong with the adapter or its data). True when the cause
	 * chain holds a {@link java.net.ConnectException}, a
	 * {@link java.net.http.HttpTimeoutException} (which includes
	 * {@link java.net.http.HttpConnectTimeoutException}), a
	 * {@link java.net.SocketTimeoutException}, an
	 * {@link java.net.UnknownHostException} or a
	 * {@link java.net.NoRouteToHostException}, and no authentication refusal
	 * ({@link #authStatus}): a controller that answered 401 was reached.
	 * Certificate failures, unreadable payloads and anything unexpected stay
	 * ERROR.
	 */
	static boolean isUnreachable(Throwable t) {
		if (authStatus(t) != 0) return false;
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
			if (c instanceof SnapshotCache.CachedFailureException) {
				return ((SnapshotCache.CachedFailureException) c).unreachable();
			}
			if (c instanceof java.net.ConnectException
					|| c instanceof java.net.http.HttpTimeoutException
					|| c instanceof java.net.SocketTimeoutException
					|| c instanceof java.net.UnknownHostException
					|| c instanceof java.net.NoRouteToHostException) {
				return true;
			}
		}
		return false;
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
