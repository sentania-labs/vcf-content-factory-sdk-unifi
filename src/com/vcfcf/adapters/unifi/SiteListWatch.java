package com.vcfcf.adapters.unifi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Build 18 review R3-N3: the sites the last read site list carried, so a
 * site that drops out of {@code /self/sites} is named once, at WARN, when it
 * first goes missing. A site leaving the list is intended removal (deleted,
 * or the account's access narrowed; the API cannot tell them apart), and it
 * is the largest removal this pack makes: the site and its whole device tree
 * start counting misses toward NOT_EXISTING. The framework logs only when it
 * marks, twelve cycles later, so without this the count starts silently.
 *
 * <p>One per configuration (held by {@code Configured}): the first read after
 * a configure only records. Fed only a list that was read in full (at least
 * one site, {@code meta.rc} ok), so an empty or failed list neither warns
 * nor forgets the last good one. No VCF Ops SDK dependency; the plain-main
 * test drives it. Synchronized: a discovery pass may enumerate on another
 * thread.
 */
final class SiteListWatch {

	/** Site name to display name from the last read list; null before one. */
	private Map<String, String> last;

	/**
	 * Records this cycle's site list and returns the sites the previous one
	 * carried that this one does not (name to the last display name). Empty
	 * on the first call.
	 */
	synchronized Map<String, String> update(Map<String, String> listed) {
		Map<String, String> gone = new LinkedHashMap<>();
		if (last != null) {
			for (Map.Entry<String, String> e : last.entrySet()) {
				if (!listed.containsKey(e.getKey())) gone.put(e.getKey(), e.getValue());
			}
		}
		last = new LinkedHashMap<>(listed);
		return Collections.unmodifiableMap(gone);
	}
}
