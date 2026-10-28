package com.vcfcf.adapters.unifi;

/**
 * Build 18 review (N2, N3): a problem logs once per streak of failing
 * cycles, and again after a clean cycle. Run from the repo root
 * (ci/run_java_tests.sh).
 */
public final class StreakLogTest {

	public static void main(String[] args) {
		oncePerStreak();
		cleanCycleRearms();
		keysAreIndependent();
		failureBeforeFirstCycle();
		System.out.println("StreakLogTest: all assertions passed");
	}

	static void oncePerStreak() {
		StreakLog s = new StreakLog();
		s.cycleStart();
		T.check(s.fail("rename"), "first failure logs");
		T.check(!s.fail("rename"), "second failure in the same cycle is quiet");
		s.cycleStart();
		T.check(!s.fail("rename"), "failing again next cycle is the same streak");
		s.cycleStart();
		T.check(!s.fail("rename"), "still the same streak");
	}

	static void cleanCycleRearms() {
		StreakLog s = new StreakLog();
		s.cycleStart();
		T.check(s.fail("rename"), "first streak logs");
		s.cycleStart();          // cycle with no failure
		s.cycleStart();
		T.check(s.fail("rename"), "a later failure after a clean cycle logs again");
	}

	static void keysAreIndependent() {
		StreakLog s = new StreakLog();
		s.cycleStart();
		T.check(s.fail("nvr1|systemInfo"), "first key logs");
		T.check(s.fail("nvr1|recordingSpace"), "second key logs on its own");
		s.cycleStart();
		T.check(!s.fail("nvr1|systemInfo"), "first key still open");
		s.cycleStart();          // the first key failed last cycle, the second did not
		T.check(!s.fail("nvr1|systemInfo"), "first key's streak continues");
		T.check(s.fail("nvr1|recordingSpace"), "second key re-armed after its clean cycle");
	}

	/** onDiscover can run before the first collect cycle. */
	static void failureBeforeFirstCycle() {
		StreakLog s = new StreakLog();
		T.check(s.fail("rename"), "a failure before any cycle logs");
		s.cycleStart();
		T.check(!s.fail("rename"), "and the first cycle continues that streak");
	}
}
