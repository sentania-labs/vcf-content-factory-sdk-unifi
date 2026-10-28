#!/usr/bin/env python3
"""Issue #20 wiring the SDK-bound adapter cannot be unit-tested for.

The rule for which kinds are read in full is ReadKinds (unit-tested in
ReadKindsTest). This checks the adapter feeds it from the same walk that
registers the resources: every site's read, every registered device's state
and child table, the Protect bootstrap only inside the branch that has one,
and that kindsReadThisCycle answers only for the enumeration that just ran
on this thread. Second half of #20: buildRelationships leaves the NVR's
site out on a cycle without Protect (ProtectEdgeHold, unit-tested in
ProtectEdgeHoldTest), the NVR is a parent only on a cycle that read
Protect, and the vmnic stitch is only ever added to (foreign parent), so a
Suite API blip publishes nothing that could remove its edges. Stdlib only;
ci/run_java_tests.sh runs it.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiAdapter.java").read_text()


def method_body(ret, name):
    m = re.search(r"\b" + re.escape(ret) + r"\s+" + name + r"\s*\([^)]*\)[^{;]*\{", SRC)
    if not m:
        sys.exit(f"FAIL: method {name} not found")
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(SRC[i], 0)
        i += 1
    return SRC[m.end():i]


def hooks():
    body = method_body("Set<String>", "kindsReadThisCycle")
    assert "r.thread == Thread.currentThread()" in body, "answers for this thread only"
    assert "ReadKinds.EXCLUDED" in method_body("Set<String>", "notExistingExcludedKinds")
    # The framework default (null) would mark nothing; both are overridden.
    for name in ("kindsReadThisCycle", "notExistingExcludedKinds"):
        assert re.search(r"@Override\s+protected Set<String> " + name, SRC), name
    # No threshold override: the framework default of 12 cycles stands.
    assert "notExistingMissedCycles" not in SRC.replace(
        "{@code notExistingMissedCycles()}", ""), "threshold left at default"


def enumeration():
    enum = method_body("void", "enumerateResources")
    # Cleared first, recorded last: a throw anywhere leaves nothing declared.
    assert enum.index("lastRead = null;") < enum.index("currentSnapshot()")
    tail = enum.rstrip()[:-1].rstrip()  # drop the method's closing brace
    assert tail.endswith(
        "lastRead = new ReadRecord(Thread.currentThread(), read.kinds());"), tail[-120:]
    assert enum.index("read.sitesRead(") > enum.index("currentSnapshot()")
    # Review R2-B1: the site count goes in, so an empty list declares nothing;
    # review R3-N1: and the site list's own meta.rc.
    assert ('read.sitesRead(s.sites.get("data").asList().size(),\n\t\t\t\t'
            'ReadKinds.rcOk(s.sites.get("meta").get("rc").asString("")));') in enum
    # Every site's stat/device, failed sites included (cool-downs are in
    # failedSites), before its devices are walked.
    site = enum.index("read.site(s.failedSites.containsKey(siteName),")
    assert site < enum.index("for (SimpleJson dev : siteDevices.asList())")
    assert "s.devicesBySite.containsKey(siteName) && siteDevices.isList()" in enum
    # Review R2-W1(a): meta.rc must be ok for the site to count as read.
    assert 'ReadKinds.rcOk(sitePayload.get("meta").get("rc").asString(""))' in enum
    # Review R3-B1: every record in the site's data counts (before any skip),
    # so zero devices on every site declares no device kind.
    assert ('ReadKinds.rcOk(sitePayload.get("meta").get("rc").asString("")),\n'
            '\t\t\t\t\tsiteDevices.isList() ? siteDevices.asList().size() : 0);') in enum
    assert enum.count("read.site(") == 1
    # Review R4-N1: a nameless site row is declared and skipped before it
    # is emitted or read.
    guard = enum.index("if (siteName == null || siteName.trim().isEmpty()) {\n\t\t\t\tread.siteWithoutIdentity();\n\t\t\t\tcontinue;")
    assert guard < enum.index('out.accept(rcOf("UniFiSite"')
    assert guard < enum.index("read.site(s.failedSites")
    assert "read.deviceWithoutIdentity();" in enum
    # Review R2-N2: a record with no type is unknown, as a MAC-less one.
    assert "if (type.trim().isEmpty()) read.deviceWithoutIdentity();" in enum
    assert enum.index("if (type.trim().isEmpty())") < enum.index("String mac = deviceMac(dev);")
    # Review R3-W1: a gateway record with no wan1 is incomplete for WAN.
    assert ('read.gateway(ReadKinds.informing(dev.get("state").asString()),\n'
            '\t\t\t\t\t\t\t!dev.get("wan1").isNull());') in enum
    assert '!dev.get("wan2")' not in enum.split("read.gateway(")[1].split(";")[0], \
        "wan2 must not gate the WAN kind"
    # Review R2-W1(c, d): the row count goes in, so an empty table is incomplete.
    assert "portTable.isList() ? portTable.asList().size() : 0,\n\t\t\t\t\t\t\tportsNoIndex);" in enum
    assert "radioStats.isList() ? radioStats.asList().size() : 0,\n\t\t\t\t\t\t\tradiosNoCode);" in enum
    # Protect is declared only inside the branch with a bootstrap and an NVR.
    protect = enum[enum.index("if (s.protect != null) {"):]
    assert ("read.protect(nvrMac != null, cams.isList(),\n\t\t\t\t\t\tcams.isList() ? cams.asList().size() : 0, camerasNoMac);"
            in protect)
    assert enum.count("read.protect(") == 1
    # Skipped records are counted, not silently dropped.
    assert "portsNoIndex++;" in enum and "radiosNoCode++;" in enum
    assert "camerasNoMac++;" in enum


def gap_logging():
    enum = method_body("void", "enumerateResources")
    # Review R3-N2, R3-N3: the gaps are logged before the record is made,
    # from the same ReadKinds, every enumeration.
    assert enum.index("logVanishedReadGaps(read, listedSites);") < enum.index(
        "lastRead = new ReadRecord(")
    assert "listedSites.put(siteName, siteDesc);" in enum
    body = method_body("void", "logVanishedReadGaps")
    assert "read.leftOut().entrySet()" in body
    assert 'streakLog.fail("not-read|" + e.getKey())' in body
    assert "logInfo(" in body
    # The site watch is fed only a fully read list (no warn, no forget on
    # an empty or rc-failed one), and warns.
    assert body.index("!read.siteListRead()") < body.index("c.siteWatch.update(listedSites)")
    # Review R5-N2: not in a cycle with a nameless row.
    assert ("if (c == null || !read.siteListRead() || read.siteWithoutIdentitySeen()) return;"
            in body)
    assert body.index("read.siteWithoutIdentitySeen()") < body.index("c.siteWatch.update(listedSites)")
    assert "logWarn(" in body
    assert "final SiteListWatch siteWatch = new SiteListWatch();" in SRC


def relationships():
    rel = method_body("Relationships", "buildRelationships")
    # The hold is decided once, before the site loop.
    assert rel.count("holdNvrSite(s, nvrSite)") == 1
    assert rel.index("holdNvrSite(s, nvrSite)") < rel.index("for (SimpleJson site :")
    # A held site still hangs under the World, and is skipped before any of
    # its children go into the builder (no reduced full set).
    loop = rel[rel.index("for (SimpleJson site :"):rel.index("// Protect: NVR")]
    nameless = loop.index("if (siteName == null || siteName.trim().isEmpty()) continue;")
    assert nameless < loop.index('ResourceKey siteKey = rb.resource("UniFiSite"')
    held = loop.index("if (holdNvrSite && siteName != null && siteName.equals(nvrSite)) {")
    assert loop.index("rb.parent(world, siteKey);") < held
    assert held < loop.index("SimpleJson devices = s.devicesForSite(siteName);")
    assert held < loop.index("rb.parent(siteKey, rb.resource(\"UniFiWirelessAggregate\"")
    # The NVR is a parent (and the site's NVR child) only inside the Protect
    # branch, so a cycle without Protect sends no set for it.
    protect = rel[rel.index("if (s.protect != null) {"):rel.index("emitVmnicHostStitch(rb, s);")]
    assert "rb.parent(siteKey, nvrKey);" in protect
    # Review R5-N1: a nameless first row is no Site key and no NVR parent.
    assert "if (!SiteIsolation.hasName(nvrSite)) nvrSite = null;" in rel
    assert rel.index("if (!SiteIsolation.hasName(nvrSite)) nvrSite = null;") < rel.index(
        "holdNvrSite(s, nvrSite)")
    assert "boolean namedSite = SiteIsolation.hasName(siteName);" in protect
    assert ('ResourceKey siteKey = namedSite ? rb.resource("UniFiSite",\n'
            '\t\t\t\t\t\tsiteDesc, "site_name", siteName) : null;') in protect
    assert ("if (nvrKey != null && namedSite && siteName.equals(\n"
            "\t\t\t\t\t\tSiteIsolation.nvrParentSite(s.siteNames(), s.failedSites))) {") in protect
    assert protect.index("namedSite && siteName.equals(") < protect.index("rb.parent(siteKey, nvrKey);")
    assert "rb.parent(nvrKey," in protect
    outside = rel.replace(protect, "")
    assert "nvrKey" not in outside, "NVR edges built outside the Protect branch"
    hold = method_body("boolean", "holdNvrSite")
    assert "protectEdgeHold.cycle(protectRead," in hold
    assert 'UniFiIds.mac(s.protect.get("nvr").get("mac").asString()) != null' in hold
    assert "protectRead ? null : platformKnowsNvr()" in hold
    knows = method_body("Boolean", "platformKnowsNvr")
    assert "getMonitoringResources()" in knows and "getStoppedResources()" in knows
    assert "catch (RuntimeException e) {\n\t\t\treturn null;" in knows


def snapshot_site_names():
    # Review R5-N1: stat/device is never asked for a nameless row, and the
    # NVR parent heuristic sees named sites only.
    build = method_body("Snapshot", "build")
    named = build.index("siteNames = SiteIsolation.named(siteNames);")
    assert named < build.index("SiteReads.readAll(\n\t\t\t\t\t\"stat/device\", siteNames,")
    names = method_body("List<String>", "siteNames")
    assert "return SiteIsolation.named(names);" in names


def stitch():
    # The stitch hangs ports under foreign HostSystems only: the framework
    # sends a foreign parent as addRelationships (never clear-first,
    # framework RelationshipBuilderTest), so a cycle with no stitch removes
    # nothing. A parent() or childForeign() here would be a full set.
    body = method_body("void", "emitVmnicHostStitch")
    assert "rb.parentForeign(e.hostKey, portRk);" in body
    assert "rb.parent(" not in body and "rb.childForeign(" not in body
    assert SRC.count("rb.parentForeign(") == 1
    assert "rb.childForeign(" not in SRC


def main():
    hooks()
    enumeration()
    gap_logging()
    relationships()
    snapshot_site_names()
    stitch()
    print("test_vanished_wiring: all assertions passed")


if __name__ == "__main__":
    main()
