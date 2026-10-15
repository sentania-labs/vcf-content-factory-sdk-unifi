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
		certificateReviewOfferedWhileSecure();
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

	/**
	 * Review W1 (fork issue #33): Validate Connection offers the controller
	 * certificate for review exactly while validation is on, at the address
	 * the client connects to; nothing to review for an insecure or
	 * incomplete config.
	 */
	static void certificateReviewOfferedWhileSecure() {
		T.eq(java.util.List.of("https://udm.lab:443/"),
				UniFiConfig.certificateCheckUrls("udm.lab", null, null),
				"absent port and insecure: 443, reviewed");
		T.eq(java.util.List.of("https://udm.lab:443/"),
				UniFiConfig.certificateCheckUrls(" udm.lab ", "", "false"),
				"host trimmed, blank port is 443");
		T.eq(java.util.List.of("https://10.0.0.1:8443/"),
				UniFiConfig.certificateCheckUrls("10.0.0.1", "8443", "false"),
				"the configured port");
		T.eq(java.util.List.of("https://udm.lab:443/"),
				UniFiConfig.certificateCheckUrls("udm.lab", "443", " true"),
				"' true' validates (strict parse), so it is reviewed");
		for (String v : new String[] {"true", "TRUE", "True"}) {
			T.eq(0, UniFiConfig.certificateCheckUrls("udm.lab", "443", v)
					.size(), v + ": insecure, nothing to review");
		}
		T.eq(0, UniFiConfig.certificateCheckUrls(null, "443", null).size(),
				"no host");
		T.eq(0, UniFiConfig.certificateCheckUrls("  ", "443", null).size(),
				"blank host");
		T.eq(0, UniFiConfig.certificateCheckUrls("udm.lab", "https", null)
				.size(), "non-numeric port");
		T.eq(0, UniFiConfig.certificateCheckUrls("udm.lab", "70000", null)
				.size(), "port out of range");
		T.check(UniFiConfig.certificateCheckUrls("udm.lab", "8443", null)
				.get(0).startsWith(new UniFiConfig("udm.lab", "8443", "u", "p",
						null).baseUrl()),
				"the reviewed URL is the address the client connects to");
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
