#!/usr/bin/env python3
"""Issue #20 wiring the SDK-bound adapter cannot be unit-tested for.

The rule for which kinds are read in full is ReadKinds (unit-tested in
ReadKindsTest). This checks the adapter feeds it from the same walk that
registers the resources: every site's read, every registered device's state
and child table, the Protect bootstrap only inside the branch that has one,
and that kindsReadThisCycle answers only for the enumeration that just ran
on this thread. Stdlib only; ci/run_java_tests.sh runs it.
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
    assert enum.index("read.sitesRead();") > enum.index("currentSnapshot()")
    # Every site's stat/device, failed sites included (cool-downs are in
    # failedSites), before its devices are walked.
    site = enum.index("read.site(s.failedSites.containsKey(siteName),")
    assert site < enum.index("for (SimpleJson dev : siteDevices.asList())")
    assert "s.devicesBySite.containsKey(siteName) && siteDevices.isList()" in enum
    assert "read.deviceWithoutIdentity();" in enum
    assert 'read.gateway(ReadKinds.informing(dev.get("state").asString()));' in enum
    assert "portTable.isList(), portsNoIndex);" in enum
    assert "radioStats.isList(), radiosNoCode);" in enum
    # Protect is declared only inside the branch with a bootstrap and an NVR.
    protect = enum[enum.index("if (s.protect != null) {"):]
    assert "read.protect(nvrMac != null, cams.isList(), camerasNoMac);" in protect
    assert enum.count("read.protect(") == 1
    # Skipped records are counted, not silently dropped.
    assert "portsNoIndex++;" in enum and "radiosNoCode++;" in enum
    assert "camerasNoMac++;" in enum


def main():
    hooks()
    enumeration()
    print("test_vanished_wiring: all assertions passed")


if __name__ == "__main__":
    main()
