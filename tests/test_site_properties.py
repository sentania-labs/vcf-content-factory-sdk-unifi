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
    assert "if (isDiscovered(dev)) adopted++;" in site, \
        "device_count does not use discovery's rule (review N8, peer 6, Codex P2)"
    assert 'dev.get("adopted")' not in site, "device_count keeps its own adopted rule"
    disc = method_body("boolean", "isDiscovered")
    assert "UniFiIds.discoveredDevice(" in disc and 'dev.get("adopted")' in disc
    assert "a.isNull() ? null : a.asBoolean())" in disc, \
        "isDiscovered does not hand the adopted flag to the rule (Codex P2)"
    for name, ret in (("enumerateResources", "void"),
                      ("buildRelationships", "Relationships"),
                      ("buildOwnPortIndex", r"Map<String, List<OwnPort>>")):
        assert "if (!isDiscovered(dev)) continue;" in method_body(ret, name), \
            f"{name} registers a device pending adoption (Codex P2)"
    nvr = method_body("void", "collectNvr")
    assert re.search(r'if \(UniFiIds\.mac\(cam\.get\("mac"\)\.asString\(\)\) != null\) cameraCount\+\+;', nvr), \
        "camera_count counts cameras the guard skips (peer review 6)"
    assert "cameras.size()" not in nvr
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
    # Peer review 4: once per build, through the gate, outside the site loop.
    assert build.count("api.statSysinfo(") == 1, "sysinfo asked more than once"
    assert "api.statSysinfo(sysinfoSite)" in build, "sysinfo not read once per build"
    assert "c.sysinfoGate.shouldAsk(" in build and "c.sysinfoGate.failed(" in build, \
        "a refused sysinfo is asked again every cycle"
    loop = build[build.index("for (String siteName : siteNames) {"):build.index("String sysinfoSite")]
    assert "statSysinfo" not in loop, "sysinfo is still asked per site"
    assert "s.sysinfo()" in site and "sysinfoForSite" not in SRC
    sys_try = build[build.index("api.statSysinfo(sysinfoSite)"):]
    sys_try = sys_try[:sys_try.index("catch (Exception e)")]
    assert re.search(r"catch \(InterruptedException e\) \{\s*throw e;", sys_try), \
        "the sysinfo catch swallows an interrupt (review N4)"
    print("test_site_properties: all assertions passed")


if __name__ == "__main__":
    main()
