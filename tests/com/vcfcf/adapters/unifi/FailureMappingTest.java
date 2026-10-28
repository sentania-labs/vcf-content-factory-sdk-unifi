package com.vcfcf.adapters.unifi;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;

/**
 * Build 14 (issue #25): an unreachable controller is DOWN, whatever wraps
 * the network exception; everything else is ERROR. The adapter's
 * mapCollectException returns DOWN exactly when
 * {@link UniFiFailures#isUnreachable} is true. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class FailureMappingTest {

	public static void main(String[] args) {
		unreachableIsDown();
		wrappedUnreachableIsDown();
		everythingElseIsError();
		connectFailureIsNarrowerThanUnreachable();
		unifiOsRequired();
		System.out.println("FailureMappingTest: all assertions passed");
	}

	static void unreachableIsDown() {
		Throwable[] down = {
			new ConnectException("Connection refused"),
			new HttpConnectTimeoutException("HTTP connect timed out"),
			new HttpTimeoutException("request timed out"),
			new SocketTimeoutException("Read timed out"),
			new UnknownHostException("udm.example.invalid"),
			new NoRouteToHostException("No route to host"),
		};
		for (Throwable t : down) {
			T.check(UniFiFailures.isUnreachable(t),
					t.getClass().getSimpleName() + " is DOWN");
		}
	}

	/** The issue's wrapped cases: the old check saw only the outer type. */
	static void wrappedUnreachableIsDown() {
		// The framework's login wrapper around an unreachable controller.
		Throwable login = new RuntimeException("SessionCookieAuth: failed to "
				+ "acquire session token", new HttpConnectTimeoutException(
						"HTTP connect timed out"));
		T.check(UniFiFailures.isUnreachable(login), "login wrapper is DOWN");
		// An IOException whose cause is a ConnectException.
		T.check(UniFiFailures.isUnreachable(new IOException("send failed",
				new ConnectException("refused"))), "wrapped connect is DOWN");
		// The cached failure the other resources of the cycle get: no cause
		// chain (review W3), the classification is carried as a field.
		Throwable cached = new SnapshotCache.CachedFailureException("cached",
				0, true);
		T.check(UniFiFailures.isUnreachable(cached), "cached failure is DOWN");
		T.check(!UniFiFailures.isUnreachable(
				new SnapshotCache.CachedFailureException("cached", 0, false)),
				"a cached failure that was not unreachable is ERROR");
		// Every site timed out (SiteReads' all-failed exception).
		T.check(UniFiFailures.isUnreachable(new IOException("UniFi "
				+ "stat/device failed for every site (2): timed out",
				new HttpTimeoutException("request timed out"))),
				"all sites timed out is DOWN");
	}

	static void everythingElseIsError() {
		T.check(!UniFiFailures.isUnreachable(new RuntimeException(
				"SessionCookieAuth: failed to acquire session token",
				new UniFiHttpException("UniFi login failed: HTTP 401", 401,
						true))), "wrong password is ERROR");
		T.check(!UniFiFailures.isUnreachable(new UniFiHttpException(
				"UniFi GET returned HTTP 500", 500, false)), "HTTP 500 is ERROR");
		T.check(!UniFiFailures.isUnreachable(new IOException("UniFi "
				+ "/self/sites returned a 200 payload with no readable 'data' "
				+ "site list")), "unreadable payload is ERROR");
		T.check(!UniFiFailures.isUnreachable(new SSLHandshakeException(
				"PKIX path building failed")), "certificate failure is ERROR");
		T.check(!UniFiFailures.isUnreachable(new IllegalStateException("bug")),
				"unexpected exception is ERROR");
		T.check(!UniFiFailures.isUnreachable(null), "null is ERROR");
		// A self-referencing cause chain terminates.
		IOException loop = new IOException("loop");
		RuntimeException outer = new RuntimeException("outer", loop);
		loop.initCause(outer);
		T.check(!UniFiFailures.isUnreachable(outer), "a cause cycle terminates");
	}

	/**
	 * Review N1: isConnectFailure (what stops the per-site loop) is the
	 * connect-level subset of isUnreachable. A read timeout is still DOWN
	 * but is not connect-level.
	 */
	static void connectFailureIsNarrowerThanUnreachable() {
		Throwable[] connect = {
			new ConnectException("Connection refused"),
			new HttpConnectTimeoutException("HTTP connect timed out"),
			new UnknownHostException("udm.example.invalid"),
			new NoRouteToHostException("No route to host"),
			new RuntimeException("SessionCookieAuth: failed to acquire session "
					+ "token", new HttpConnectTimeoutException("HTTP connect "
							+ "timed out")),
		};
		for (Throwable t : connect) {
			T.check(UniFiFailures.isConnectFailure(t),
					t.getClass().getSimpleName() + " is connect-level");
			T.check(UniFiFailures.isUnreachable(t),
					t.getClass().getSimpleName() + " is also DOWN");
		}
		Throwable[] read = {
			new HttpTimeoutException("request timed out"),
			new SocketTimeoutException("Read timed out"),
			new IOException("wrapped", new HttpTimeoutException("request timed out")),
		};
		for (Throwable t : read) {
			T.check(!UniFiFailures.isConnectFailure(t),
					t.getClass().getSimpleName() + " (read timeout) is not connect-level");
			T.check(UniFiFailures.isUnreachable(t),
					t.getClass().getSimpleName() + " (read timeout) is still DOWN");
		}
		T.check(!UniFiFailures.isConnectFailure(new UniFiHttpException(
				"HTTP 403", 403, false)), "a 403 is not connect-level");
		T.check(!UniFiFailures.isConnectFailure(new IOException("x",
				new UniFiHttpException("HTTP 401", 401, false))),
				"a refusal is never connect-level");
		UniFiHttpException refusedOverConnect =
				new UniFiHttpException("HTTP 401", 401, true);
		refusedOverConnect.initCause(new ConnectException("refused"));
		T.check(!UniFiFailures.isConnectFailure(refusedOverConnect),
				"a controller that answered 401 was reached, whatever lies below");
		T.check(!UniFiFailures.isConnectFailure(null), "null is not");
		IOException loop = new IOException("loop");
		RuntimeException outer = new RuntimeException("outer", loop);
		loop.initCause(outer);
		T.check(!UniFiFailures.isConnectFailure(outer), "a cause cycle terminates");
	}

	/** Build 18 (issue #17): a 404 names the UniFi OS requirement. */
	static void unifiOsRequired() {
		Throwable login = new RuntimeException(
				"SessionCookieAuth: failed to acquire session token",
				new UniFiHttpException("UniFi login failed: HTTP 404", 404, true));
		String m = UniFiFailures.unifiOsRequired(login);
		T.check(m != null && m.startsWith("This adapter supports UniFi OS consoles only")
				&& m.contains("/api/auth/login"), "login 404: " + m);
		String n = UniFiFailures.unifiOsRequired(new UniFiHttpException(
				"UniFi GET /proxy/network/api/self/sites returned HTTP 404", 404, false));
		T.check(n != null && n.contains("/proxy/network"), "network 404: " + n);
		T.eq(null, UniFiFailures.unifiOsRequired(new UniFiHttpException(
				"UniFi login failed: HTTP 401", 401, true)), "401 is not the 404 message");
		T.eq(null, UniFiFailures.unifiOsRequired(new IOException("x")), "no HTTP status");
		T.eq(null, UniFiFailures.unifiOsRequired(null), "null");
	}
}
