package com.vcfcf.adapters.unifi;

/**
 * Build 15: value decisions for published metrics. No VCF Ops SDK and no
 * framework-jar dependency (the adapter hands over the field's text,
 * {@code SimpleJson.asString()}, {@code null} when the field is absent), so
 * the plain-main tests drive exactly the logic the adapter runs.
 *
 * <p><b>Issue #14: absent is not zero.</b> The framework's
 * {@code SimpleJson.asDouble()} returns 0.0 for a missing or unparseable
 * field, so an offline device or a field the firmware does not send was
 * published as a real 0 (0% CPU, 0 clients, 0 satisfaction). Every value
 * here is {@code null} when it cannot be read, and the adapter publishes
 * nothing for a {@code null}.
 */
final class UniFiValues {

	private UniFiValues() {}

	/**
	 * The field as a number, or {@code null} when it is absent, blank, not a
	 * number (a boolean, an object, free text) or not finite. UniFi sends
	 * some numbers as strings ({@code "23.8"}); those parse.
	 */
	static Double number(String raw) {
		if (raw == null) return null;
		String t = raw.trim();
		if (t.isEmpty()) return null;
		// Double.parseDouble also takes "NaN", "Infinity", hex and a
		// trailing type suffix; only plain decimal numbers count.
		if (!t.matches("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?")) return null;
		try {
			double d = Double.parseDouble(t);
			return Double.isFinite(d) ? d : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * A satisfaction score (0 to 100), or {@code null} when absent or
	 * negative: UniFi reports -1 when it has no data, which is not a score.
	 */
	static Double satisfaction(String raw) {
		Double d = number(raw);
		return d == null || d < 0 ? null : d;
	}

	/**
	 * Whether a Network device is connected, from its {@code state} field
	 * (1 = connected in the UniFi device API; any other value, such as 0
	 * disconnected, 4 upgrading or 5 provisioning, is not). {@code null}
	 * when the field is absent or not a number: unknown, never assumed
	 * either way.
	 */
	static Boolean connected(String rawState) {
		Double d = number(rawState);
		if (d == null) return null;
		return d == 1.0;
	}

	/** {@code Status|state}: 1 connected, 0 not, {@code null} unknown. */
	static Double stateMetric(Boolean connected) {
		if (connected == null) return null;
		return connected ? 1.0 : 0.0;
	}

	/**
	 * The states in which a device is informing the controller, so its
	 * values are current: connected (1), upgrading (4), provisioning (5),
	 * adopting (7). Review round 3: an allow-list, so a code not listed
	 * (0 disconnected, 2 pending adoption, 6 heartbeat missed, 9 inform
	 * error, and any code this pack does not know, such as 10 or 11) skips
	 * the metrics instead of republishing stored values. Code meanings:
	 * see docs/overview.md "Device state codes" (community-sourced except
	 * 0 and 1).
	 */
	static final java.util.Set<Integer> INFORMING_STATES =
			java.util.Collections.unmodifiableSet(new java.util.HashSet<>(
					java.util.Arrays.asList(1, 4, 5, 7)));

	/**
	 * Whether a device's performance metrics (and those of its ports, radios
	 * and WAN interfaces) are published, from its raw {@code state}: only
	 * in an {@link #INFORMING_STATES} state. In any other state the
	 * controller holds last values or none, so nothing is published. An
	 * absent state (or one that is not a whole number) is unknown and
	 * publishes what is present, as before.
	 */
	static boolean publishesPerformance(String rawState) {
		Integer code = stateCode(rawState);
		return code == null || INFORMING_STATES.contains(code);
	}

	/**
	 * {@code Status|state_code}: the device state's short name (0
	 * disconnected, 1 connected, 2 pending adoption, 4 upgrading, 5
	 * provisioning, 6 heartbeat missed, 7 adopting, 9 inform error), any
	 * other code as its number, {@code null} when the state is absent or not
	 * a whole number.
	 */
	static String stateName(String rawState) {
		Integer code = stateCode(rawState);
		if (code == null) return null;
		switch (code) {
			case 0: return "disconnected";
			case 1: return "connected";
			case 2: return "pending adoption";
			case 4: return "upgrading";
			case 5: return "provisioning";
			case 6: return "heartbeat missed";
			case 7: return "adopting";
			case 9: return "inform error";
			default: return String.valueOf(code);
		}
	}

	/** The state as a whole number, or null. */
	private static Integer stateCode(String rawState) {
		Double d = number(rawState);
		if (d == null || d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
			return null;
		}
		return d.intValue();
	}

	/**
	 * Build 16 (issue #30): {@code PoE|poe_budget_used_pct}, the share of
	 * the switch's PoE budget drawn, as a percentage: consumption divided
	 * by {@code total_max_power}, times 100. {@code null} (nothing
	 * published) when the maximum is absent or not above 0 (a non-PoE
	 * switch reports none, or 0) or when no PoE port reported its draw:
	 * never 0 and never NaN for a switch that has no budget to use. A draw
	 * above the budget reads above 100, as reported.
	 */
	static Double poeBudgetUsedPct(Double consumption, Double maxPower) {
		if (consumption == null || maxPower == null || !(maxPower > 0)) return null;
		double pct = consumption / maxPower * 100.0;
		return Double.isFinite(pct) ? pct : null;
	}

	/**
	 * Build 17 (review B1/W2 on build 16): a boolean field as the property
	 * text {@code "true"} or {@code "false"}, or {@code null} (nothing
	 * published) when the field is absent or not a boolean. Before build 17
	 * the adapter used {@code SimpleJson.asBoolean()}, which reads an absent
	 * field as false, so a field the controller did not send published
	 * "false" and could raise Uplink Port Down, Camera Offline or Camera Not
	 * Recording. Accepts a JSON boolean, the strings true/false in any case,
	 * and a number (0 false, any other true), the shapes asBoolean accepted.
	 */
	static String flag(String raw) {
		if (raw == null) return null;
		String t = raw.trim();
		if ("true".equalsIgnoreCase(t)) return "true";
		if ("false".equalsIgnoreCase(t)) return "false";
		Double d = number(t);
		if (d == null) return null;
		return d != 0.0 ? "true" : "false";
	}

	/**
	 * Build 17 (review W1 on build 16): a Protect camera's recording mode
	 * ({@code recordingSettings.mode}) as sent, trimmed, or {@code null}
	 * when absent or blank. Not normalized or validated against a list, so
	 * a mode a newer Protect adds still publishes.
	 */
	static String text(String raw) {
		if (raw == null) return null;
		String t = raw.trim();
		return t.isEmpty() ? null : t;
	}

	/**
	 * Build 15 (issue #16): seconds from an epoch-millisecond timestamp
	 * (Protect's {@code lastMotion}) to {@code nowMs}, or {@code null} when
	 * the timestamp is absent or not positive (never had motion). A
	 * timestamp ahead of the collector's clock reads 0, not a negative age.
	 */
	static Double secondsSince(Double epochMs, long nowMs) {
		if (epochMs == null || epochMs <= 0) return null;
		return Math.max(0.0, (nowMs - epochMs) / 1000.0);
	}

	/**
	 * Build 15 (issue #16): a duration in milliseconds (Protect's
	 * {@code recordingRetentionDurationMs}) in days, or {@code null} when
	 * absent or negative. Fractions are kept (36 hours is 1.5).
	 */
	static Double millisToDays(Double ms) {
		if (ms == null || ms < 0) return null;
		return ms / 86_400_000.0;
	}

	/** Build 18 review N3: {@code Storage|source} when the volume pair fed the metrics. */
	static final String STORAGE_VOLUME = "systemInfo";
	/** Build 18 review N3: {@code Storage|source} when Protect's recording share did. */
	static final String STORAGE_RECORDING = "recordingSpace";

	/** One NVR storage reading: which source, and its total and used in bytes. */
	static final class NvrStorage {
		final String source;
		final double total;
		final double used;

		NvrStorage(String source, double total, double used) {
			this.source = source;
			this.total = total;
			this.used = used;
		}
	}

	/**
	 * Build 18 (issue #39): the NVR's storage in bytes and the source it came
	 * from, or {@code null} when neither source gives a usable pair. The
	 * Protect bootstrap (UDM Pro, Protect 7.3.70, devel 2026-10-28) has no
	 * {@code storageInfo}; storage is under {@code nvr.systemInfo.storage}
	 * ({@code size}, {@code used}, {@code available}: the recording volume,
	 * the same numbers the console lists as the primary space's
	 * {@code total_bytes} / {@code used_bytes}) and
	 * {@code nvr.storageStats.recordingSpace} ({@code total}, {@code used},
	 * {@code available}: Protect's share of it). The volume is read first,
	 * the recording space only when the volume pair is not usable. A total
	 * and a used from different sources are never combined; a pair with a
	 * total of 0 or less, a negative used, or (review N3) a used above the
	 * total is unusable, see {@link #usedExceedsTotal}.
	 */
	static NvrStorage nvrStorage(Double volumeSize, Double volumeUsed,
			Double recordingTotal, Double recordingUsed) {
		if (usablePair(volumeSize, volumeUsed)) {
			return new NvrStorage(STORAGE_VOLUME, volumeSize, volumeUsed);
		}
		if (usablePair(recordingTotal, recordingUsed)) {
			return new NvrStorage(STORAGE_RECORDING, recordingTotal, recordingUsed);
		}
		return null;
	}

	/**
	 * Build 18 review N3: a pair that is otherwise usable but reports more
	 * used than total. It is rejected (neither number is published from it,
	 * so {@code usage_pct} cannot pass 100); the adapter logs it once per
	 * streak.
	 */
	static boolean usedExceedsTotal(Double total, Double used) {
		return total != null && used != null && total > 0 && used > total;
	}

	private static boolean usablePair(Double total, Double used) {
		return total != null && used != null && total > 0 && used >= 0
				&& used <= total;
	}
}
