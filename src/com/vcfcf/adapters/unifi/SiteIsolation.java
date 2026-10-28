package com.vcfcf.adapters.unifi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Build 14 (issue #19, review W2): the decisions that keep a site whose
 * {@code stat/device} failed from publishing defaults or dropping edges.
 * No VCF Ops SDK and no framework-jar dependency (generic over the device
 * record type), so the plain-main tests drive exactly the logic the adapter
 * runs; {@code UniFiAdapter.Snapshot} and {@code buildRelationships}
 * delegate here.
 *
 * <p>{@code failedSites} maps a site name to a one-line reason (from
 * {@link SiteReads.Result#failed}); empty when every site was read.
 *
 * <p><b>A device that moved between sites (review NIT-3).</b> Identity is
 * the MAC, not the site, so a device found on any readable site collects
 * normally. Its relationships can be briefly wrong until the failed site
 * reads again: moved from a failed site to a readable one, it is a child of
 * both (the failed site is not emitted as a parent, so its stored edge
 * stays); moved from a readable site into a failed one, the readable
 * site's full set no longer lists it and the failed site is not emitted, so
 * it has no site parent, and its own collect is ERROR because it is not in
 * the snapshot. Both heal on the first cycle the failed site is read.
 */
final class SiteIsolation {

	private SiteIsolation() {}

	/**
	 * The device with this MAC, or {@code null} when it is not in the
	 * snapshot and every site was read (it is gone from the controller).
	 * When a site could not be read this cycle, a device that is not found
	 * may be on that site, so it is unreadable, not absent: this throws and
	 * the resource goes to ERROR instead of collecting defaults.
	 */
	static <D> D findDevice(Map<String, D> deviceByMac,
			Map<String, String> failedSites, String mac) throws IOException {
		D d = deviceByMac.get(mac);
		if (d == null && !failedSites.isEmpty()) {
			throw new IOException("UniFi device " + mac + " was not read "
					+ "this cycle: stat/device failed for site(s) "
					+ failedSites.keySet() + " ("
					+ failedSites.values().iterator().next() + ")");
		}
		return d;
	}

	/** Throws when this site was not read this cycle (its resources are ERROR). */
	static void requireSiteRead(Map<String, String> failedSites,
			String siteName) throws IOException {
		String reason = failedSites.get(siteName);
		if (reason != null) {
			throw new IOException("UniFi site " + siteName + " was not "
					+ "read this cycle: stat/device failed (" + reason + ")");
		}
	}

	/**
	 * Whether this site is emitted as a parent of its devices and Wireless
	 * Summary this cycle. Own-adapter parents are full-set (the platform
	 * replaces a parent's children with what is emitted), so a site that
	 * was not read must not be emitted at all: emitting it with no devices
	 * would drop its device edges. Not emitting it keeps the edges it has.
	 * The World to site edge is emitted for every site regardless.
	 */
	static boolean emitsChildren(Map<String, String> failedSites,
			String siteName) {
		return !failedSites.containsKey(siteName);
	}

	/**
	 * Review R5-N1: a {@code /self/sites} row has an identity only with a
	 * non-blank {@code name}; a nameless row is never a Site, a
	 * {@code stat/device} target or an NVR parent.
	 */
	static boolean hasName(String siteName) {
		return siteName != null && !siteName.trim().isEmpty();
	}

	/** Review R5-N1: {@code siteNames} without the nameless rows, in order. */
	static List<String> named(List<String> siteNames) {
		List<String> out = new ArrayList<>();
		for (String n : siteNames) {
			if (hasName(n)) out.add(n);
		}
		return out;
	}

	/**
	 * The site the Protect NVR is emitted under (v1 heuristic: the first
	 * site), or {@code null} when there are no sites or the first site was
	 * not read this cycle (the NVR keeps its stored site edge; its cameras
	 * are still emitted under it).
	 */
	static String nvrParentSite(List<String> siteNames,
			Map<String, String> failedSites) {
		if (siteNames.isEmpty()) return null;
		String first = siteNames.get(0);
		return emitsChildren(failedSites, first) ? first : null;
	}
}
