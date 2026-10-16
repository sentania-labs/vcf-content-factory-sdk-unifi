#!/usr/bin/env python3
"""Build 15 (issue #16): the two deliberately renamed keys.

Status|last_motion (epoch / 1000, labelled seconds) is replaced by
Status|seconds_since_last_motion (an age); Configuration|
recording_retention_mode (the millisecond duration as text) by the numeric
property Configuration|recording_retention_days. The old keys are gone
from the code and describe.xml, the new ones are declared with labels.
The value math is unit-tested in UniFiValuesTest. Stdlib only; run from
anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiAdapter.java").read_text()
DESCRIBE = (ROOT / "describe.xml").read_text()
LABELS = (ROOT / "resources/resources.properties").read_text()


def attr(key):
    m = re.search(r'<ResourceAttribute key="' + key + r'"[^>]*>', DESCRIBE, re.S)
    return m.group(0) if m else None


def label(attr_xml):
    key = re.search(r'nameKey="(\d+)"', attr_xml).group(1)
    m = re.search(r"^" + key + r"=(.*)$", LABELS, re.M)
    return m.group(1) if m else None


def main():
    for old in ("last_motion", "recording_retention_mode"):
        assert attr(old) is None, f"{old} still declared"
        assert f'|{old}"' not in SRC, f"{old} still published"

    motion = attr("seconds_since_last_motion")
    assert motion and 'isProperty="false"' in motion and 'unit="sec"' in motion
    assert label(motion) == "Seconds Since Last Motion", label(motion)
    assert '"Status|seconds_since_last_motion"' in SRC
    assert "UniFiValues.secondsSince(" in SRC

    retention = attr("recording_retention_days")
    assert retention and 'isProperty="true"' in retention \
        and 'dataType="float"' in retention, retention
    assert label(retention) == "Recording Retention (days)", label(retention)
    assert re.search(r'numericProp\(out, "Configuration\|recording_retention_days"', SRC)
    print("test_renamed_keys: all assertions passed")


if __name__ == "__main__":
    main()
