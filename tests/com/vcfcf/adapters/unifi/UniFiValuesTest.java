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
		poeBudgetUsedPct();
		flagAbsentIsNotFalse();
		recordingModeText();
		nvrStorage();
		nvrStorageUsedOverTotal();
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
		// Review round 3: an allow-list. Pin the set and every skip.
		T.eq(new java.util.HashSet<>(java.util.Arrays.asList(1, 4, 5, 7)),
				UniFiValues.INFORMING_STATES, "the informing states");
		for (String code : new String[]{"0", "2", "3", "6", "8", "9", "10",
				"11", "12", "-1", "99"}) {
			T.check(!UniFiValues.publishesPerformance(code),
					"state " + code + " skips metrics");
		}
		for (String code : new String[]{"1", "4", "5", "7", "4.0"}) {
			T.check(UniFiValues.publishesPerformance(code),
					"state " + code + " publishes");
		}
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

	/** #30: share of the PoE budget drawn; nothing for a switch with no budget. */
	static void poeBudgetUsedPct() {
		T.eq(50.0, UniFiValues.poeBudgetUsedPct(30.0, 60.0), "half the budget");
		T.eq(87.5, UniFiValues.poeBudgetUsedPct(105.0, 120.0), "87.5 %");
		T.eq(0.0, UniFiValues.poeBudgetUsedPct(0.0, 60.0),
				"a PoE switch drawing nothing reads a real 0");
		T.eq(100.0, UniFiValues.poeBudgetUsedPct(60.0, 60.0), "fully used");
		T.eq(150.0, UniFiValues.poeBudgetUsedPct(90.0, 60.0),
				"over budget reads above 100, as reported");
		T.eq(null, UniFiValues.poeBudgetUsedPct(10.0, null),
				"no total_max_power (non-PoE switch): not published");
		T.eq(null, UniFiValues.poeBudgetUsedPct(0.0, 0.0),
				"total_max_power 0: not published, never NaN");
		T.eq(null, UniFiValues.poeBudgetUsedPct(10.0, 0.0),
				"total_max_power 0: not published, never Infinity");
		T.eq(null, UniFiValues.poeBudgetUsedPct(10.0, -5.0),
				"negative total_max_power: not published");
		T.eq(null, UniFiValues.poeBudgetUsedPct(null, 60.0),
				"no port reported its draw: not published");
		T.eq(null, UniFiValues.poeBudgetUsedPct(null, null), "neither");
		T.eq(null, UniFiValues.poeBudgetUsedPct(10.0, Double.NaN), "NaN max");
		T.eq(null, UniFiValues.poeBudgetUsedPct(1e308, 1e-308),
				"overflow is not published");
	}

	/** Build 17 (review W2): an absent boolean publishes nothing, never "false". */
	static void flagAbsentIsNotFalse() {
		T.eq(null, UniFiValues.flag(null), "absent field: nothing published");
		T.eq(null, UniFiValues.flag(""), "empty string");
		T.eq(null, UniFiValues.flag("  "), "blank string");
		T.eq(null, UniFiValues.flag("yes"), "free text is unreadable, not false");
		T.eq(null, UniFiValues.flag("{up=true}"), "object");
		T.eq(null, UniFiValues.flag("NaN"), "NaN");
		T.eq("true", UniFiValues.flag("true"), "JSON true");
		T.eq("false", UniFiValues.flag("false"), "JSON false is a real false");
		T.eq("true", UniFiValues.flag("TRUE"), "string, any case");
		T.eq("false", UniFiValues.flag(" False "), "padded string");
		T.eq("true", UniFiValues.flag("1"), "number 1");
		T.eq("false", UniFiValues.flag("0"), "number 0");
		T.eq("true", UniFiValues.flag("2"), "non-zero number, as asBoolean read it");
	}

	/** Build 17 (review W1): recording mode as sent; absent or blank, nothing. */
	static void recordingModeText() {
		T.eq("never", UniFiValues.text("never"), "never");
		T.eq("always", UniFiValues.text(" always "), "trimmed");
		T.eq("detections", UniFiValues.text("detections"), "kept as sent");
		T.eq(null, UniFiValues.text(null), "absent");
		T.eq(null, UniFiValues.text(""), "empty");
		T.eq(null, UniFiValues.text("   "), "blank");
	}

	/**
	 * Build 18 (issue #39): numbers from the devel UDM Pro's Protect
	 * bootstrap, 2026-10-28 (Protect 7.3.70). systemInfo.storage: size
	 * 9912161972224, used 9822262308864 (available 89899663360; the three
	 * add up, and the console's primary space reports the same total as
	 * total_bytes, so bytes). storageStats.recordingSpace: total
	 * 9877802233856, used 9839457505280.
	 */
	static void nvrStorage() {
		UniFiValues.NvrStorage v = UniFiValues.nvrStorage(9912161972224.0, 9822262308864.0,
				9877802233856.0, 9839457505280.0);
		T.eq(9912161972224.0, v.total, "volume total first");
		T.eq(9822262308864.0, v.used, "volume used first");
		T.eq("systemInfo", v.source, "volume source named");
		T.check(Math.abs(v.used / v.total * 100.0 - 99.093) < 0.001, "usage 99.09 %");
		UniFiValues.NvrStorage r = UniFiValues.nvrStorage(null, null,
				9877802233856.0, 9839457505280.0);
		T.eq(9877802233856.0, r.total, "recording space when no volume pair");
		T.eq(9839457505280.0, r.used, "recording used");
		T.eq("recordingSpace", r.source, "recording source named");
		UniFiValues.NvrStorage mixed = UniFiValues.nvrStorage(9912161972224.0, null,
				null, 9839457505280.0);
		T.eq(null, mixed, "a total and a used from different sources are never combined");
		UniFiValues.NvrStorage half = UniFiValues.nvrStorage(9912161972224.0, null,
				9877802233856.0, 9839457505280.0);
		T.eq(9877802233856.0, half.total, "half a volume pair falls back whole");
		T.eq("recordingSpace", half.source, "and says so");
		T.eq(null, UniFiValues.nvrStorage(null, null, null, null), "nothing sent");
		T.eq(null, UniFiValues.nvrStorage(0.0, 0.0, null, null), "total 0 is unusable");
		T.eq(null, UniFiValues.nvrStorage(100.0, -1.0, null, null), "negative used");
		T.eq(0.0, UniFiValues.nvrStorage(100.0, 0.0, null, null).used, "empty disk is a real 0");
		T.eq(100.0, UniFiValues.nvrStorage(100.0, 100.0, null, null).used, "a full disk is usable");
	}

	/** Review N3: used above total is rejected, never a usage over 100 %. */
	static void nvrStorageUsedOverTotal() {
		T.eq(null, UniFiValues.nvrStorage(100.0, 101.0, null, null),
				"used above total publishes neither");
		UniFiValues.NvrStorage fb = UniFiValues.nvrStorage(100.0, 101.0, 80.0, 40.0);
		T.eq("recordingSpace", fb.source, "a rejected volume pair falls back whole");
		T.eq(80.0, fb.total, "recording total, not the volume's");
		T.eq(40.0, fb.used, "recording used, not the volume's");
		T.eq(null, UniFiValues.nvrStorage(null, null, 80.0, 81.0),
				"a rejected recording pair publishes neither");
		T.check(UniFiValues.usedExceedsTotal(100.0, 101.0), "flagged for the log");
		T.check(!UniFiValues.usedExceedsTotal(100.0, 100.0), "full is not flagged");
		T.check(!UniFiValues.usedExceedsTotal(null, 101.0), "missing total is not flagged");
		T.check(!UniFiValues.usedExceedsTotal(100.0, null), "missing used is not flagged");
		T.check(!UniFiValues.usedExceedsTotal(0.0, 1.0), "total 0 is unusable, not flagged");
	}
}
