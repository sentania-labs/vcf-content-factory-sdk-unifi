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
 * {@code disks[]} drives). No VCF Ops SDK and no framework-jar dependency:
 * the adapter copies each field's text out of the bootstrap
 * ({@code SimpleJson.asString()}, {@code null} when absent) into
 * {@link Space} and {@link Disk}, so the plain-main tests drive exactly the
 * logic the adapter runs.
 *
 * <p>Values are Protect's own, verbatim. The only classification here is
 * which drive states count as not healthy, and it is the one the UniFi OS
 * storage page applies (console web UI, main bundle, read 2026-10-28):
 * green {@code normal}, {@code spare}, {@code initializing},
 * {@code expanding}; neutral {@code nodisk} (an empty bay, not a drive) and
 * {@code probing}; orange {@code risk}, {@code spare_at_risk},
 * {@code foreign}, {@code repairing}, {@code incompatible}; red
 * {@code broken}, {@code faulty}, {@code not_support}. The UI also shows a
 * drive at risk when its {@code health} is {@code at_risk} or its
 * {@code reason} names a risk reason, whatever its state. A state this
 * pack does not know counts as not healthy: a drive the pack cannot place
 * is surfaced, never passed.
 */
final class NvrStorageHealth {

	private NvrStorageHealth() {}

	/** Published for the "first not-healthy drive" pair when none is. */
	static final String NONE = "none";

	/** Drive states the UI colours green or neutral. */
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

	/** One {@code ustorage.space[]} entry. */
	static final class Space {
		final String spaceType;
		final String device;
		final String health;
		final String action;
		/** {@code type:level} per reason, in Protect's order; null when the list is absent. */
		final List<String> reasons;

		Space(String spaceType, String device, String health, String action,
				List<String> reasons) {
			this.spaceType = spaceType;
			this.device = device;
			this.health = health;
			this.action = action;
			this.reasons = reasons;
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
	 * {@code root} nor {@code swap}; {@code null} when there is no such one
	 * space.
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
		return n == 1 ? only : null;
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
		for (Disk d : sorted) {
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
