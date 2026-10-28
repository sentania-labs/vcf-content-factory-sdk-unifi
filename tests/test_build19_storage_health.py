#!/usr/bin/env python3
"""Build 19 wiring: NVR storage health the SDK-bound adapter cannot be
unit-tested for. collectNvr calls collectNvrStorageHealth, which reads
nvr.systemInfo.ustorage (space[], disks[]) and nvr.hardDriveState through
NvrStorageHealth (unit-tested in NvrStorageHealthTest); every key it
publishes is declared in describe.xml with a label, with the right
metric/property type. Stdlib only; ci/run_java_tests.sh runs it.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiAdapter.java").read_text()
DESCRIBE = ROOT / "describe.xml"
PROPS = (ROOT / "resources/resources.properties").read_text()


def method_body(ret, name):
    m = re.search(r"\b" + ret + r"\s+" + name + r"\s*\([^)]*\)[^{;]*\{", SRC)
    if not m:
        sys.exit(f"FAIL: method {name} not found")
    depth, i = 1, m.end()
    while depth:
        depth += {"{": 1, "}": -1}.get(SRC[i], 0)
        i += 1
    return SRC[m.end():i]


PROPERTIES = ("volume_health", "volume_issues", "volume_action", "volume_device",
              "unhealthy_drive_slot", "unhealthy_drive_state", "hard_drive_state")
METRICS = ("drive_count", "drives_not_healthy", "drives_failed", "drive_bad_sectors")


def main():
    assert "collectNvrStorageHealth(nvr, out);" in method_body("void", "collectNvr")
    body = method_body("void", "collectNvrStorageHealth")
    assert 'nvr.get("systemInfo").get("ustorage")' in body
    assert 'ustorage.get("space")' in body and 'ustorage.get("disks")' in body
    assert "NvrStorageHealth.primary(" in body and "NvrStorageHealth.drives(" in body
    assert 'nvr.get("hardDriveState")' in body
    assert "usage" not in body, "health never reads fullness"
    for key in PROPERTIES:
        assert re.search(r'(prop|textPropIfPresent)\(out, "Storage\|' + key + '"', body), key
    for key in METRICS:
        assert re.search(r'metric(IfPresent)?\(out, "Storage\|' + key + '"', body), key
    # The drive summary publishes only inside the null guard.
    guarded = body[body.index("if (drives != null) {"):]
    for key in METRICS + ("unhealthy_drive_slot", "unhealthy_drive_state"):
        assert '"Storage|' + key + '"' in guarded, key + " outside the guard"

    root = ET.parse(DESCRIBE).getroot()
    ns = "{http://schemas.vmware.com/vcops/schema}"
    kinds = list(root.iter(ns + "ResourceKind")) or list(root.iter("ResourceKind"))
    nvr = next(k for k in kinds if k.get("key") == "UniFiNvr")
    storage = next(g for g in nvr if g.get("key") == "Storage")
    attrs = {a.get("key"): a for a in storage}
    labels = dict(re.findall(r"^(\d+)=(.+)$", PROPS, re.M))
    for key in PROPERTIES + METRICS:
        a = attrs.get(key)
        assert a is not None, key + " not declared"
        assert a.get("isProperty") == ("true" if key in PROPERTIES else "false"), key
        assert a.get("dataType") == ("string" if key in PROPERTIES else "float"), key
        assert a.get("nameKey") in labels, key + " has no label"
    keys = [a.get("nameKey") for a in root.iter() if a.get("nameKey")]
    dupes = {k for k in keys if keys.count(k) > 1}
    # nameKeys repeat across kinds only when labels are shared; the new ones are unique.
    for key in PROPERTIES + METRICS:
        assert attrs[key].get("nameKey") not in dupes, key + " nameKey reused"
    print("test_build19_storage_health: ok")


if __name__ == "__main__":
    main()
