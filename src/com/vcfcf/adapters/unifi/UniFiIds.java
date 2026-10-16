package com.vcfcf.adapters.unifi;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Build 15: identity and path guards shared by discovery, relationships, the
 * snapshot index and the API client. No VCF Ops SDK and no framework-jar
 * dependency (plain strings in, plain values out), so the plain-main tests
 * drive exactly the logic the adapter runs.
 *
 * <p><b>Issue #23.</b> A device or camera whose {@code mac} is missing or
 * blank, and a switch port whose {@code port_idx} is missing or not a whole
 * number, has no identity: {@link #mac} and {@link #portIdx} return
 * {@code null} and every caller skips it. Before build 15 discovery and
 * relationships used the raw value, so such a device registered with an
 * empty identifier and every such port collapsed onto {@code <mac>_0}, while
 * the snapshot index already skipped a blank MAC. One guard keeps the three
 * consistent.
 *
 * <p><b>Issue #22.</b> {@link #pathSegment} percent-encodes a site name as one
 * URL path segment, so a reserved character in a name returned by
 * {@code /self/sites} cannot change which path is requested.
 */
final class UniFiIds {

	private UniFiIds() {}

	/**
	 * The MAC to use as an identifier, or {@code null} when it is missing or
	 * blank. A usable MAC is returned exactly as the controller sent it, so
	 * existing resource keys do not change.
	 */
	static String mac(String raw) {
		if (raw == null || raw.trim().isEmpty()) return null;
		return raw;
	}

	/**
	 * The port index, or {@code null} when it is missing or not a whole
	 * number. The JSON parser hands an integer over as {@code "3"} and a
	 * decimal as {@code "3.0"}; both are 3. Never defaults to 0.
	 */
	static Integer portIdx(String raw) {
		if (raw == null) return null;
		String t = raw.trim();
		if (t.isEmpty()) return null;
		try {
			return Integer.valueOf(t);
		} catch (NumberFormatException e) {
			// fall through to the decimal form
		}
		try {
			double d = Double.parseDouble(t);
			if (Double.isFinite(d) && d == Math.rint(d)
					&& d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
				return (int) d;
			}
		} catch (NumberFormatException e) {
			// not a number
		}
		return null;
	}

	/**
	 * Percent-encodes {@code raw} as one URL path segment (UTF-8; a space is
	 * {@code %20}, never {@code +}; {@code /}, {@code ?}, {@code #} and
	 * {@code %} are encoded). A name of letters, digits, {@code -},
	 * {@code _}, {@code .} and {@code *} is unchanged, so the usual site
	 * names ({@code default}, {@code abc123de}) request the same path as
	 * before.
	 */
	static String pathSegment(String raw) {
		if (raw == null) return "";
		return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
