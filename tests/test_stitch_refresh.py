#!/usr/bin/env python3
"""Build 15 (issue #21): the vmnic stitch host list is fetched through a
15-minute RefreshCache; the Protect bootstrap through one with refreshMs 0
(fetched every cycle, the last good one reused only when a fetch fails, for
at most an hour; unit-tested in RefreshCacheTest). The stitch reads host
properties in bulk with a per-host fallback (paths unit-tested in
VmnicLldpTest), and interrupts are not swallowed. Structural; stdlib only,
run from anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiAdapter.java").read_text()
ST = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiStitcher.java").read_text()


def main():
    assert "SLOW_REFRESH_MS = 15L * 60_000L" in SRC
    assert "hostCache.get(\n\t\t\t\t\tst::listHostsWithVmnicLldp" in SRC, \
        "the stitch host list is not fetched through the refresh cache"
    assert "st.listHostsWithVmnicLldp()" not in SRC, \
        "the stitch host list is still fetched on every snapshot"
    assert "c.protectBootstrap.get(api::protectBootstrap" in SRC, \
        "the Protect bootstrap is not fetched through the refresh cache"
    assert "api.protectBootstrap()" not in SRC, \
        "the Protect bootstrap is still fetched on every snapshot"
    assert re.search(r"final RefreshCache<List<UniFiStitcher.ForeignHost>> vmnicHosts", SRC)
    assert re.search(r"final RefreshCache<SimpleJson> protectBootstrap", SRC)
    assert re.search(r"RefreshCache<List<UniFiStitcher.ForeignHost>> vmnicHosts =\s*"
                     r"new RefreshCache<>\(System::currentTimeMillis, SLOW_REFRESH_MS,", SRC), \
        "the host list is not on the 15-minute refresh"
    assert re.search(r"RefreshCache<SimpleJson> protectBootstrap =\s*"
                     r"new RefreshCache<>\(System::currentTimeMillis, PROTECT_REFRESH_MS,", SRC), \
        "the Protect bootstrap is not on its own (every cycle) refresh"
    assert "PROTECT_REFRESH_MS = 0L;" in SRC, \
        "the Protect bootstrap would be served from cache between cycles"

    assert "VmnicLldp.bulkPropertiesPaths(ids, VmnicLldp.BULK_BATCH)" in ST
    loop = ST[ST.index("for (int b = 0; b < paths.size(); b++)"):]
    assert "fetchVmnicNeighbours(h.identifier, h.name)" in loop, \
        "no per-host fallback for a failed bulk batch"
    assert '"resourcePropertiesList"' in ST
    assert "unread == pending.size()" in ST, \
        "a refresh that read no host would be cached as good"
    for body in (ST, SRC):
        assert "catch (InterruptedException e) {\n\t\t\tthrow e;" in body \
            or "catch (InterruptedException e) {\n\t\t\t\tthrow e;" in body
    print("test_stitch_refresh: all assertions passed")


if __name__ == "__main__":
    main()
