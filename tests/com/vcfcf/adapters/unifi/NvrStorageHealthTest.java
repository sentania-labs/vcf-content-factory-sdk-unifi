package com.vcfcf.adapters.unifi;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Build 19: NVR storage health, Protect's values verbatim. Run from the
 * repo root (ci/run_java_tests.sh).
 */
public final class NvrStorageHealthTest {

	public static void main(String[] args) {
		develVolume();
		primaryChoice();
		issuesShapes();
		develDrive();
		driveStates();
		driveRiskByHealthOrReason();
		incompleteDiskList();
		badSectors();
		System.out.println("NvrStorageHealthTest: all assertions passed");
	}

	static NvrStorageHealth.Space space(String type, String health, List<String> reasons) {
		return new NvrStorageHealth.Space(type, "md3", health, "none", reasons);
	}

	static NvrStorageHealth.Disk disk(Double slot, String state) {
		return new NvrStorageHealth.Disk(slot, state, null, null, 0.0);
	}

	/** Devel UDM Pro, Protect 7.3.70, bootstrap 2026-10-28. */
	static void develVolume() {
		List<NvrStorageHealth.Space> spaces = Arrays.asList(
				new NvrStorageHealth.Space("primary", "md3", "atrisk", "none",
						Arrays.asList(NvrStorageHealth.reason("io_err", "atrisk"))),
				new NvrStorageHealth.Space("swap", "md0", "health", "none",
						Collections.<String>emptyList()),
				new NvrStorageHealth.Space("root", "overlayfs-root", "health", "none",
						Collections.<String>emptyList()));
		NvrStorageHealth.Space v = NvrStorageHealth.primary(spaces);
		T.eq("md3", v.device, "primary is md3");
		T.eq("atrisk", v.health, "health verbatim");
		T.eq("io_err:atrisk", NvrStorageHealth.issues(v), "issues");
	}

	static void primaryChoice() {
		T.eq(null, NvrStorageHealth.primary(null), "no list");
		T.eq(null, NvrStorageHealth.primary(Collections.<NvrStorageHealth.Space>emptyList()), "empty");
		NvrStorageHealth.Space sec = space("secondary", "health", null);
		T.check(NvrStorageHealth.primary(Arrays.asList(space("root", "health", null), sec)) == sec,
				"the only non-root non-swap space");
		T.eq(null, NvrStorageHealth.primary(Arrays.asList(sec, space(null, "health", null))),
				"two candidates and no primary: none");
		T.eq(null, NvrStorageHealth.primary(Arrays.asList(space("root", "health", null))),
				"root only: none");
	}

	static void issuesShapes() {
		T.eq(null, NvrStorageHealth.issues(space("primary", "health", null)), "absent list publishes nothing");
		T.eq("none", NvrStorageHealth.issues(space("primary", "health",
				Collections.<String>emptyList())), "empty list");
		T.eq("lacking_disk:atrisk, io_err:atrisk", NvrStorageHealth.issues(space("primary", "atrisk",
				Arrays.asList("lacking_disk:atrisk", "io_err:atrisk"))), "order kept");
		T.eq("io_err:", NvrStorageHealth.reason("io_err", null), "missing level");
		T.eq(null, NvrStorageHealth.issues(null), "no volume");
	}

	/** Devel: one 10 TB drive in slot 1, state normal. */
	static void develDrive() {
		NvrStorageHealth.Drives d = NvrStorageHealth.drives(Arrays.asList(disk(1.0, "normal")));
		T.eq(1, d.count, "one drive");
		T.eq(0, d.notHealthy, "healthy");
		T.eq(0, d.failed, "none failed");
		T.eq("none", d.firstSlot, "no slot");
		T.eq("none", d.firstState, "no state");
		T.eq(0.0, d.badSectors, "bad sectors");
	}

	static void driveStates() {
		for (String ok : Arrays.asList("normal", "spare", "initializing", "expanding", "probing")) {
			T.check(!NvrStorageHealth.notHealthy(disk(1.0, ok)), ok + " is healthy");
		}
		for (String warn : Arrays.asList("risk", "spare_at_risk", "foreign", "repairing", "incompatible")) {
			NvrStorageHealth.Disk d = disk(1.0, warn);
			T.check(NvrStorageHealth.notHealthy(d), warn + " not healthy");
			T.check(!NvrStorageHealth.isFailed(d), warn + " not failed");
		}
		for (String red : Arrays.asList("broken", "faulty", "not_support")) {
			NvrStorageHealth.Disk d = disk(1.0, red);
			T.check(NvrStorageHealth.notHealthy(d), red + " not healthy");
			T.check(NvrStorageHealth.isFailed(d), red + " failed");
		}
		T.check(NvrStorageHealth.notHealthy(disk(1.0, "some_new_state")), "unknown state surfaces");
		T.check(!NvrStorageHealth.notHealthy(disk(1.0, "nodisk")), "an empty bay is not a drive");

		NvrStorageHealth.Drives d = NvrStorageHealth.drives(Arrays.asList(
				disk(4.0, "faulty"), disk(2.0, "risk"), disk(1.0, "normal"), disk(3.0, "nodisk")));
		T.eq(3, d.count, "empty bay left out");
		T.eq(2, d.notHealthy, "two not healthy");
		T.eq(1, d.failed, "one failed");
		T.eq("2", d.firstSlot, "lowest slot first");
		T.eq("risk", d.firstState, "its state verbatim");
	}

	static void driveRiskByHealthOrReason() {
		T.check(NvrStorageHealth.notHealthy(new NvrStorageHealth.Disk(1.0, "normal", "at_risk", null, 0.0)),
				"health at_risk on a normal drive");
		T.check(NvrStorageHealth.isFailed(new NvrStorageHealth.Disk(1.0, "normal", "broken", null, 0.0)),
				"health broken is failed");
		T.check(NvrStorageHealth.notHealthy(new NvrStorageHealth.Disk(1.0, "normal", null,
				Arrays.asList("too_many_bad_sectors"), 0.0)), "risk reason");
		T.check(!NvrStorageHealth.notHealthy(new NvrStorageHealth.Disk(1.0, "normal", "healthy",
				Arrays.asList("lower_rpm"), 0.0)), "an incompatibility reason is not a risk");
	}

	/** A list the pack cannot read in full publishes no count. */
	static void incompleteDiskList() {
		T.eq(null, NvrStorageHealth.drives(null), "no list");
		T.eq(null, NvrStorageHealth.drives(Arrays.asList(disk(1.0, "normal"), disk(2.0, null))),
				"a drive without state");
		T.eq(null, NvrStorageHealth.drives(Arrays.asList(disk(1.0, "normal"), disk(2.0, " "))),
				"a blank state");
		T.eq(null, NvrStorageHealth.drives(Arrays.asList(disk(null, "normal"))), "a drive without slot");
		NvrStorageHealth.Drives none = NvrStorageHealth.drives(Collections.<NvrStorageHealth.Disk>emptyList());
		T.eq(0, none.count, "no drives listed is a real zero");
	}

	static void badSectors() {
		NvrStorageHealth.Drives d = NvrStorageHealth.drives(Arrays.asList(
				new NvrStorageHealth.Disk(1.0, "normal", null, null, 3.0),
				new NvrStorageHealth.Disk(2.0, "normal", null, null, 5.0),
				new NvrStorageHealth.Disk(3.0, "nodisk", null, null, null)));
		T.eq(8.0, d.badSectors, "sum over drives, empty bay ignored");
		NvrStorageHealth.Drives partial = NvrStorageHealth.drives(Arrays.asList(
				new NvrStorageHealth.Disk(1.0, "normal", null, null, 3.0),
				new NvrStorageHealth.Disk(2.0, "normal", null, null, null)));
		T.eq(null, partial.badSectors, "a drive without bad_sector: no sum");
		T.eq(2, partial.count, "the counts still publish");
	}
}
