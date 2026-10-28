package com.vcfcf.adapters.unifi;

/**
 * Build 18 (issue #20, second half): whether this cycle's relationships
 * leave the NVR's site out, so one failed Protect read does not drop the
 * Site &gt; NVR edge and add it back on the next good cycle. No VCF Ops SDK
 * and no framework-jar dependency, so the plain-main tests drive it.
 *
 * <p><b>Why leaving the site out keeps the edge.</b> The framework's
 * {@code RelationshipBuilder.build()} sends one full child set per own
 * parent ({@code setRelationships}, clear first) and sends nothing for a
 * parent it was not given, so a parent left out keeps the children it had
 * (framework RelationshipBuilderTest). The NVR's site is the first site in
 * {@code /self/sites}; its full set carries the NVR only when Protect was
 * read. On a cycle without Protect, publishing that site would replace its
 * children with a set lacking the NVR. Leaving it out is the rule build 14
 * already applies to a site whose device list failed
 * ({@link SiteIsolation#emitsChildren}): that site's other edges are not
 * updated for the cycle either, and come back on the next good one. The
 * NVR &gt; Camera edges need nothing: the NVR is a parent only on a cycle that
 * read Protect, so without Protect its set is not sent and its cameras stay.
 *
 * <p><b>Only while an NVR may exist.</b> When the platform knows no NVR for
 * this instance there is no edge to keep, and the site is published as
 * usual (a controller without Protect, or a fresh instance). When the
 * platform's resource list cannot be read the site is held, the safe side.
 *
 * <p><b>Grace period.</b> A hold lasts at most {@link #GRACE_CYCLES}
 * consecutive cycles without Protect (an hour at the default 5-minute
 * interval; the same count as the NOT_EXISTING threshold), so an NVR
 * removed for good, or Protect uninstalled, does not freeze the site's
 * edges forever. After that the reduced set is published, as before build
 * 18. A cycle that reads Protect resets the count.
 */
final class ProtectEdgeHold {

	/** Consecutive cycles without Protect during which the site is held. */
	static final int GRACE_CYCLES = 12;

	enum Decision {
		/** Protect was read, or no NVR to keep: publish as usual. */
		PUBLISH,
		/** Leave the NVR's site out of this cycle's relationships. */
		HOLD,
		/** The grace period has passed: publish without the NVR edge. */
		EXPIRED
	}

	private int unread;

	/**
	 * One cycle's decision. Call once per relationship build.
	 *
	 * @param protectRead      the Protect bootstrap was read with an NVR
	 *                         that has a MAC (its edges can be built)
	 * @param platformKnowsNvr whether the platform holds a UniFi NVR for this
	 *                         instance; {@code null} when that could not be
	 *                         read. Ignored when {@code protectRead}.
	 */
	synchronized Decision cycle(boolean protectRead, Boolean platformKnowsNvr) {
		if (protectRead) {
			unread = 0;
			return Decision.PUBLISH;
		}
		if (Boolean.FALSE.equals(platformKnowsNvr)) {
			unread = 0;
			return Decision.PUBLISH;
		}
		unread++;
		return unread <= GRACE_CYCLES ? Decision.HOLD : Decision.EXPIRED;
	}

	/** Consecutive cycles without Protect counted so far (for logs). */
	synchronized int unreadCycles() {
		return unread;
	}
}
