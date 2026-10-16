#!/usr/bin/env python3
"""Build 15 (issues #16 part 1 and #29): traffic counters and rates.

The WAN Traffic|tx_bytes / rx_bytes are cumulative counters, declared in
bytes (they were declared bytes/s). SwitchPort, Radio and WanInterface
also carry Traffic|tx_rate / rx_rate in bytes/s, read from UniFi's own
"-r" rate fields and published only when present; the counters stay.
Stdlib only; run from anywhere (ci/run_java_tests.sh runs it).
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "src/com/vcfcf/adapters/unifi/UniFiAdapter.java").read_text()
DESCRIBE = (ROOT / "describe.xml").read_text()
LABELS = (ROOT / "resources/resources.properties").read_text()


def kind(name):
    block = DESCRIBE[DESCRIBE.index(f'<ResourceKind key="{name}"'):]
    return block[:block.index("</ResourceKind>")]


def attr(block, key):
    m = re.search(r'<ResourceAttribute key="' + key + r'"[^>]*>', block, re.S)
    assert m, f"{key} missing"
    return m.group(0)


def main():
    for key in ("tx_bytes", "rx_bytes"):
        a = attr(kind("UniFiWanInterface"), key)
        assert 'unit="bytes"' in a, f"WAN {key} is not declared as a byte counter: {a}"
    for name in ("UniFiWanInterface", "UniFiSwitchPort", "UniFiRadio"):
        block = kind(name)
        for key in ("tx_bytes", "rx_bytes"):
            attr(block, key)  # the counters stay
        for key in ("tx_rate", "rx_rate"):
            a = attr(block, key)
            assert 'unit="bytes/s"' in a and 'isProperty="false"' in a, a
            nk = re.search(r'nameKey="(\d+)"', a).group(1)
            assert re.search(r"^" + nk + r"=(TX|RX) Rate$", LABELS, re.M), \
                f"{name} {key} has no label"
    reads = re.findall(r'metricIfPresent\(out, "Traffic\|(tx|rx)_rate",\s*'
                       r'([^;]+)\);', SRC)
    sources = sorted(src.replace("\n", "").replace("\t", "") for _, src in reads)
    assert len(reads) == 6, reads
    for src in sources:
        assert "_bytes-r" in src, f"a rate is not read from a -r field: {src}"
    print("test_traffic_units: all assertions passed")


if __name__ == "__main__":
    main()
