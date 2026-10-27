#!/usr/bin/env python3
"""Build 16 (issue #30): PoE|poe_budget_used_pct on UniFiSwitch.

The calculation and its not-published cases are unit-tested in
UniFiValuesTest.poeBudgetUsedPct. This pins the wiring the SDK-bound
collector cannot be unit-tested for: collectSwitch publishes the key only
through metricIfPresent (so a null, a switch with no budget, publishes
nothing), only inside the block guarded by perf and a reported draw
(brace-matched, so a publish after the closing brace fails; the same check
covers poe_consumption and poe_budget_remaining), from
the summed consumption and total_max_power; and describe.xml declares it in
the switch's PoE group as a % metric with a label in resources.properties.
Stdlib only; run from anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiAdapter.java").read_text()
DESCRIBE = (ROOT / "describe.xml").read_text()
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


def block_body(text, opener):
    """Brace-match the block opened by `opener` (which ends in "{") and
    return its body, so a check can prove a call is INSIDE the block, not
    merely after its `if` text."""
    assert text.count(opener) == 1, f"want one {opener!r}, got {text.count(opener)}"
    depth, start = 1, text.index(opener) + len(opener)
    i = start
    while depth:
        depth += {"{": 1, "}": -1}.get(text[i], 0)
        i += 1
    return text[start:i - 1]


def main():
    body = method_body("void", "collectSwitch")
    calls = re.findall(r"(\w+)\(out,\s*\"PoE\|poe_budget_used_pct\",\s*([^;]+);", body)
    assert len(calls) == 1, f"want one publish of the key, got {calls}"
    helper, arg = calls[0]
    assert helper == "metricIfPresent", f"published via {helper}, not metricIfPresent"
    assert re.sub(r"\s+", "", arg) == "UniFiValues.poeBudgetUsedPct(poeConsumption,maxPower))", arg
    guarded = block_body(body, "if (perf && poeReported) {")
    outside = body.replace(guarded, "")
    # Every switch-level PoE draw metric is published only from a read draw
    # (closes build-15 N3 for poe_consumption / poe_budget_remaining too).
    for key in ("PoE|poe_consumption", "PoE|poe_budget_remaining",
                "PoE|poe_budget_used_pct"):
        assert guarded.count(f'"{key}"') == 1, \
            f"{key} is not published inside the perf && poeReported block"
        assert f'"{key}"' not in outside, \
            f"{key} is also published outside the perf && poeReported block"
    assert "poe_budget_used_pct" not in SRC.replace(body, ""), \
        "the key is published outside collectSwitch"

    switch = re.search(r'<ResourceKind key="UniFiSwitch".*?</ResourceKind>', DESCRIBE, re.S)
    assert switch, "UniFiSwitch kind not found"
    poe = re.search(r'<ResourceGroup key="PoE".*?</ResourceGroup>', switch.group(0), re.S)
    assert poe, "UniFiSwitch PoE group not found"
    attr = re.search(r'<ResourceAttribute key="poe_budget_used_pct"([^>]*)/>', poe.group(0))
    assert attr, "poe_budget_used_pct not declared in the switch PoE group"
    a = attr.group(1)
    assert 'unit="%"' in a and 'isProperty="false"' in a and 'dataType="float"' in a, a
    key = re.search(r'nameKey="(\d+)"', a).group(1)
    assert re.search(r"^" + key + r"=PoE Budget Used %$", PROPS, re.M), \
        f"nameKey {key} has no PoE Budget Used % label"
    assert len(re.findall(r'nameKey="' + key + r'"', DESCRIBE)) == 1, \
        f"nameKey {key} reused"
    print("test_poe_budget_used_pct: all assertions passed")


if __name__ == "__main__":
    main()
