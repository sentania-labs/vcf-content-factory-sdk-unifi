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
	 * Peer review 7: the radio code ({@code ng}, {@code na}, {@code 6e}) to
	 * use in a radio identifier, or {@code null} when it is missing or
	 * blank (every such radio would collapse onto {@code <mac>_}).
	 */
	static String radioCode(String raw) {
		if (raw == null || raw.trim().isEmpty()) return null;
		return raw;
	}

	/** A gateway's {@code type}: UDM, USG or UXG. */
	static boolean isGatewayType(String type) {
		return "udm".equals(type) || "ugw".equals(type) || "uxg".equals(type);
	}

	/**
	 * Peer review 6, Codex P2: whether discovery registers this
	 * {@code stat/device} record, and so whether the site's Device Count
	 * counts it (one rule for both): a gateway, switch ({@code usw}) or
	 * access point ({@code uap}) with a MAC that is not pending adoption.
	 * Other types (for example a camera or a power device the Network
	 * application lists), MAC-less records and a device whose
	 * {@code adopted} is {@code false} (it has no statistics, so it would
	 * publish properties only) are not discovered. A record without the
	 * {@code adopted} field counts as adopted.
	 *
	 * @param adopted the record's {@code adopted}, null when absent
	 */
	static boolean discoveredDevice(String type, String rawMac, Boolean adopted) {
		boolean kind = isGatewayType(type) || "usw".equals(type) || "uap".equals(type);
		return kind && mac(rawMac) != null && !Boolean.FALSE.equals(adopted);
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

	/**
	 * Build 18 (issue #27, item 4): the display name of a generic
	 * per-parent object, {@code "<label> (<parent>)"}, the form Broadcom's
	 * own adapters use ("Storage Pool (esx01...)"), so "Port 3" or "WAN 1"
	 * can be told apart in search, lists and alerts. {@code label} alone
	 * when the parent name is missing or blank. A display name only: the
	 * resource's identifier, and so its identity and history, is unchanged.
	 */
	static String childName(String label, String parentName) {
		if (parentName == null || parentName.trim().isEmpty()) return label;
		return label + " (" + parentName.trim() + ")";
	}
}
