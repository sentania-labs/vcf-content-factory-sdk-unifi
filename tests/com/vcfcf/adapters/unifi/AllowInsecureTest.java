package com.vcfcf.adapters.unifi;

/**
 * Build 14 (issue #13): only "true" turns certificate validation off, and
 * the configure WARN is present exactly while it is off. Run from the repo
 * root (ci/run_java_tests.sh).
 */
public final class AllowInsecureTest {

	public static void main(String[] args) {
		strictParse();
		warningOnlyWhileInsecure();
		System.out.println("AllowInsecureTest: all assertions passed");
	}

	static void strictParse() {
		for (String v : new String[] {"true", "TRUE", "True"}) {
			T.check(UniFiConfig.parseAllowInsecure(v), v + " is insecure");
		}
		for (String v : new String[] {null, "", " ", "false", "FALSE", "yes",
				"1", "on", " true", "true ", "truee", "default"}) {
			T.check(!UniFiConfig.parseAllowInsecure(v),
					"<" + v + "> validates");
		}
		T.check(!new UniFiConfig("h", null, "u", "p", null).allowInsecure,
				"an absent identifier validates");
		T.check(new UniFiConfig("h", null, "u", "p", "true").allowInsecure,
				"an explicit true keeps working after upgrade");
	}

	static void warningOnlyWhileInsecure() {
		UniFiConfig insecure = new UniFiConfig("udm.lab", "8443", "svc",
				"s3cret-pw", "true");
		String w = insecure.insecureWarning();
		T.check(w != null, "insecure mode warns");
		T.check(w.contains("udm.lab:8443"), "the warning names the address");
		T.check(!w.contains("s3cret-pw") && !w.contains("svc"),
				"the warning carries no credential");
		T.eq(null, new UniFiConfig("udm.lab", null, "svc", "pw", "false")
				.insecureWarning(), "validated mode does not warn");
		T.eq(null, new UniFiConfig("udm.lab", null, "svc", "pw", null)
				.insecureWarning(), "an absent value does not warn");
	}
}
