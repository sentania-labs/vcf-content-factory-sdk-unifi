package com.vcfcf.adapters.unifi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Build 19: the NVR's storage health as Protect reports it, from the
 * bootstrap's {@code nvr.systemInfo.ustorage} ({@code space[]} volumes,
 * {@code disks[]} drives), plus the volume's {@code raid} block from UniFi
 * OS {@code /api/system} (Protect trims it from the bootstrap). No VCF Ops
 * SDK and no framework-jar dependency: the adapter copies each field's text
 * out of the JSON into {@link Space} and {@link Disk}, so the plain-main
 * tests drive exactly the logic the adapter runs.
 *
 * <p>Values are Protect's own, verbatim. The only classification here is
 * the one the UniFi OS storage page applies (console web UI, main bundle,
 * read 2026-10-28), copied rule for rule:
 * <ul>
 * <li>Drive states: green {@code normal}, {@code initializing},
 * {@code expanding}; black {@code spare}; neutral {@code nodisk} (an empty
 * bay, not a drive) and {@code probing}; orange {@code risk},
 * {@code spare_at_risk}, {@code foreign}, {@code repairing},
 * {@code incompatible}; red {@code broken}, {@code faulty},
 * {@code not_support}. The UI also shows a drive at risk when its
 * {@code health} is {@code at_risk} or its {@code reason} names a risk
 * reason, whatever its state, and turns a {@code spare} with any
 * {@code reason} into {@code spare_at_risk}. A state this pack does not
 * know counts as not healthy: a drive the pack cannot place is surfaced,
 * never passed.</li>
 * <li>Volume display health ({@link #displayHealth}): {@code health} and
 * {@code healthy} display as {@code healthy}; a primary volume at risk for
 * exactly one reason, {@code lacking_disk} at level {@code atrisk}, displays
 * healthy only when it has no {@code raid} block or
 * {@code raid.had_most < raid.expected} (the array never had all its
 * members). When the raid block could not be read the exception is not
 * applied.</li>
 * <li>Rebuild ({@link #rebuildRelabel}): on a healthy primary volume whose
 * action is {@code repairing}, with {@code raid.expected == 2} and
 * {@code had_most >= expected}, one drive {@code normal} and one
 * {@code repairing}, the UI shows the {@code repairing} drive as
 * {@code initializing}.</li>
 * </ul>
 */
final class NvrStorageHealth {

	private NvrStorageHealth() {}

	/** Published for the "first not-healthy drive" pair when none is. */
	static final String NONE = "none";

	/** Drive states the UI colours green, black ({@code spare}) or neutral. */
	static final Set<String> OK_STATES = Collections.unmodifiableSet(
			new HashSet<>(Arrays.asList("normal", "spare", "initializing",
					"expanding", "probing")));

	/** Drive states the UI colours red ("Drive Failure Detected", "Drive Not Supported"). */
	static final Set<String> FAILED_STATES = Collections.unmodifiableSet(
			new HashSet<>(Arrays.asList("broken", "faulty", "not_support")));

	/** An empty bay: listed by Protect, not a drive. */
	static final String NO_DISK = "nodisk";

	/** Drive {@code reason} values the UI treats as at risk. */
	static final Set<String> RISK_REASONS = Collections.unmodifiableSet(
			new HashSet<>(Arrays.asList("abnormal_smart", "too_many_read_error",
					"too_many_bad_sectors", "too_short_life_span",
					"too_many_unc_count", "too_many_get_smart_error",
					"investigation_needed")));

	/**
	 * A volume's {@code raid} block from {@code /api/system}: {@link #NONE}
	 * when the record was read and carries no block; a {@code null} Raid on a
	 * {@link Space} means it could not be read.
	 */
	static final class Raid {
		static final Raid NONE = new Raid(null, null);
		final Double expected;
		final Double hadMost;

		private Raid(Double expected, Double hadMost) {
			this.expected = expected;
			this.hadMost = hadMost;
		}

		/** A read block; {@code null} (unreadable) unless both counts are numbers. */
		static Raid of(Double expected, Double hadMost) {
			return expected == null || hadMost == null ? null : new Raid(expected, hadMost);
		}

		boolean present() {
			return this != NONE;
		}
	}

	/** One {@code ustorage.space[]} entry. */
	static final class Space {
		final String spaceType;
		final String device;
		final String health;
		final String action;
		/** {@code type:level} per reason, in Protect's order; null when the list is absent. */
		final List<String> reasons;
		/** The raid block, {@link Raid#NONE} when there is none, null when unread. */
		final Raid raid;

		Space(String spaceType, String device, String health, String action,
				List<String> reasons) {
			this(spaceType, device, health, action, reasons, null);
		}

		Space(String spaceType, String device, String health, String action,
				List<String> reasons, Raid raid) {
			this.spaceType = spaceType;
			this.device = device;
			this.health = health;
			this.action = action;
			this.reasons = reasons;
			this.raid = raid;
		}

		/** The same volume with its raid block (or null, unread). */
		Space withRaid(Raid r) {
			return new Space(spaceType, device, health, action, reasons, r);
		}
	}

	/** One {@code ustorage.disks[]} entry. */
	static final class Disk {
		final Double slot;
		final String state;
		final String health;
		/** Reason names (a string, {@code {type}}, or a list of either); empty when none. */
		final List<String> reasons;
		final Double badSectors;

		Disk(Double slot, String state, String health, List<String> reasons,
				Double badSectors) {
			this.slot = slot;
			this.state = state;
			this.health = health;
			this.reasons = reasons == null ? Collections.<String>emptyList() : reasons;
			this.badSectors = badSectors;
		}
	}

	/**
	 * The recording volume, chosen as the UniFi OS storage page chooses it:
	 * the {@code primary} space, else the only space that is neither
	 * {@code root} nor {@code swap}; when no space carries a
	 * {@code space_type} at all, the first space (the UI's third branch).
	 * {@code null} when none of these picks one.
	 */
	static Space primary(List<Space> spaces) {
		if (spaces == null) return null;
		for (Space s : spaces) {
			if ("primary".equals(s.spaceType)) return s;
		}
		Space only = null;
		int n = 0;
		for (Space s : spaces) {
			if ("root".equals(s.spaceType) || "swap".equals(s.spaceType)) continue;
			only = s;
			n++;
		}
		if (n == 1) return only;
		boolean anyType = false;
		for (Space s : spaces) {
			if (s.spaceType != null) anyType = true;
		}
		return !anyType && !spaces.isEmpty() ? spaces.get(0) : null;
	}

	/**
	 * {@code Storage|volume_issues}: the reasons as {@code type:level},
	 * comma separated in Protect's order ({@code io_err:atrisk}), or
	 * {@link #NONE} when Protect sent an empty list; {@code null} (publish
	 * nothing) when it sent no list.
	 */
	static String issues(Space s) {
		if (s == null || s.reasons == null) return null;
		if (s.reasons.isEmpty()) return NONE;
		return String.join(", ", s.reasons);
	}

	/** The one reason the UI forgives on a volume that never had all its members. */
	static final String LACKING_DISK_ATRISK = "lacking_disk:atrisk";

	/**
	 * {@code Storage|volume_display_health}: the health the storage page
	 * displays for this volume ({@code healthy}, {@code atrisk},
	 * {@code failed}), by the UI's rule (class Javadoc). A value outside the
	 * UI's set is returned verbatim, so it is surfaced, never passed;
	 * {@code null} (publish nothing) when there is no volume or it carries
	 * no {@code health}.
	 */
	static String displayHealth(Space s) {
		if (s == null || s.health == null) return null;
		if ("health".equals(s.health) || "healthy".equals(s.health)) return "healthy";
		if (!"atrisk".equals(s.health)) return s.health;
		boolean forgiven = "primary".equals(s.spaceType)
				&& s.reasons != null && s.reasons.size() == 1
				&& LACKING_DISK_ATRISK.equals(s.reasons.get(0))
				&& s.raid != null
				&& (!s.raid.present() || s.raid.hadMost < s.raid.expected);
		return forgiven ? "healthy" : "atrisk";
	}

	/**
	 * Whether the UI shows the {@code repairing} drives as
	 * {@code initializing}: a two-member mirror resyncing on a healthy
	 * primary volume (class Javadoc). False when the raid block was not read.
	 */
	static boolean rebuildRelabel(Space s, List<Disk> disks) {
		if (s == null || disks == null || s.raid == null || !s.raid.present()) return false;
		if (!"healthy".equals(displayHealth(s)) || !"repairing".equals(s.action)) return false;
		if (s.raid.expected != 2.0 || s.raid.hadMost < s.raid.expected) return false;
		boolean normal = false;
		boolean repairing = false;
		for (Disk d : disks) {
			// Review R2-N1: the UI tests the state after its drive transform,
			// where a normal drive at risk is already "risk".
			if ("normal".equals(d.state) && !notHealthy(d)) normal = true;
			if ("repairing".equals(d.state)) repairing = true;
		}
		return normal && repairing;
	}

	/** A reason as {@code type:level}; a missing part is left empty. */
	static String reason(String type, String level) {
		return (type == null ? "" : type) + ":" + (level == null ? "" : level);
	}

	/** Whether the slot holds a drive (any state but {@code nodisk}). */
	static boolean isDrive(Disk d) {
		return !NO_DISK.equals(d.state);
	}

	/** Whether Protect's storage page would show this drive at risk or failed. */
	static boolean notHealthy(Disk d) {
		if (!isDrive(d)) return false;
		if (isFailed(d)) return true;
		if ("at_risk".equals(d.health)) return true;
		for (String r : d.reasons) {
			if (RISK_REASONS.contains(r)) return true;
		}
		// The UI's spare_at_risk: a hot spare with any reason cannot serve.
		if ("spare".equals(d.state) && !d.reasons.isEmpty()) return true;
		return !OK_STATES.contains(d.state);
	}

	/** Whether the drive is in a state the UI colours red, or its health is {@code broken}. */
	static boolean isFailed(Disk d) {
		return FAILED_STATES.contains(d.state) || "broken".equals(d.health);
	}

	/** The drive summary published on the NVR; see {@link #drives}. */
	static final class Drives {
		final int count;
		final int notHealthy;
		final int failed;
		/** Slot of the lowest-slot drive that is not healthy, or {@link #NONE}. */
		final String firstSlot;
		/** Its state, verbatim, or {@link #NONE}. */
		final String firstState;
		/** Sum of {@code bad_sector}; null unless every drive carries it. */
		final Double badSectors;

		Drives(int count, int notHealthy, int failed, String firstSlot,
				String firstState, Double badSectors) {
			this.count = count;
			this.notHealthy = notHealthy;
			this.failed = failed;
			this.firstSlot = firstSlot;
			this.firstState = firstState;
			this.badSectors = badSectors;
		}
	}

	/**
	 * The drive summary, or {@code null} (publish nothing) when Protect sent
	 * no disk list or any entry lacks its {@code state} or {@code slot}: a
	 * count over a list the pack could not read in full would say "0 not
	 * healthy" about a drive it never saw.
	 */
	static Drives drives(List<Disk> disks) {
		return drives(disks, false);
	}

	/**
	 * As {@link #drives(List)}; with {@code relabel} ({@link #rebuildRelabel})
	 * a {@code repairing} drive is counted, and named, as the UI shows it:
	 * {@code initializing}.
	 */
	static Drives drives(List<Disk> disks, boolean relabel) {
		if (disks == null) return null;
		for (Disk d : disks) {
			if (d.state == null || d.state.trim().isEmpty() || d.slot == null) return null;
		}
		List<Disk> sorted = new ArrayList<>(disks);
		sorted.sort((a, b) -> Double.compare(a.slot, b.slot));
		int count = 0;
		int notHealthy = 0;
		int failed = 0;
		Disk first = null;
		double bad = 0;
		boolean badComplete = true;
		for (Disk raw : sorted) {
			Disk d = relabel && "repairing".equals(raw.state)
					? new Disk(raw.slot, "initializing", raw.health, raw.reasons, raw.badSectors)
					: raw;
			if (!isDrive(d)) continue;
			count++;
			if (d.badSectors == null) badComplete = false;
			else bad += d.badSectors;
			if (notHealthy(d)) {
				notHealthy++;
				if (first == null) first = d;
			}
			if (isFailed(d)) failed++;
		}
		String slot = first == null ? NONE : slotText(first.slot);
		String state = first == null ? NONE : first.state;
		return new Drives(count, notHealthy, failed, slot, state,
				badComplete ? Double.valueOf(bad) : null);
	}

	private static String slotText(double slot) {
		return slot == Math.rint(slot) ? String.valueOf((long) slot) : String.valueOf(slot);
	}
}
