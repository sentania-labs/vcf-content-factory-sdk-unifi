#!/usr/bin/env python3
"""Build 15 (issue #23): one identity guard for discovery, relationships and
the snapshot index.

A device or camera with no MAC and a port with no port_idx are skipped in
enumerateResources, buildRelationships, buildOwnPortIndex, findPort and the
Snapshot.build index alike, through UniFiIds (unit-tested in UniFiIdsTest).
This check pins that no raw read of the identity is left behind. Stdlib
only; run from anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiAdapter.java").read_text()


def method_body(ret, name):
    m = re.search(r"\b" + ret + r"\s+" + name + r"\s*\([^)]*\)[^{;]*\{", SRC)
    if not m:
        sys.exit(f"FAIL: method {name} not found")
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(SRC[i], 0)
        i += 1
    return SRC[m.end():i]


def main():
    assert 'port_idx").asLong()' not in SRC, \
        "a raw port_idx read is left (a missing index becomes port 0)"
    # Devices and cameras (the NVR identity is out of #23's scope).
    for m in re.finditer(r'\b(?:dev|cam)\.get\("mac"\)\.asString\(([^)]*)\)', SRC):
        line_start = SRC.rfind("\n", 0, m.start()) + 1
        line = SRC[line_start:SRC.find("\n", m.end())]
        if "UniFiIds.mac(" in line:
            continue
        prev_start = SRC.rfind("\n", 0, line_start - 1) + 1
        if "UniFiIds.discoveredDevice(" in SRC[prev_start:line_start]:
            continue  # the guard's second argument, on its own line
        # deviceDisplayName only uses the MAC for a display suffix.
        if 'String mac = dev.get("mac").asString("");' in line:
            continue
        sys.exit("FAIL: raw MAC read outside the guard: " + line.strip())

    for name, ret in (("enumerateResources", "void"),
                      ("buildRelationships", "Relationships"),
                      ("buildOwnPortIndex", r"Map<String, List<OwnPort>>")):
        body = method_body(ret, name)
        assert "deviceMac(dev)" in body, f"{name} does not use the MAC guard"
        assert "portIdx(port)" in body, f"{name} does not use the port guard"
    for name in ("enumerateResources", "buildRelationships"):
        body = method_body("void" if name == "enumerateResources"
                           else "Relationships", name)
        assert 'UniFiIds.mac(cam.get("mac")' in body, \
            f"{name} does not guard camera MACs"
    # Every guarded read is followed by its skip: no "<mac>_null" keys.
    for m in re.finditer(r"(?:Integer|String) (\w+) = (?:portIdx\(port\)|deviceMac\(dev\)|radioCode\(radio\)"
                         r"|UniFiIds\.mac\(cam\.get\(\"mac\"\)\.asString\(\)\));\n", SRC):
        var = m.group(1)
        nxt = SRC[m.end():SRC.find("\n", m.end())].strip()
        if nxt.startswith(f"if ({var} == null) continue;"):
            continue
        if nxt.startswith(f"if ({var} != null && "):  # findPort's null-aware match
            continue
        if nxt.startswith(f"if ({var} == null) {{"):  # the snapshot index counts it
            block = SRC[m.end():SRC.find("continue;", m.end())]
            # Issue #20: enumeration counts it for ReadKinds instead.
            assert "Skipped" in block or "skipped" in block \
                or "NoIndex++" in block or "NoCode++" in block \
                or "NoMac++" in block or "read.deviceWithoutIdentity()" in block, block
            continue
        sys.exit(f"FAIL: {var} read without its skip: {nxt}")
    # Peer review 7: radios with no code and an NVR with no MAC.
    assert 'radio.get("radio").asString("");\n' not in SRC.replace("\r", ""), \
        "a raw radio code read is left (a blank code becomes <mac>_)"
    for name, ret in (("enumerateResources", "void"),
                      ("buildRelationships", "Relationships")):
        body = method_body(ret, name)
        assert "radioCode(radio)" in body, f"{name} does not guard radio codes"
        assert 'UniFiIds.mac(nvr.get("mac").asString())' in body, \
            f"{name} does not guard the NVR MAC"
        assert 'nvr.get("mac").asString("")' not in body
    rel = method_body("Relationships", "buildRelationships")
    assert "if (nvrKey != null && !cameras.isNull()) {" in rel, \
        "cameras are parented under an NVR with no identity"
    assert "if (nvrKey != null && java.util.Objects.equals(siteName," in rel
    enum = method_body("void", "enumerateResources")
    assert "if (nvrMac != null) {" in enum
    build = method_body("Snapshot", "build")
    assert "skippedRadios++" in build and "skippedNvrs++" in build
    find = method_body("SimpleJson", "findPort")
    assert "portIdx(port)" in find, "findPort matches a missing index as 0"
    build = method_body("Snapshot", "build")
    assert "skippedDevices++" in build and "skippedPorts++" in build \
        and "skippedCameras++" in build, "the snapshot does not count skips"
    print("test_inventory_guard: all assertions passed")


if __name__ == "__main__":
    main()
