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
    find = method_body("SimpleJson", "findPort")
    assert "portIdx(port)" in find, "findPort matches a missing index as 0"
    build = method_body("Snapshot", "build")
    assert "skippedDevices++" in build and "skippedPorts++" in build \
        and "skippedCameras++" in build, "the snapshot does not count skips"
    print("test_inventory_guard: all assertions passed")


if __name__ == "__main__":
    main()
