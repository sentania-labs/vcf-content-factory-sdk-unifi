package com.vcfcf.adapters.unifi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Build 15 (peer review 4): stat/sysinfo is not asked again for 15 minutes
 * after an authentication-class refusal (one WARN), is retried next build
 * after any other failure, and asks again (one INFO) once the window has
 * passed. Run from the repo root (ci/run_java_tests.sh).
 */
public final class SysinfoGateTest {

	private static final long MIN = 60_000L;

	public static void main(String[] args) {
		refusalBacksOff();
		otherFailureRetriesNextBuild();
		clockSteppedBackAsksAgain();
		System.out.println("SysinfoGateTest: all assertions passed");
	}

	static void refusalBacksOff() {
		AtomicLong now = new AtomicLong(0);
		SysinfoGate g = new SysinfoGate(now::get, 15 * MIN);
		List<String> warn = new ArrayList<>(), info = new ArrayList<>();
		T.check(g.shouldAsk(info::add), "asks the first build");
		g.failed(new UniFiHttpException("UniFi GET returned HTTP 403", 403, false),
				warn::add, info::add);
		T.eq(1, warn.size(), "one WARN when the window starts");
		T.check(warn.get(0).contains("403"), warn.get(0));
		for (long t = 5; t < 15; t += 5) {
			now.set(t * MIN);
			T.check(!g.shouldAsk(info::add), "not asked at " + t + " min");
		}
		now.set(14 * MIN + 59_999);
		T.check(!g.shouldAsk(info::add), "not asked just under 15 min");
		T.eq(1, warn.size(), "no repeated WARN while cooling down");
		T.eq(0, info.size(), "no INFO while cooling down");
		now.set(15 * MIN);
		T.check(g.shouldAsk(info::add), "asked again at 15 min");
		T.eq(1, info.size(), "one INFO when the window has passed");
		T.check(g.shouldAsk(info::add) && info.size() == 1, "no repeated INFO");
		g.failed(new UniFiHttpException("HTTP 401", 401, false), warn::add, info::add);
		T.eq(2, warn.size(), "a new refusal starts a new window");
		T.check(g.coolingDown(), "cooling down");
	}

	static void otherFailureRetriesNextBuild() {
		SysinfoGate g = new SysinfoGate(() -> 0L, 15 * MIN);
		List<String> warn = new ArrayList<>(), info = new ArrayList<>();
		g.failed(new IOException("connect timed out"), warn::add, info::add);
		T.eq(0, warn.size(), "no WARN for a non-refusal");
		T.eq(1, info.size(), "one INFO");
		T.check(g.shouldAsk(info::add), "retried on the next build");
		g.failed(new UniFiHttpException("HTTP 500", 500, false), warn::add, info::add);
		T.check(g.shouldAsk(info::add), "a 500 is not a refusal window");
	}

	static void clockSteppedBackAsksAgain() {
		AtomicLong now = new AtomicLong(100 * MIN);
		SysinfoGate g = new SysinfoGate(now::get, 15 * MIN);
		g.failed(new UniFiHttpException("HTTP 403", 403, false), m -> { }, m -> { });
		now.set(10 * MIN);
		T.check(g.shouldAsk(m -> { }), "a clock stepped back before the window "
				+ "start does not keep the window open for an hour and a half");
	}
}
