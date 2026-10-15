package com.vcfcf.adapters.unifi;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;

/**
 * Build 14 (issue #11): a failed snapshot build is cached, so the other
 * resources of the cycle fail fast without a new login, and an
 * authentication refusal is not retried before its back-off or an instance
 * edit. Driven with a fake clock and a fake controller whose login is
 * wrapped exactly as the framework's SessionCookieAuth wraps it. Run from
 * the repo root (ci/run_java_tests.sh).
 */
public final class SnapshotCacheTest {

	private static final long MIN = 60_000L;
	private static final long MAX_AGE = 15 * MIN;
	private static final long RETRY = MIN;
	private static final long AUTH = 15 * MIN;
	private static final int RESOURCES = 200;

	/** Fake time. */
	static final class FakeClock implements SnapshotCache.Clock {
		long now = 1_000_000L;

		@Override
		public long millis() {
			return now;
		}
	}

	/** Fake controller: counts login POSTs, answers each with a status. */
	static final class FakeController {
		int logins;
		int loginStatus = 200;
		Exception transport;

		/** One snapshot build: log in, then read. */
		String build() throws Exception {
			if (transport != null) throw transport;
			logins++;
			if (loginStatus != 200) {
				UniFiHttpException refused = new UniFiHttpException(
						"UniFi login failed: HTTP " + loginStatus, loginStatus,
						true);
				// What SessionCookieAuth.acquireFresh throws.
				throw new RuntimeException(
						"SessionCookieAuth: failed to acquire session token",
						refused);
			}
			return "snapshot#" + logins;
		}
	}

	public static void main(String[] args) throws Exception {
		healthyControllerOneBuildPerCycle();
		wrongPasswordOneLoginPerCycle();
		authBackoffSpansCycles();
		rateLimitAndForbiddenAreAuthClass();
		dataCallRateLimitIsNotCalledAnAuthRefusal();
		instanceEditTriesAtOnce();
		unreachableFailsFastAndRetriesNextCycle();
		failureHoldsForTheRestOfTheCycle();
		interruptIsNotCached();
		System.out.println("SnapshotCacheTest: all assertions passed");
	}

	private static SnapshotCache<String> cache(FakeClock clock) {
		return new SnapshotCache<>(clock, MAX_AGE, RETRY, AUTH);
	}

	/** Collects one cycle: n resources, each asking for the snapshot. */
	private static List<Exception> cycle(SnapshotCache<String> c,
			FakeController ctl, int n, List<String> warns) {
		c.beginCycle();
		List<Exception> failures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			try {
				c.get(ctl::build, warns::add);
			} catch (Exception e) {
				failures.add(e);
			}
		}
		return failures;
	}

	/** Must not regress: a healthy controller is read once per cycle. */
	static void healthyControllerOneBuildPerCycle() {
		FakeClock clock = new FakeClock();
		FakeController ctl = new FakeController();
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		T.eq(0, cycle(c, ctl, RESOURCES, warns).size(), "no failures");
		T.eq(1, ctl.logins, "one build serves every resource");
		T.eq(0, warns.size(), "no WARN");
	}

	/** The issue: one login per resource. Now: one per cycle at most. */
	static void wrongPasswordOneLoginPerCycle() {
		FakeClock clock = new FakeClock();
		FakeController ctl = new FakeController();
		ctl.loginStatus = 401;
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		List<Exception> failures = cycle(c, ctl, RESOURCES, warns);
		T.eq(1, ctl.logins, "the second resource does not log in");
		T.eq(RESOURCES, failures.size(), "every resource fails");
		T.check(failures.get(0) instanceof RuntimeException
				&& !(failures.get(0)
						instanceof SnapshotCache.CachedFailureException),
				"the first resource gets the original failure");
		for (int i = 1; i < failures.size(); i++) {
			Exception e = failures.get(i);
			T.check(e instanceof SnapshotCache.CachedFailureException,
					"resource " + i + " fails from the cache");
			T.eq(401, UniFiFailures.authStatus(e),
					"the cached failure carries its 401");
			T.eq(null, e.getCause(), "no cause chain on the cached copy");
			T.eq(0, e.getStackTrace().length,
					"no stack trace on the cached copy");
			T.check(!UniFiFailures.isUnreachable(e),
					"a cached 401 is ERROR, not DOWN");
			T.check(e.getMessage().contains("HTTP 401"),
					"the cached message says why: " + e.getMessage());
		}
		T.eq(1, warns.size(), "one WARN for the failed build");
		T.check(warns.get(0).contains("HTTP 401")
				&& warns.get(0).contains("no login is tried"),
				"the WARN names the refusal: " + warns.get(0));
	}

	/** No login again until the back-off expires (cycles every 5 min). */
	static void authBackoffSpansCycles() {
		FakeClock clock = new FakeClock();
		FakeController ctl = new FakeController();
		ctl.loginStatus = 401;
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		cycle(c, ctl, RESOURCES, warns);
		for (int k = 1; k <= 3; k++) {
			clock.now += 5 * MIN - 1_000L; // a cycle starts just under 5 min on
			cycle(c, ctl, RESOURCES, warns);
			T.eq(1, ctl.logins, "no login in cycle " + (k + 1)
					+ " inside the back-off");
		}
		clock.now += 5 * MIN;
		cycle(c, ctl, RESOURCES, warns);
		T.eq(2, ctl.logins, "one login once the back-off has passed");
		T.eq(2, warns.size(), "one WARN per failed build");

		ctl.loginStatus = 200; // password fixed on the controller side
		clock.now += AUTH + 1;
		T.eq(0, cycle(c, ctl, RESOURCES, warns).size(),
				"collects again after the back-off");
		T.eq(3, ctl.logins, "one login for the recovered cycle");
	}

	static void rateLimitAndForbiddenAreAuthClass() {
		for (int status : new int[] {403, 429}) {
			FakeClock clock = new FakeClock();
			FakeController ctl = new FakeController();
			ctl.loginStatus = status;
			SnapshotCache<String> c = cache(clock);
			List<String> warns = new ArrayList<>();
			cycle(c, ctl, RESOURCES, warns);
			clock.now += 5 * MIN;
			cycle(c, ctl, RESOURCES, warns);
			T.eq(1, ctl.logins, "HTTP " + status + " holds the next cycle");
		}
		// A 500 on login is not an authentication refusal.
		FakeClock clock = new FakeClock();
		FakeController ctl = new FakeController();
		ctl.loginStatus = 500;
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		cycle(c, ctl, RESOURCES, warns);
		clock.now += 5 * MIN;
		cycle(c, ctl, RESOURCES, warns);
		T.eq(2, ctl.logins, "HTTP 500 is retried the next cycle");
	}

	/**
	 * Review NIT-2: a 429 on a data call (after the framework's own retries)
	 * is described as rate limiting, not as an authentication refusal, and
	 * still holds the next build for the 15-minute back-off. A refused login
	 * is described as one.
	 */
	static void dataCallRateLimitIsNotCalledAnAuthRefusal() {
		FakeClock clock = new FakeClock();
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		int[] builds = {0};
		SnapshotCache.Builder<String> rateLimited = () -> {
			builds[0]++;
			throw new UniFiHttpException("UniFi GET /proxy/network/api/self/"
					+ "sites returned HTTP 429", 429, false);
		};
		c.beginCycle();
		try {
			c.get(rateLimited, warns::add);
		} catch (Exception expected) {
			// expected
		}
		T.eq(1, warns.size(), "one WARN");
		T.check(warns.get(0).contains("rate limiting requests"),
				"a data call 429 is rate limiting: " + warns.get(0));
		T.check(!warns.get(0).contains("authentication"),
				"and not an authentication refusal: " + warns.get(0));
		clock.now += 5 * MIN;
		c.beginCycle();
		try {
			c.get(rateLimited, warns::add);
		} catch (Exception expected) {
			// expected
		}
		T.eq(1, builds[0], "the 15-minute back-off still applies");

		FakeController ctl = new FakeController();
		ctl.loginStatus = 401;
		List<String> loginWarns = new ArrayList<>();
		cycle(cache(new FakeClock()), ctl, 1, loginWarns);
		T.check(loginWarns.get(0).contains("refused the login"),
				"a login 401 is a refused login: " + loginWarns.get(0));
		ctl.loginStatus = 429;
		loginWarns.clear();
		cycle(cache(new FakeClock()), ctl, 1, loginWarns);
		T.check(loginWarns.get(0).contains("rate limiting logins"),
				"a login 429 is rate limiting: " + loginWarns.get(0));
	}

	/** A new configuration gets a new cache: it may log in at once. */
	static void instanceEditTriesAtOnce() {
		FakeClock clock = new FakeClock();
		FakeController ctl = new FakeController();
		ctl.loginStatus = 401;
		List<String> warns = new ArrayList<>();
		cycle(cache(clock), ctl, RESOURCES, warns);
		ctl.loginStatus = 200; // the admin fixed the password
		SnapshotCache<String> edited = cache(clock); // configure publishes a new one
		T.eq(0, cycle(edited, ctl, RESOURCES, warns).size(),
				"the edited instance collects in the same minute");
		T.eq(2, ctl.logins, "one login for the edited instance");
	}

	/** An unreachable controller costs one attempt per cycle, not per resource. */
	static void unreachableFailsFastAndRetriesNextCycle() {
		FakeClock clock = new FakeClock();
		FakeController ctl = new FakeController();
		int[] attempts = {0};
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		SnapshotCache.Builder<String> unreachable = () -> {
			attempts[0]++;
			clock.now += 90_000L; // three 30 s timeouts
			throw new RuntimeException("SessionCookieAuth: failed to acquire "
					+ "session token", new ConnectException("Connection refused"));
		};
		c.beginCycle();
		for (int i = 0; i < RESOURCES; i++) {
			try {
				c.get(unreachable, warns::add);
			} catch (Exception e) {
				// expected
			}
		}
		T.eq(1, attempts[0], "one attempt for the whole cycle");
		try {
			c.get(unreachable, warns::add);
			throw new AssertionError("cached failure expected");
		} catch (SnapshotCache.CachedFailureException e) {
			T.check(UniFiFailures.isUnreachable(e),
					"the cached copy of an unreachable failure is DOWN");
			T.eq(null, e.getCause(), "no cause chain on the cached copy");
			T.check(e.getMessage().contains("Connection refused"),
					"the message names the original: " + e.getMessage());
		} catch (Exception e) {
			throw new AssertionError("wrong failure " + e);
		}
		T.eq(1, warns.size(), "one WARN");
		clock.now += 5 * MIN;
		c.beginCycle();
		try {
			c.get(unreachable, warns::add);
		} catch (Exception e) {
			// expected
		}
		T.eq(2, attempts[0], "tried again the next cycle");
		T.eq(0, ctl.logins, "unused");
	}

	/** Even after the short back-off, the same cycle is not retried. */
	static void failureHoldsForTheRestOfTheCycle() {
		FakeClock clock = new FakeClock();
		FakeController ctl = new FakeController();
		ctl.loginStatus = 500;
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		c.beginCycle();
		try {
			c.get(ctl::build, warns::add);
		} catch (Exception e) {
			// expected
		}
		clock.now += 10 * MIN; // a very long cycle
		try {
			c.get(ctl::build, warns::add);
			throw new AssertionError("cached failure expected");
		} catch (SnapshotCache.CachedFailureException e) {
			// expected
		} catch (Exception e) {
			throw new AssertionError("wrong failure " + e);
		}
		T.eq(1, ctl.logins, "no second build in the same cycle");
	}

	/** Stop Collecting is not a controller failure. */
	static void interruptIsNotCached() throws Exception {
		FakeClock clock = new FakeClock();
		SnapshotCache<String> c = cache(clock);
		List<String> warns = new ArrayList<>();
		c.beginCycle();
		try {
			c.get(() -> {
				throw new InterruptedException("stop");
			}, warns::add);
			throw new AssertionError("interrupt expected");
		} catch (InterruptedException expected) {
			// expected
		}
		T.eq("ok", c.get(() -> "ok", warns::add), "next call builds");
		T.eq(0, warns.size(), "no WARN for an interrupt");
	}
}
