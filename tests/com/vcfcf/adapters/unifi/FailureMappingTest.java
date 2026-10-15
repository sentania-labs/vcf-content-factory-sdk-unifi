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
		// The cached failure the other resources of the cycle get.
		Throwable cached = new SnapshotCache.CachedFailureException("cached",
				new RuntimeException("SessionCookieAuth: failed to acquire "
						+ "session token", new UnknownHostException("udm")));
		T.check(UniFiFailures.isUnreachable(cached), "cached failure is DOWN");
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
}
