#!/usr/bin/env python3
"""Build 18 wiring the SDK-bound adapter cannot be unit-tested for.

#39: collectNvr reads nvr.systemInfo.storage and
nvr.storageStats.recordingSpace through UniFiValues.nvrStorage (unit-tested
in UniFiValuesTest.nvrStorage), never the storageInfo object current
Protect does not send. #15: buildRelationships parents devices through
UplinkTree (unit-tested in UplinkTreeTest), and describe.xml declares a
path for every nesting UplinkTree allows. #27 item 4: every per-parent
object is named through UniFiIds.childName, the same way in discovery and
in relationships. #17: Test Connection maps a 404 to the UniFi OS message.
Stdlib only; run from anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiAdapter.java").read_text()
DESCRIBE = ROOT / "describe.xml"


def method_body(ret, name):
    m = re.search(r"\b" + ret + r"\s+" + name + r"\s*\([^)]*\)[^{;]*\{", SRC)
    if not m:
        sys.exit(f"FAIL: method {name} not found")
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(SRC[i], 0)
        i += 1
    return SRC[m.end():i]


def nvr_storage():
    body = method_body("void", "collectNvr")
    assert 'get("storageInfo")' not in body, "storageInfo is not sent by Protect"
    assert 'nvr.get("systemInfo").get("storage")' in body
    assert 'nvr.get("storageStats").get("recordingSpace")' in body
    assert "UniFiValues.nvrStorage(" in body
    for key in ("total_bytes", "used_bytes", "usage_pct"):
        assert len(re.findall(r'"Storage\|' + key + '"', body)) == 1, key
    guarded = body[body.index("if (storage != null) {"):]
    for key in ("total_bytes", "used_bytes", "usage_pct", "source"):
        assert '"Storage|' + key + '"' in guarded, key + " outside the guard"
    # Review N3: the source is published with the numbers, and both pairs
    # are checked for used above total (logged once per streak).
    assert 'prop(out, "Storage|source", storage.source);' in body
    assert body.count("warnUsedOverTotal(") == 2, "both pairs checked"
    warn = method_body("void", "warnUsedOverTotal")
    assert "UniFiValues.usedExceedsTotal(" in warn
    assert "streakLog.fail(" in warn, "used-over-total must log once per streak"
    ns = "{http://schemas.vmware.com/vcops/schema}"
    root = ET.parse(DESCRIBE).getroot()
    kinds = [k for k in root.iter(ns + "ResourceKind")] or list(root.iter("ResourceKind"))
    nvr = next(k for k in kinds if k.get("key") == "UniFiNvr")
    storage = next(g for g in nvr if g.get("key") == "Storage")
    src = [a for a in storage if a.get("key") == "source"]
    assert len(src) == 1 and src[0].get("isProperty") == "true", "Storage|source declared"


def uplink_tree():
    body = method_body("Relationships", "buildRelationships")
    assert "UplinkTree.resolve(" in body
    assert "uplinkMac.equals(mac)" not in body, "old self-only guard left"
    assert "loggedUplinkFallbacks.add(" in body, "fallback not logged once"
    ns = "{http://schemas.vmware.com/vcops/schema}"
    root = ET.parse(DESCRIBE).getroot()
    paths = [rp.get("path") for rp in root.iter(ns + "ResourcePath")] or \
        [rp.get("path") for rp in root.iter("ResourcePath")]

    def kinds(p):
        return [seg.split("::")[1] + ("*" if seg.endswith("/recursive") else "")
                for seg in p.split("||")[3:]]

    shapes = {tuple(kinds(p)) for p in paths}
    want = {
        ("UniFiGateway", "UniFiWanInterface"),
        ("UniFiGateway", "UniFiSwitch*", "UniFiSwitchPort"),
        ("UniFiGateway", "UniFiSwitch*", "UniFiAccessPoint*", "UniFiRadio"),
        ("UniFiGateway", "UniFiAccessPoint*", "UniFiRadio"),
        ("UniFiSwitch*", "UniFiSwitchPort"),
        ("UniFiSwitch*", "UniFiAccessPoint*", "UniFiRadio"),
        ("UniFiAccessPoint*", "UniFiRadio"),
        ("UniFiNvr", "UniFiCamera"),
        ("UniFiWirelessAggregate",),
    }
    assert shapes == want, f"traversal shapes differ: {shapes ^ want}"
    for p in paths:
        assert p.startswith("unifi_controller::unifi_controller||"
                            "unifi_controller::UniFiWorld::child||"
                            "unifi_controller::UniFiSite::child||"), p


def names():
    enum = method_body("void", "enumerateResources")
    rels = method_body("Relationships", "buildRelationships")
    for body, where in ((enum, "enumerateResources"), (rels, "buildRelationships")):
        for label in ('"WAN 1"', '"WAN 2"', '"Wireless Summary"'):
            assert "UniFiIds.childName(" + label in body, f"{where}: {label}"
            assert not re.search(r'resource\([^;]*,\s*' + re.escape(label) + r'\s*,', body) \
                and not re.search(r'rcOf\([^;]*,\s*' + re.escape(label) + r'\s*,', body), \
                f"{where}: bare {label}"
        assert "UniFiIds.childName(portDisplayName(" in body, where
        assert "UniFiIds.childName(radioDisplayName(" in body, where
    idx = method_body(r"Map<String, List<OwnPort>>", "buildOwnPortIndex")
    assert "new OwnPort(portKey,\n\t\t\t\t\t\t\tUniFiIds.childName(portName" in idx
    assert "aliases.add(portName);" in idx, "join aliases stay bare labels"


def rename_opt_in():
    m = re.search(r"public boolean isResourceRenameAllowed\(\)\s*\{\s*return true;\s*\}", SRC)
    assert m, "isResourceRenameAllowed() must return true (Broadcom's vSphere/vSAN do)"
    assert "isResourceRenameAllowed(ResourceKey" not in SRC, "no per-key override"
    helper = method_body("void", "renameIfChanged")
    assert "getMonitoringResource(rc.getResourceKey());" in helper
    # Review N2: the warning is once per streak, re-armed every cycle.
    assert "streakLog.fail(RENAME_STREAK)" in helper
    assert "AtomicBoolean" not in helper
    hook = method_body("void", "onCycleStart")
    assert "streakLog.cycleStart();" in hook, "onCycleStart must re-arm the streak log"
    enum = method_body("void", "enumerateResources")
    assert enum.count("sink.accept(") == 1, "only the wrapper may call sink directly"
    wrapper = enum[enum.index("ResourceSink out = rc -> {"):enum.index("};")]
    assert wrapper.index("renameIfChanged(rc);") < wrapper.index("sink.accept(rc);")
    assert enum.count("out.accept(rcOf(") == 12, enum.count("out.accept(rcOf(")


def test_connection():
    assert "UniFiFailures.unifiOsRequired(e)" in SRC


def main():
    nvr_storage()
    uplink_tree()
    names()
    rename_opt_in()
    test_connection()
    print("test_build18_wiring: all assertions passed")


if __name__ == "__main__":
    main()
