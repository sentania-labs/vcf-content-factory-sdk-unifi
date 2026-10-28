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
		displayHealth();
		lackingDiskException();
		rebuildRelabel();
		spareWithReason();
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
		// Review R2-N2: no space carries a space_type, so the UI takes the first.
		NvrStorageHealth.Space first = space(null, "health", null);
		T.check(NvrStorageHealth.primary(Arrays.asList(first, space(null, "atrisk", null))) == first,
				"untyped spaces: the first");
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

	static NvrStorageHealth.Space vol(String type, String health, List<String> reasons,
			NvrStorageHealth.Raid raid) {
		return new NvrStorageHealth.Space(type, "md3", health, "none", reasons, raid);
	}

	static final NvrStorageHealth.Raid NEVER_COMPLETE = NvrStorageHealth.Raid.of(2.0, 1.0);
	static final NvrStorageHealth.Raid ONCE_COMPLETE = NvrStorageHealth.Raid.of(2.0, 2.0);
	static final List<String> LACKING = Arrays.asList("lacking_disk:atrisk");

	/** Review B2/W1/W2/W3: the storage page's displayed health. */
	static void displayHealth() {
		// Devel md3: atrisk for io_err, /api/system raid expected 2, had_most 1.
		T.eq("atrisk", NvrStorageHealth.displayHealth(vol("primary", "atrisk",
				Arrays.asList("io_err:atrisk"), NEVER_COMPLETE)), "devel");
		T.eq("healthy", NvrStorageHealth.displayHealth(vol("primary", "health",
				Collections.<String>emptyList(), null)), "health displays healthy");
		T.eq("healthy", NvrStorageHealth.displayHealth(vol("primary", "healthy", null, null)),
				"healthy");
		T.eq("failed", NvrStorageHealth.displayHealth(vol("primary", "failed", null, null)),
				"failed");
		T.eq("degraded", NvrStorageHealth.displayHealth(vol("primary", "degraded", null, null)),
				"an unknown value is surfaced verbatim");
		T.eq(null, NvrStorageHealth.displayHealth(vol("primary", null, null, null)),
				"no health publishes nothing");
		T.eq(null, NvrStorageHealth.displayHealth(null), "no volume");
		T.eq("atrisk", NvrStorageHealth.displayHealth(vol("primary", "atrisk", null,
				NvrStorageHealth.Raid.NONE)), "W3: atrisk with no reasons list stays atrisk");
		T.eq("atrisk", NvrStorageHealth.displayHealth(vol("primary", "atrisk",
				Collections.<String>emptyList(), NvrStorageHealth.Raid.NONE)),
				"atrisk with an empty reasons list stays atrisk");
	}

	static void lackingDiskException() {
		T.eq("healthy", NvrStorageHealth.displayHealth(vol("primary", "atrisk", LACKING,
				NvrStorageHealth.Raid.NONE)), "no raid block: forgiven");
		T.eq("healthy", NvrStorageHealth.displayHealth(vol("primary", "atrisk", LACKING,
				NEVER_COMPLETE)), "had_most < expected: forgiven");
		T.eq("atrisk", NvrStorageHealth.displayHealth(vol("primary", "atrisk", LACKING,
				ONCE_COMPLETE)), "B2: a once-complete array that lost a member");
		T.eq("atrisk", NvrStorageHealth.displayHealth(vol("primary", "atrisk", LACKING,
				null)), "raid unread: not forgiven");
		T.eq("atrisk", NvrStorageHealth.displayHealth(vol("secondary", "atrisk", LACKING,
				NvrStorageHealth.Raid.NONE)), "only a primary volume");
		T.eq("atrisk", NvrStorageHealth.displayHealth(vol("primary", "atrisk",
				Arrays.asList("lacking_disk:atrisk", "io_err:atrisk"), NvrStorageHealth.Raid.NONE)),
				"a second reason");
		T.eq("atrisk", NvrStorageHealth.displayHealth(vol("primary", "atrisk",
				Arrays.asList("lacking_disk:warn"), NvrStorageHealth.Raid.NONE)),
				"another level");
		T.eq(null, NvrStorageHealth.Raid.of(2.0, null), "a raid block without had_most is unread");
	}

	/** Review W4: a two-member mirror resync shows its repairing drive as initializing. */
	static void rebuildRelabel() {
		List<NvrStorageHealth.Disk> disks = Arrays.asList(disk(1.0, "normal"), disk(2.0, "repairing"));
		NvrStorageHealth.Space resync = new NvrStorageHealth.Space("primary", "md3", "health",
				"repairing", Collections.<String>emptyList(), ONCE_COMPLETE);
		T.check(NvrStorageHealth.rebuildRelabel(resync, disks), "resync relabels");
		NvrStorageHealth.Drives d = NvrStorageHealth.drives(disks, true);
		T.eq(0, d.notHealthy, "the resyncing drive is not counted");
		T.eq("none", d.firstState, "no unhealthy drive");
		T.eq(1, NvrStorageHealth.drives(disks).notHealthy, "without the rule it counts");

		T.check(!NvrStorageHealth.rebuildRelabel(resync.withRaid(null), disks), "raid unread");
		T.check(!NvrStorageHealth.rebuildRelabel(resync.withRaid(NvrStorageHealth.Raid.NONE), disks),
				"no raid block");
		T.check(!NvrStorageHealth.rebuildRelabel(resync.withRaid(NEVER_COMPLETE), disks),
				"an incomplete array");
		T.check(!NvrStorageHealth.rebuildRelabel(resync.withRaid(NvrStorageHealth.Raid.of(3.0, 3.0)),
				disks), "only a two-member array");
		T.check(!NvrStorageHealth.rebuildRelabel(new NvrStorageHealth.Space("primary", "md3",
				"atrisk", "repairing", null, ONCE_COMPLETE), disks), "an unhealthy volume");
		T.check(!NvrStorageHealth.rebuildRelabel(new NvrStorageHealth.Space("primary", "md3",
				"health", "none", null, ONCE_COMPLETE), disks), "no repair running");
		T.check(!NvrStorageHealth.rebuildRelabel(resync,
				Arrays.asList(disk(1.0, "repairing"), disk(2.0, "repairing"))), "no normal drive");
		// Review R2-N1: a normal drive the UI shows at risk is not the healthy half.
		T.check(!NvrStorageHealth.rebuildRelabel(resync, Arrays.asList(
				new NvrStorageHealth.Disk(1.0, "normal", "at_risk", null, 0.0), disk(2.0, "repairing"))),
				"the normal drive is at risk");
		T.check(!NvrStorageHealth.rebuildRelabel(null, disks), "no volume");
	}

	/** Review W6: the UI's spare_at_risk, a hot spare with any reason. */
	static void spareWithReason() {
		T.check(!NvrStorageHealth.notHealthy(new NvrStorageHealth.Disk(1.0, "spare", null,
				null, 0.0)), "a spare with no reason");
		T.check(NvrStorageHealth.notHealthy(new NvrStorageHealth.Disk(1.0, "spare", null,
				Arrays.asList("lower_rpm"), 0.0)), "a spare with a non-risk reason");
		T.check(NvrStorageHealth.notHealthy(new NvrStorageHealth.Disk(1.0, "spare", null,
				Arrays.asList("too_many_bad_sectors"), 0.0)), "a spare with a risk reason");
		NvrStorageHealth.Drives d = NvrStorageHealth.drives(Arrays.asList(
				disk(1.0, "normal"), new NvrStorageHealth.Disk(2.0, "spare", null,
						Arrays.asList("different_type"), 0.0)));
		T.eq(1, d.notHealthy, "counted");
		T.eq(0, d.failed, "not failed");
		T.eq("2", d.firstSlot, "its slot");
	}
}
