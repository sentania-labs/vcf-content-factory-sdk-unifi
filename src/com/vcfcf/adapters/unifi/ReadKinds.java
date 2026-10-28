package com.vcfcf.adapters.unifi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
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
 *       site and {@code meta.rc} {@code ok} (the snapshot build throws
 *       without a list; review R3-N1). A site removed from
 *       the controller and its Wireless Summary are marked together. An
 *       empty site list is not proof that everything is gone (a controller
 *       always carries its default site; a Network app mid-restore, an
 *       account whose site access was emptied or a proxy stub can answer
 *       {@code []}), so it declares no kind at all (review R2-B1). A site
 *       row with no {@code name} (the key every site URL and the site's
 *       resource key are built on) is skipped, and leaves Site, Wireless
 *       Summary and the six device kinds out: the site it was is unknown,
 *       so its objects take no miss (review R4-N1).</li>
 *   <li>{@code UniFiGateway}, {@code UniFiSwitch}, {@code UniFiAccessPoint}:
 *       only when every site's {@code stat/device} was read (no failed or
 *       cooling-down site, every payload a device list with
 *       {@code meta.rc} {@code ok}) and no gateway, switch or access point
 *       record lacked its MAC, and no device record lacked its
 *       {@code type} (it could have been any of the three), and at least
 *       one device record came back across all sites. The hook is per kind,
 *       not per site, and a device can move between sites, so one unread
 *       site leaves every device kind out. Zero device records on every
 *       site is not an inventory (a UniFi OS console lists itself; a
 *       Network app mid-start or a proxy stub answers {@code ok} with
 *       {@code []}), so it declares none of the six device kinds and the
 *       last device on the controller is removed by hand (review R3-B1).</li>
 *   <li>{@code UniFiWanInterface}, {@code UniFiSwitchPort},
 *       {@code UniFiRadio}: as their device kinds, and also only when every
 *       registered device of that type is informing the controller (state
 *       1, 4, 5 or 7) and sent its table ({@code port_table},
 *       {@code radio_table_stats}) as a non-empty list with an identity on
 *       every row; for WAN, every registered gateway carries {@code wan1}
 *       (review R3-W1; an absent {@code wan2} is a real configuration and
 *       is not a gap). A disconnected switch may come back with an empty or
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
 *
 * <p>{@link #leftOut()} says why each left-out kind was left out, so the
 * adapter can log it once per streak (review R3-N2): removal that is off
 * for a kind is never off silently.
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
	private boolean siteListEmpty;
	private boolean siteListRcNotOk;
	private boolean siteWithoutIdentity;
	private boolean everySiteRead = true;
	private int deviceRecords;
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
	 * @param rcOk      its {@code meta.rc} is {@code ok}; otherwise nothing
	 *                  is declared (R3-N1)
	 */
	void sitesRead(int siteCount, boolean rcOk) {
		siteListEmpty = siteCount <= 0;
		siteListRcNotOk = !rcOk;
		sitesRead = !siteListEmpty && rcOk;
	}

	/**
	 * A {@code /self/sites} row with no {@code name} (review R4-N1). The
	 * adapter skips it (no Site with a null key); which site it was is
	 * unknown, and its devices were not read, so Site, Wireless Summary and
	 * every device kind are left out this cycle.
	 */
	void siteWithoutIdentity() {
		siteWithoutIdentity = true;
	}

	/** The site list was read in full: at least one site, meta.rc ok. */
	boolean siteListRead() {
		return sitesRead;
	}

	/**
	 * One site's {@code stat/device}.
	 *
	 * @param failed     the site failed or is cooling down this cycle
	 * @param deviceList its payload's {@code data} is a list
	 * @param rcOk       its payload's {@code meta.rc} is {@code ok}
	 *                   ({@link #rcOk(String)})
	 * @param deviceRows records in its {@code data}, any type, registered or
	 *                   not; summed over the sites, 0 declares no device
	 *                   kind (R3-B1)
	 */
	void site(boolean failed, boolean deviceList, boolean rcOk,
			int deviceRows) {
		if (failed || !deviceList || !rcOk) everySiteRead = false;
		if (deviceRows > 0) deviceRecords += deviceRows;
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
	 * @param hasWan1   its record carries {@code wan1}; every gateway has a
	 *                  WAN 1, so its absence is incomplete (R3-W1)
	 */
	void gateway(boolean informing, boolean hasWan1) {
		if (!informing || !hasWan1) wansIncomplete = true;
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
		evaluate(out, new LinkedHashMap<>());
		return Collections.unmodifiableSet(out);
	}

	/**
	 * Why kinds were left out this cycle (review R3-N2): a stable key per
	 * reason (for a once-per-streak log) to a sentence naming the kinds and
	 * the cause. Empty when every kind was read. The same evaluation as
	 * {@link #kinds()}, so the two cannot disagree.
	 */
	Map<String, String> leftOut() {
		Map<String, String> why = new LinkedHashMap<>();
		evaluate(new LinkedHashSet<>(), why);
		return Collections.unmodifiableMap(why);
	}

	private static final String DEVICE_KINDS =
			"gateway, switch, access point, WAN, port and radio";

	private void evaluate(Set<String> out, Map<String, String> why) {
		if (!sitesRead) {
			why.put("sites", "every kind: the site list "
					+ (siteListEmpty ? "was empty" : "meta.rc was not ok"));
			return;
		}
		if (siteWithoutIdentity) {
			why.put("site-identity", "site, Wireless Summary, " + DEVICE_KINDS
					+ ": a site in the site list had no name");
		} else {
			out.add(SITE);
			out.add(AGGREGATE);
		}
		if (siteWithoutIdentity) {
			// Reason given above; that site's devices were not read.
		} else if (!everySiteRead) {
			why.put("devices-site", DEVICE_KINDS + ": a site's stat/device "
					+ "failed, is cooling down, was not a device list or its "
					+ "meta.rc was not ok");
		} else if (deviceWithoutIdentity) {
			why.put("devices-identity", DEVICE_KINDS + ": a device record "
					+ "had no type, or a gateway, switch or access point no MAC");
		} else if (deviceRecords <= 0) {
			why.put("devices-none", DEVICE_KINDS + ": no site listed any "
					+ "device, which is not taken as proof that none exist");
		} else {
			out.add(GATEWAY);
			out.add(SWITCH);
			out.add(ACCESS_POINT);
			if (wansIncomplete) {
				why.put("wan", "WAN: a gateway was not informing or its "
						+ "record had no wan1");
			} else {
				out.add(WAN);
			}
			if (portsIncomplete) {
				why.put("port", "port: a switch was not informing or sent an "
						+ "empty or missing port_table, or a port with no "
						+ "port_idx");
			} else {
				out.add(PORT);
			}
			if (radiosIncomplete) {
				why.put("radio", "radio: an access point was not informing or "
						+ "sent an empty or missing radio_table_stats, or a "
						+ "radio with no code");
			} else {
				out.add(RADIO);
			}
		}
		if (!protectRead) {
			why.put("protect", "NVR and camera: no Protect bootstrap with an "
					+ "NVR was read");
			return;
		}
		if (nvrRead) {
			out.add(NVR);
		} else {
			why.put("nvr", "NVR: the NVR had no MAC");
		}
		if (camerasRead) {
			out.add(CAMERA);
		} else {
			why.put("camera", "camera: the camera list was missing or empty, "
					+ "or a camera had no MAC");
		}
	}

	/** Whether a raw device {@code state} is a known informing code. */
	static boolean informing(String rawState) {
		Integer code = UniFiValues.stateCode(rawState);
		return code != null && UniFiValues.INFORMING_STATES.contains(code);
	}
}
