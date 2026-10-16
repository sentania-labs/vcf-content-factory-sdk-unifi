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
	 * Whether a device's performance metrics (and those of its ports, radios
	 * and WAN interfaces) are published: not when it is known to be
	 * disconnected, since the controller then reports its last values or
	 * none. Unknown state publishes what is present.
	 */
	static boolean publishesPerformance(Boolean connected) {
		return !Boolean.FALSE.equals(connected);
	}
}
