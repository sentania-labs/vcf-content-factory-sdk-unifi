#!/usr/bin/env python3
"""Build 15 (issue #18): the site's device_count, version and timezone.

device_count is counted from the snapshot's stat/device for the site, not
read from the /self/sites entry; version and timezone come from
stat/sysinfo and are published only when present. The checks are
structural because collectSite needs the SDK jar; stdlib only, run from
anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiAdapter.java").read_text()
CLIENT = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiApiClient.java").read_text()
DESCRIBE = (ROOT / "describe.xml").read_text()


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
    site = method_body("void", "collectSite")
    assert 'site.get("device_count")' not in site, \
        "device_count is still read from the /self/sites entry"
    assert "s.devicesForSite(siteName)" in site and '"adopted"' in site, \
        "device_count is not counted from the site's adopted devices"
    assert site.index("requireSiteRead") < site.index("devicesForSite"), \
        "device_count could count an unread site's (empty) device list"
    for key in ("version", "timezone"):
        assert re.search(r'if \(!' + key + r'\.isEmpty\(\)\) prop\(out, '
                         r'"Configuration\|' + key + '"', site), \
            f"{key} is not published (only when present)"
        assert f'<ResourceAttribute key="{key}"' in DESCRIBE, \
            f"{key} missing from describe.xml"
    assert 'sitePath(site, "stat/sysinfo")' in CLIENT, "no stat/sysinfo call"
    build = method_body("Snapshot", "build")
    assert "api.statSysinfo(siteName)" in build, "sysinfo not read per site"
    print("test_site_properties: all assertions passed")


if __name__ == "__main__":
    main()
