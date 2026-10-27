#!/usr/bin/env python3
"""Build 17 (review B1, W1, W2 on build 16): present-only status properties.

UniFiValuesTest.flagAbsentIsNotFalse and recordingModeText unit-test the
value decisions. This pins the wiring the SDK-bound collectors cannot be
unit-tested for:
- W2: Status|up and Status|is_uplink (switch port), Status|is_connected and
  Status|is_recording (camera) go through flagPropIfPresent, so an absent
  field publishes nothing instead of "false"; no collector folds a field
  through asBoolean() into a "true"/"false" property any more, except
  Hardware|is_wireless, whose null on wired cameras means not wireless.
- B1: the WAN interface publishes Status|up the same way.
- W1: the camera publishes Configuration|recording_mode from
  recordingSettings.mode through textPropIfPresent.
- the helpers publish only a non-null value; describe.xml declares the new
  keys as string properties with labels.
Stdlib only; run from anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

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


def publishes(body, key):
    """Every call in `body` that publishes `key`: (helper, argument text)."""
    return [(h, re.sub(r"\s+", "", a)) for h, a in re.findall(
        r"(\w+)\(out,\s*\"" + re.escape(key) + r"\",\s*([^;]+)\);", body)]


def main():
    expected = {
        "collectSwitchPort": {"Status|up": 'port.get("up")',
                              "Status|is_uplink": 'port.get("is_uplink")'},
        "collectCamera": {"Status|is_connected": 'cam.get("isConnected")',
                          "Status|is_recording": 'cam.get("isRecording")'},
        "collectWanInterface": {"Status|up": 'wan.get("up")'},
    }
    for name, keys in expected.items():
        body = method_body("void", name)
        for key, arg in keys.items():
            calls = publishes(body, key)
            assert calls == [("flagPropIfPresent", arg)], \
                f"{name} {key}: want one flagPropIfPresent(out, key, {arg}), got {calls}"

    cam = method_body("void", "collectCamera")
    calls = publishes(cam, "Configuration|recording_mode")
    assert calls == [("textPropIfPresent",
                      'cam.get("recordingSettings").get("mode")')], calls
    # Status|duplex: Full/Half only from a read full_duplex.
    port = method_body("void", "collectSwitchPort")
    assert 'UniFiValues.flag(port.get("full_duplex").asString())' in port
    assert re.search(r'if \(fullDuplex != null\) \{\s*prop\(out, "Status\|duplex"', port), \
        "Status|duplex published when full_duplex is absent"
    # No Status| property (the group the bundled symptoms read) folds an
    # absent field through asBoolean() into "true"/"false".
    for name in ("collectSwitchPort", "collectCamera", "collectWanInterface"):
        body = method_body("void", name)
        for m in re.finditer(r'prop\(out,\s*"(Status\|[^"]+)",[^;]*asBoolean\(\)', body):
            sys.exit(f"FAIL: {name} folds an absent field into a boolean: {m.group(1)}")

    flag = method_body("void", "flagPropIfPresent")
    assert " ".join(flag.split()) == ("String value = UniFiValues.flag(field.asString()); "
                                      "if (value != null) prop(out, key, value); }"), flag
    text = method_body("void", "textPropIfPresent")
    assert " ".join(text.split()) == ("String value = UniFiValues.text(field.asString()); "
                                      "if (value != null) prop(out, key, value); }"), text

    root = ET.fromstring(DESCRIBE)
    ns = {"d": root.tag[1:].split("}")[0]} if root.tag.startswith("{") else {}
    pre = "d:" if ns else ""

    def attr(kind, group, key):
        rk = root.find(f".//{pre}ResourceKind[@key='{kind}']", ns)
        assert rk is not None, kind
        g = rk.find(f"{pre}ResourceGroup[@key='{group}']", ns)
        assert g is not None, f"{kind} has no {group} group"
        a = g.find(f"{pre}ResourceAttribute[@key='{key}']", ns)
        assert a is not None, f"{kind} {group}|{key} not declared"
        return g, a

    for kind, group, key, label in (
            ("UniFiWanInterface", "Status", "up", "Link Up"),
            ("UniFiCamera", "Configuration", "recording_mode", "Recording Mode")):
        g, a = attr(kind, group, key)
        assert a.get("dataType") == "string" and a.get("isProperty") == "true", \
            f"{kind} {group}|{key} is not a string property"
        assert re.search(r"^" + a.get("nameKey") + "=" + re.escape(label) + "$",
                         PROPS, re.M), f"no label for {key}"
        assert re.search(r"^" + g.get("nameKey") + "=" + group + "$", PROPS, re.M), \
            f"no label for {kind} {group} group"
    keys = re.findall(r'nameKey="(\d+)"', DESCRIBE)
    dup = sorted({k for k in keys if keys.count(k) > 1})
    assert not dup, f"duplicate nameKeys in describe.xml: {dup}"
    print("test_present_only_flags: all checks passed")


if __name__ == "__main__":
    main()
