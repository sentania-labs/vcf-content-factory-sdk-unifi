#!/usr/bin/env python3
"""Build 15 (issue #14): absent is not zero, and device state.

The value decisions are unit-tested in UniFiValuesTest. This pins the
wiring the SDK-bound collectors cannot be unit-tested for: no collector
publishes SimpleJson.asDouble() (0.0 for an absent field) or the removed
parseDouble("0") default; the gateway, switch and access point publish
Status|state and skip their metrics when disconnected; their ports, radios
and WAN interfaces skip theirs too; and describe.xml declares the state.
Stdlib only; run from anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiAdapter.java").read_text()
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


COLLECTORS = ["collectSite", "collectGateway", "collectWanInterface",
              "collectSwitch", "collectSwitchPort", "collectAccessPoint",
              "collectRadio", "collectWirelessAggregate", "collectNvr",
              "collectCamera"]


def main():
    assert "parseDouble(" not in SRC, "the 0-default parseDouble is back"
    for name in COLLECTORS:
        body = method_body("void", name)
        assert ".asDouble()" not in body, f"{name} publishes asDouble() (0.0 when absent)"
        for m in re.finditer(r"\bmetric\(out,\s*\"[^\"]+\",\s*([^;]+)\);", body):
            arg = m.group(1)
            assert "asLong()" not in arg, f"{name} publishes asLong(): {arg}"

    for name in ("collectGateway", "collectSwitch", "collectAccessPoint"):
        body = method_body("void", name)
        assert "publishState(out, dev)" in body, f"{name} does not publish state"
    for name in ("collectWanInterface", "collectSwitchPort", "collectRadio"):
        body = method_body("void", name)
        assert "UniFiValues.publishesPerformance(deviceConnected(dev))" in body, \
            f"{name} publishes metrics while its device is disconnected"

    # Review N3: the switch PoE metrics are gated on the switch's state too.
    switch = method_body("void", "collectSwitch")
    assert 'if (perf) metricIfPresent(out, "PoE|total_max_power", maxPower);' in switch, \
        "total_max_power published while the switch is disconnected"
    assert "if (perf && poeReported) {" in switch, \
        "PoE consumption and budget published while the switch is disconnected"
    # Review N1: absent numeric properties read blank, never "0".
    for key in ("Status|speed", "Configuration|min_txpower",
                "Configuration|max_txpower"):
        m = re.search(r'prop\(out, "' + re.escape(key) + r'",\s*([^;]+)\);', SRC)
        assert m and 'asString("")' in m.group(1) and "asLong()" not in m.group(1), \
            f"{key} reads 0 when absent"
    assert 'wired.get("phyRate").asString("")' in SRC, "phy_rate reads 0 when absent"

    state = method_body("boolean", "publishState")
    assert '"Status|state"' in state and "stateMetric(connected)" in state

    for kind in ("UniFiGateway", "UniFiSwitch", "UniFiAccessPoint"):
        block = DESCRIBE[DESCRIBE.index(f'<ResourceKind key="{kind}"'):]
        block = block[:block.index("</ResourceKind>")]
        assert re.search(r'<ResourceGroup key="Status"[^>]*>\s*'
                         r'(?:<!--.*?-->\s*)?<ResourceAttribute key="state"[^>]*'
                         r'isProperty="false"', block, re.S), \
            f"{kind} does not declare the Status|state metric"
    print("test_present_only: all assertions passed")


if __name__ == "__main__":
    main()
