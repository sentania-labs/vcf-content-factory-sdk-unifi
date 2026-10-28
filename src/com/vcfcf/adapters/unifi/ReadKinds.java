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
 *       whenever {@code /self/sites} was read, which every enumeration needs
 *       (the snapshot build throws otherwise). A site removed from the
 *       controller and its Wireless Summary are marked together.</li>
 *   <li>{@code UniFiGateway}, {@code UniFiSwitch}, {@code UniFiAccessPoint}:
 *       only when every site's {@code stat/device} was read (no failed or
 *       cooling-down site, every payload a device list) and no gateway,
 *       switch or access point record lacked its MAC. The hook is per kind,
 *       not per site, and a device can move between sites, so one unread
 *       site leaves every device kind out.</li>
 *   <li>{@code UniFiWanInterface}, {@code UniFiSwitchPort},
 *       {@code UniFiRadio}: as their device kinds, and also only when every
 *       registered device of that type is informing the controller (state
 *       1, 4, 5 or 7) and sent its table ({@code port_table},
 *       {@code radio_table_stats}) as a list with an identity on every row.
 *       A disconnected switch may come back with an empty or missing port
 *       table; its ports are not gone.</li>
 *   <li>{@code UniFiNvr}, {@code UniFiCamera}: only when the Protect
 *       bootstrap was read and carried an NVR. The NVR kind also needs the
 *       NVR's MAC; the camera kind needs {@code cameras} as a list with a MAC
 *       on every camera. A Protect outage marks no camera.</li>
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

	/** {@code /self/sites} was read as a site list. */
	void sitesRead() {
		sitesRead = true;
	}

	/**
	 * One site's {@code stat/device}.
	 *
	 * @param failed     the site failed or is cooling down this cycle
	 * @param deviceList its payload's {@code data} is a list
	 */
	void site(boolean failed, boolean deviceList) {
		if (failed || !deviceList) everySiteRead = false;
	}

	/** A gateway, switch or access point record with no MAC (#23). */
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
	 * @param portsNoIndex rows of it with no {@code port_idx}
	 */
	void switchPorts(boolean informing, boolean portList, int portsNoIndex) {
		if (!informing || !portList || portsNoIndex > 0) portsIncomplete = true;
	}

	/**
	 * A registered access point.
	 *
	 * @param informing    its state is a known informing code
	 * @param radioList    {@code radio_table_stats} is a list
	 * @param radiosNoCode rows of it with no radio code
	 */
	void accessPointRadios(boolean informing, boolean radioList, int radiosNoCode) {
		if (!informing || !radioList || radiosNoCode > 0) radiosIncomplete = true;
	}

	/**
	 * The Protect bootstrap, read and carrying an NVR. Not called when the
	 * bootstrap failed or had no NVR.
	 *
	 * @param nvrHasMac       the NVR has a MAC
	 * @param cameraList      {@code cameras} is a list
	 * @param camerasNoMac    cameras in it with no MAC
	 */
	void protect(boolean nvrHasMac, boolean cameraList, int camerasNoMac) {
		protectRead = true;
		nvrRead = nvrHasMac;
		camerasRead = cameraList && camerasNoMac == 0;
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
