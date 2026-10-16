package com.vcfcf.adapters.unifi;

/**
 * Build 15 (issue #14): absent is not zero, and device state. Run from the
 * repo root (ci/run_java_tests.sh).
 */
public final class UniFiValuesTest {

	public static void main(String[] args) {
		absentIsNotZero();
		numbersAndNumericStrings();
		satisfactionNoData();
		deviceState();
		secondsSinceLastMotion();
		retentionDays();
		System.out.println("UniFiValuesTest: all assertions passed");
	}

	/** A missing or unreadable field has no value (SimpleJson would say 0.0). */
	static void absentIsNotZero() {
		T.eq(null, UniFiValues.number(null), "absent field");
		T.eq(null, UniFiValues.number(""), "empty string");
		T.eq(null, UniFiValues.number("  "), "blank string");
		T.eq(null, UniFiValues.number("true"), "boolean");
		T.eq(null, UniFiValues.number("{cpu=1}"), "object");
		T.eq(null, UniFiValues.number("n/a"), "free text");
		T.eq(null, UniFiValues.number("NaN"), "NaN");
		T.eq(null, UniFiValues.number("Infinity"), "Infinity");
		T.eq(null, UniFiValues.number("0x10"), "hex");
		T.eq(null, UniFiValues.number("5d"), "type suffix");
	}

	/** Real values, zero included, are published as they are. */
	static void numbersAndNumericStrings() {
		T.eq(0.0, UniFiValues.number("0"), "a real zero is kept");
		T.eq(23.8, UniFiValues.number("23.8"), "UDM string cpu");
		T.eq(4.9, UniFiValues.number("4.9"), "AP numeric cpu");
		T.eq(503712.0, UniFiValues.number("503712"), "integer");
		T.eq(-3.5, UniFiValues.number("-3.5"), "negative");
		T.eq(1.5e9, UniFiValues.number("1.5E9"), "exponent");
		T.eq(4.22, UniFiValues.number(" 4.22 "), "padded PoE string");
	}

	/** -1 is UniFi's "no data", not a score. */
	static void satisfactionNoData() {
		T.eq(null, UniFiValues.satisfaction("-1"), "no data");
		T.eq(null, UniFiValues.satisfaction(null), "absent");
		T.eq(0.0, UniFiValues.satisfaction("0"), "real zero");
		T.eq(97.0, UniFiValues.satisfaction("97"), "score");
	}

	/** 1 connected, anything else not, absent unknown. */
	static void deviceState() {
		T.eq(Boolean.TRUE, UniFiValues.connected("1"), "connected");
		T.eq(Boolean.FALSE, UniFiValues.connected("0"), "disconnected");
		T.eq(Boolean.FALSE, UniFiValues.connected("4"), "upgrading");
		T.eq(Boolean.FALSE, UniFiValues.connected("5"), "provisioning");
		T.eq(null, UniFiValues.connected(null), "absent state is unknown");
		T.eq(null, UniFiValues.connected("CONNECTED"), "non-numeric is unknown");

		T.eq(1.0, UniFiValues.stateMetric(Boolean.TRUE), "metric 1");
		T.eq(0.0, UniFiValues.stateMetric(Boolean.FALSE), "metric 0");
		T.eq(null, UniFiValues.stateMetric(null), "no metric when unknown");

		T.check(UniFiValues.publishesPerformance("1"), "connected");
		T.check(!UniFiValues.publishesPerformance("0"),
				"disconnected: state only");
		T.check(!UniFiValues.publishesPerformance("6"),
				"heartbeat missed: state only");
		T.check(UniFiValues.publishesPerformance("4"),
				"peer review 5: an upgrading device still publishes its metrics");
		T.check(UniFiValues.publishesPerformance("5"), "provisioning publishes");
		T.check(UniFiValues.publishesPerformance("7"), "adopting publishes");
		T.check(UniFiValues.publishesPerformance(null),
				"unknown: present fields only");
		T.check(UniFiValues.publishesPerformance("CONNECTED"),
				"non-numeric: unknown, present fields only");

		// Status|state stays 1/0 connected for an upgrading device.
		T.eq(0.0, UniFiValues.stateMetric(UniFiValues.connected("4")),
				"upgrading reads 0 on Status|state");
		// Status|state_code names it.
		String[][] names = {{"0", "disconnected"}, {"1", "connected"},
				{"2", "pending adoption"}, {"4", "upgrading"},
				{"5", "provisioning"}, {"6", "heartbeat missed"},
				{"7", "adopting"}, {"9", "inform error"}, {"11", "11"},
				{"3.0", "3"}};
		for (String[] n : names) {
			T.eq(n[1], UniFiValues.stateName(n[0]), "state_code for " + n[0]);
		}
		T.eq(null, UniFiValues.stateName(null), "absent: no state_code");
		T.eq(null, UniFiValues.stateName("1.5"), "not a whole number");
		T.eq(null, UniFiValues.stateName("x"), "not a number");
	}

	/** #16: an age in seconds, not the epoch value divided by 1000. */
	static void secondsSinceLastMotion() {
		long now = 1_760_000_000_000L;
		T.eq(90.0, UniFiValues.secondsSince(now - 90_000.0, now), "90 s ago");
		T.eq(0.0, UniFiValues.secondsSince((double) now, now), "just now");
		T.eq(0.0, UniFiValues.secondsSince(now + 5_000.0, now),
				"clock skew reads 0, not negative");
		T.eq(null, UniFiValues.secondsSince(null, now), "absent");
		T.eq(null, UniFiValues.secondsSince(0.0, now), "never had motion");
	}

	/** #16: retention as a number of days. */
	static void retentionDays() {
		T.eq(30.0, UniFiValues.millisToDays(2_592_000_000.0), "30 days");
		T.eq(1.5, UniFiValues.millisToDays(129_600_000.0), "36 hours");
		T.eq(0.0, UniFiValues.millisToDays(0.0), "0 is a real value");
		T.eq(null, UniFiValues.millisToDays(null), "absent");
		T.eq(null, UniFiValues.millisToDays(-1.0), "negative");
	}
}
