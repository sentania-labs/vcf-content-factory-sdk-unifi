#!/usr/bin/env python3
"""Build 15 (issue #22): every per-site request path goes through the
encoder (UniFiIds.pathSegment, unit-tested in UniFiIdsTest); no site name
is concatenated into a path raw. Stdlib only; run from anywhere
(ci/run_java_tests.sh runs it).
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiApiClient.java").read_text()


def main():
    raw = [m for m in re.findall(r'"/proxy/network/api/s/"\s*\+\s*([\w.]+)', SRC)
           if m != "UniFiIds.pathSegment"]
    assert raw == [], f"site concatenated into a path unencoded: {raw}"
    assert re.search(r'"/proxy/network/api/s/"\s*\+\s*UniFiIds\.pathSegment\(site\)',
                     SRC), "sitePath does not encode the site"
    per_site = re.findall(r'get\(sitePath\(site, "([^"]+)"\)\)', SRC)
    assert "stat/device" in per_site and "stat/health" in per_site, per_site
    print("test_site_path: all assertions passed")


if __name__ == "__main__":
    main()
