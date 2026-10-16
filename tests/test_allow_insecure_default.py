#!/usr/bin/env python3
"""Build 14 (issue #13): Allow Insecure SSL defaults to false.

describe.xml is what a new adapter instance is created with; README.md,
REFERENCE.md and docs/installing.md must state the same default. Before
build 14 describe.xml and README said true while docs/installing.md said
false. Stdlib only. Run from anywhere: python3 tests/test_allow_insecure_default.py
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def describe_default():
    tree = ET.parse(os.path.join(ROOT, "describe.xml"))
    found = []
    for el in tree.iter():
        if el.tag.split("}")[-1] == "ResourceIdentifier" and \
                el.get("key") == "allowInsecure":
            found.append(el.get("default"))
    assert len(found) == 1, "one allowInsecure identifier, found %r" % found
    return found[0]


def doc_defaults(rel):
    """Default column of every table row naming Allow Insecure SSL."""
    out = []
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        for line in f:
            if line.startswith("| Allow Insecure SSL"):
                cells = [c.strip() for c in line.strip().strip("|").split("|")]
                out.append(cells)
    assert out, "%s has an Allow Insecure SSL row" % rel
    return out


def main():
    failures = []
    d = describe_default()
    if d != "false":
        failures.append("describe.xml allowInsecure default is %r, want 'false'" % d)

    # README: | Field | Description | Default |
    for cells in doc_defaults("README.md"):
        if cells[-1] != "false":
            failures.append("README.md default %r" % cells[-1])
    # REFERENCE.md: | Field | Key | Default | Required |
    for cells in doc_defaults("REFERENCE.md"):
        if cells[2] != "false":
            failures.append("REFERENCE.md default %r" % cells[2])
    # docs/installing.md: | Field | Key | Required | Default | Notes |
    for cells in doc_defaults("docs/installing.md"):
        if cells[3] != "false":
            failures.append("docs/installing.md default %r" % cells[3])

    with open(os.path.join(ROOT, "docs/installing.md"), encoding="utf-8") as f:
        text = f.read()
    if not re.search(r"existing instances keep their stored", text, re.I):
        failures.append("docs/installing.md has no upgrade note")

    if failures:
        for f in failures:
            print("FAIL: " + f)
        sys.exit(1)
    print("test_allow_insecure_default: all assertions passed")


if __name__ == "__main__":
    main()
