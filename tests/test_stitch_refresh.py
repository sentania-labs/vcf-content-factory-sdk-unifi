#!/usr/bin/env python3
"""Build 15 (issue #21): the vmnic stitch host list is fetched through a
15-minute RefreshCache (unit-tested in RefreshCacheTest). The Protect
bootstrap is not cached at all (review B1): fetched every snapshot, absent
for the cycle on failure, as in build 14. The stitch reads host properties
in bulk with a per-host fallback (paths unit-tested in VmnicLldpTest), and
interrupts are not swallowed. Structural; stdlib only, run from anywhere
(ci/run_java_tests.sh runs it).
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
    assert "SimpleJson protect = api.protectBootstrap();" in SRC, \
        "the Protect bootstrap is not fetched on every snapshot"
    assert "protectBootstrap.get(" not in SRC and "RefreshCache<SimpleJson>" not in SRC \
        and "PROTECT_REFRESH_MS" not in SRC, \
        "the Protect bootstrap is cached (a stale bootstrap would publish as current)"
    assert re.search(r"final RefreshCache<List<UniFiStitcher.ForeignHost>> vmnicHosts =\s*"
                     r"new RefreshCache<>\(System::currentTimeMillis, SLOW_REFRESH_MS,", SRC), \
        "the host list is not on the 15-minute refresh"

    assert "VmnicLldp.bulkPropertiesPaths(ids, VmnicLldp.BULK_BATCH)" in ST
    loop = ST[ST.index("for (int b = 0; b < paths.size(); b++)"):]
    assert "fetchVmnicNeighbours(h.identifier, h.name)" in loop, \
        "no per-host fallback for a failed bulk batch"
    assert '"resourcePropertiesList"' in ST
    # Peer review 1 and 2.
    assert "VmnicLldp.missingFromAnswer(batch, byId.keySet())" in ST, \
        "a host missing from a bulk answer is taken as read with no neighbours"
    assert "if (perHost.contains(h.identifier)) {" in ST
    assert "getOrDefault(h.identifier" not in ST, \
        "an omitted host defaults to no neighbours"
    assert 'parsed.get("pageInfo").get("totalCount")' in ST \
        and "list != null && list.isList(), totalCount)) {" in ST, \
        "the host list check does not get the answer's pageInfo.totalCount"
    assert "VmnicLldp.hostListPresent(" in ST and '"totalCount"' in ST, \
        "a Call A answer without resourceList is cached as an empty host list"
    assert "if (list == null || !list.isList()) return out;" not in ST
    assert "unread == pending.size()" in ST, \
        "a refresh that read no host would be cached as good"
    assert re.search(r"if \(unread > 0\) \{\s*throw new RefreshCache\.Partial\(", ST), \
        "a partly failed refresh would be cached for 15 minutes (review N5)"
    for body in (ST, SRC):
        assert "catch (InterruptedException e) {\n\t\t\tthrow e;" in body \
            or "catch (InterruptedException e) {\n\t\t\t\tthrow e;" in body
    print("test_stitch_refresh: all assertions passed")


if __name__ == "__main__":
    main()
