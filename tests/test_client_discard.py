#!/usr/bin/env python3
"""Build 15 (issue #24): HTTP clients and the stitcher are released.

buildHttpClient builds two clients: the authenticated one and a raw
login-only one its login callback posts the credentials with. Both are
returned together (HttpClients) and discarded together: in Test
Connection's finally, on reconfigure (VcfCfAdapter.onConfigure discards the
previous main client; the adapter releases only the login client and the
Suite API stitcher, review N2) and in onDiscard. The checks are structural because the
adapter class needs the SDK jar; stdlib only, run from anywhere
(ci/run_java_tests.sh runs it).
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
    build = method_body("HttpClients", "buildHttpClient")
    assert re.search(r"return new HttpClients\(.*,\s*rawHttp\)", build, re.S), \
        "buildHttpClient does not return the login-only client with the main one"

    pair = method_body("void", "discard")  # HttpClients.discard, first match
    assert "discardQuietly(main)" in pair and "discardQuietly(login)" in pair, \
        "HttpClients.discard does not release both clients"

    tester = method_body("VcfCfTester<UniFiConfig>", "getTester")
    fin = tester[tester.index("finally"):]
    assert re.search(r"testHttp\.discard\(\)", fin), \
        "Test Connection's finally does not discard the client pair"
    assert "HttpClients testHttp" in tester, \
        "Test Connection does not keep the pair, so the login client leaks"

    helper = method_body("void", "discardConfigured")
    assert "HttpClients.discardQuietly(c.clients.login)" in helper, \
        "discardConfigured does not release the configuration's login client"
    assert "c.clients.discard()" not in helper and "clients.main" not in helper, \
        "discardConfigured discards the main client the framework already released (review N2)"
    assert "st.discard()" in helper, \
        "discardConfigured does not release the Suite API stitcher"

    configure = method_body("void", "configureAdapter")
    first_build = configure.index("buildHttpClient(")
    assert 0 <= configure.find("discardConfigured()") < first_build, \
        "configureAdapter does not release the previous clients and stitcher first"
    assert "this.httpClient = clients.main" in configure

    discard = method_body("void", "onDiscard")
    assert "discardConfigured()" in discard and "super.onDiscard()" in discard, \
        "onDiscard does not release the login client, the stitcher and the main client"
    print("test_client_discard: all assertions passed")


if __name__ == "__main__":
    main()
