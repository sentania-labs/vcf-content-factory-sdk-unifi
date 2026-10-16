#!/usr/bin/env python3
"""Reviewer blocker on 16ade92: the snapshot cache's logger is resolved once.

The framework's componentLogger() resets the logger to INFO on every call,
so a per-failure componentLogger(SnapshotCache.class) made the cache's DEBUG
line unreachable. The adapter must resolve it once in configureAdapter,
store it in the Configured holder and hand its debug method to the cache.
Build 15 (issue #34): the same holds for the UniFiApiClient logger. It is
resolved once in configureAdapter, stored on Configured and handed to the
login callback, never looked up inside the callback (once per login) or
twice per Test Connection.
Stdlib only; run from anywhere (ci/run_java_tests.sh runs it).
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
    calls = SRC.count("componentLogger(SnapshotCache.class)")
    assert calls == 1, f"componentLogger(SnapshotCache.class) appears {calls} times"
    configure = method_body("void", "configureAdapter")
    assert "componentLogger(SnapshotCache.class)" in configure, \
        "the snapshot logger is not resolved in configureAdapter"
    assert re.search(r"new Configured\([^;]*snapshotLog,", configure, re.S), \
        "the resolved logger is not stored in the Configured holder"
    assert "snapshotLog::debug" in configure, \
        "the cache is not given the stored logger's debug method"
    current = method_body("Snapshot", "currentSnapshot")
    assert "componentLogger" not in current, \
        "currentSnapshot resolves a logger per call"
    api_logger_once()
    print("test_snapshot_logger_once: all assertions passed")


def api_logger_once():
    lookup = "componentLogger(UniFiApiClient.class)"
    configure = method_body("void", "configureAdapter")
    assert configure.count(lookup) == 1, \
        "the API client logger is not resolved exactly once in configureAdapter"
    assert re.search(r"new Configured\([^;]*apiLog,", configure, re.S), \
        "the API client logger is not stored in the Configured holder"
    assert "buildHttpClient(cfg, apiLog)" in configure, \
        "configureAdapter does not hand the resolved logger to the login callback"
    build = method_body("HttpClients", "buildHttpClient")
    assert "componentLogger" not in build, \
        "buildHttpClient (the login callback) looks a logger up per login"
    tester = method_body("VcfCfTester<UniFiConfig>", "getTester")
    assert tester.count(lookup) == 1, \
        f"Test Connection looks the API client logger up {tester.count(lookup)} times"
    assert "c.apiLog" in tester, \
        "Test Connection on a configured instance does not reuse the stored logger"
    total = SRC.count(lookup)
    assert total == 2, f"{lookup} appears {total} times (want configure + tester)"


if __name__ == "__main__":
    main()
