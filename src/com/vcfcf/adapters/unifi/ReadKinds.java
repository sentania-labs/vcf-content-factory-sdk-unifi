package com.vcfcf.adapters.unifi;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Build 18 (issue #20): which resource kinds one enumeration read in full,
 * for the framework's {@code kindsReadThisCycle()} (buildkit 1.0.22). The
 * framework counts a miss, and after 12 consecutive misses marks the object
 * NOT_EXISTING, only for a kind named here, so a kind is named only when
 * nothing about its read failed this cycle. That a kind was delivered is not
 * enough: a site whose {@code stat/device} failed still leaves switches from
 * the other sites. A false mark is not undone (the platform deletes the
 * object, with its history, after the Deleted Objects retention), a missed
 * one only leaves a stale object, so every doubt leaves the kind out. No VCF
 * Ops SDK and no framework-jar dependency, so the plain-main tests drive the
 * exact rule the adapter runs.
 *
 * <p>The rule, per kind:
 * <ul>
 *   <li>{@code UniFiSite} and {@code UniFiWirelessAggregate} (one per site):
 *       whenever {@code /self/sites} was read as a list with at least one
 *       site (the snapshot build throws without a list). A site removed from
 *       the controller and its Wireless Summary are marked together. An
 *       empty site list is not proof that everything is gone (a controller
 *       always carries its default site; a Network app mid-restore, an
 *       account whose site access was emptied or a proxy stub can answer
 *       {@code []}), so it declares no kind at all (review R2-B1).</li>
 *   <li>{@code UniFiGateway}, {@code UniFiSwitch}, {@code UniFiAccessPoint}:
 *       only when every site's {@code stat/device} was read (no failed or
 *       cooling-down site, every payload a device list with
 *       {@code meta.rc} {@code ok}) and no gateway, switch or access point
 *       record lacked its MAC, and no device record lacked its
 *       {@code type} (it could have been any of the three). The hook is per kind,
 *       not per site, and a device can move between sites, so one unread
 *       site leaves every device kind out.</li>
 *   <li>{@code UniFiWanInterface}, {@code UniFiSwitchPort},
 *       {@code UniFiRadio}: as their device kinds, and also only when every
 *       registered device of that type is informing the controller (state
 *       1, 4, 5 or 7) and sent its table ({@code port_table},
 *       {@code radio_table_stats}) as a non-empty list with an identity on
 *       every row. A disconnected switch may come back with an empty or
 *       missing port table; its ports are not gone. Every switch has ports
 *       and every access point a radio, so an empty table is incomplete in
 *       any state (review R2-W1).</li>
 *   <li>{@code UniFiNvr}, {@code UniFiCamera}: only when the Protect
 *       bootstrap was read and carried an NVR. The NVR kind also needs the
 *       NVR's MAC; the camera kind needs {@code cameras} as a non-empty list
 *       with a MAC on every camera. A Protect outage marks no camera, and
 *       neither does {@code cameras: []} with an NVR present (review
 *       R2-W1), so the last camera is never removed automatically.</li>
 * </ul>
 * {@code UniFiWorld} is never named (and is excluded; it is always
 * delivered). A device pending adoption is not registered by discovery
 * (Codex P2, build 15), so an object an earlier build registered for it is
 * counted like any other device that is no longer listed.
 */
final class ReadKinds {

	static final String WORLD = "UniFiWorld";
	static final String SITE = "UniFiSite";
	static final String AGGREGATE = "UniFiWirelessAggregate";
	static final String GATEWAY = "UniFiGateway";
	static final String WAN = "UniFiWanInterface";
	static final String SWITCH = "UniFiSwitch";
	static final String PORT = "UniFiSwitchPort";
	static final String ACCESS_POINT = "UniFiAccessPoint";
	static final String RADIO = "UniFiRadio";
	static final String NVR = "UniFiNvr";
	static final String CAMERA = "UniFiCamera";

	/** Kinds the framework must never mark (issue #20). */
	static final Set<String> EXCLUDED =
			Collections.unmodifiableSet(new LinkedHashSet<>(
					Collections.singletonList(WORLD)));

	private boolean sitesRead;
	private boolean everySiteRead = true;
	private boolean deviceWithoutIdentity;
	private boolean wansIncomplete;
	private boolean portsIncomplete;
	private boolean radiosIncomplete;
	private boolean protectRead;
	private boolean nvrRead;
	private boolean camerasRead;

	/**
	 * {@code /self/sites} was read as a site list.
	 *
	 * @param siteCount the sites in it; 0 declares nothing (R2-B1)
	 */
	void sitesRead(int siteCount) {
		sitesRead = siteCount > 0;
	}

	/**
	 * One site's {@code stat/device}.
	 *
	 * @param failed     the site failed or is cooling down this cycle
	 * @param deviceList its payload's {@code data} is a list
	 * @param rcOk       its payload's {@code meta.rc} is {@code ok}
	 *                   ({@link #rcOk(String)})
	 */
	void site(boolean failed, boolean deviceList, boolean rcOk) {
		if (failed || !deviceList || !rcOk) everySiteRead = false;
	}

	/**
	 * Whether a classic-API {@code meta.rc} says the read succeeded. Every
	 * classic Network response carries {@code meta}; a 200 with
	 * {@code rc: error} (or no {@code meta}) is not a complete read (R2-W1).
	 */
	static boolean rcOk(String rc) {
		return rc != null && "ok".equalsIgnoreCase(rc.trim());
	}

	/**
	 * A gateway, switch or access point record with no MAC (#23), or a
	 * device record with no {@code type} (R2-N2): which kind it was is
	 * unknown, so no device kind is complete.
	 */
	void deviceWithoutIdentity() {
		deviceWithoutIdentity = true;
	}

	/**
	 * A registered gateway.
	 *
	 * @param informing its state is a known informing code
	 */
	void gateway(boolean informing) {
		if (!informing) wansIncomplete = true;
	}

	/**
	 * A registered switch.
	 *
	 * @param informing    its state is a known informing code
	 * @param portList     {@code port_table} is a list
	 * @param portRows     rows in it; 0 is incomplete (every switch has ports)
	 * @param portsNoIndex rows of it with no {@code port_idx}
	 */
	void switchPorts(boolean informing, boolean portList, int portRows,
			int portsNoIndex) {
		if (!informing || !portList || portRows <= 0 || portsNoIndex > 0) {
			portsIncomplete = true;
		}
	}

	/**
	 * A registered access point.
	 *
	 * @param informing    its state is a known informing code
	 * @param radioList    {@code radio_table_stats} is a list
	 * @param radioRows    rows in it; 0 is incomplete (every AP has a radio)
	 * @param radiosNoCode rows of it with no radio code
	 */
	void accessPointRadios(boolean informing, boolean radioList, int radioRows,
			int radiosNoCode) {
		if (!informing || !radioList || radioRows <= 0 || radiosNoCode > 0) {
			radiosIncomplete = true;
		}
	}

	/**
	 * The Protect bootstrap, read and carrying an NVR. Not called when the
	 * bootstrap failed or had no NVR.
	 *
	 * @param nvrHasMac       the NVR has a MAC
	 * @param cameraList      {@code cameras} is a list
	 * @param cameraRows      cameras in it; 0 is not proof none exist (R2-W1)
	 * @param camerasNoMac    cameras in it with no MAC
	 */
	void protect(boolean nvrHasMac, boolean cameraList, int cameraRows,
			int camerasNoMac) {
		protectRead = true;
		nvrRead = nvrHasMac;
		camerasRead = cameraList && cameraRows > 0 && camerasNoMac == 0;
	}

	/** The kinds read in full, for {@code kindsReadThisCycle()}. */
	Set<String> kinds() {
		Set<String> out = new LinkedHashSet<>();
		if (!sitesRead) return Collections.unmodifiableSet(out);
		out.add(SITE);
		out.add(AGGREGATE);
		if (everySiteRead && !deviceWithoutIdentity) {
			out.add(GATEWAY);
			out.add(SWITCH);
			out.add(ACCESS_POINT);
			if (!wansIncomplete) out.add(WAN);
			if (!portsIncomplete) out.add(PORT);
			if (!radiosIncomplete) out.add(RADIO);
		}
		if (protectRead) {
			if (nvrRead) out.add(NVR);
			if (camerasRead) out.add(CAMERA);
		}
		return Collections.unmodifiableSet(out);
	}

	/** Whether a raw device {@code state} is a known informing code. */
	static boolean informing(String rawState) {
		Integer code = UniFiValues.stateCode(rawState);
		return code != null && UniFiValues.INFORMING_STATES.contains(code);
	}
}
