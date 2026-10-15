package com.vcfcf.adapters.unifi;

import java.util.Collections;
import java.util.List;

public final class UniFiConfig {
	public final String host;
	public final String port;
	public final String username;
	public final String password;
	public final boolean allowInsecure;

	public UniFiConfig(String host, String port, String username,
			String password, String allowInsecure) {
		this.host = host;
		this.port = (port == null || port.isEmpty()) ? "443" : port;
		this.username = username;
		this.password = password;
		this.allowInsecure = parseAllowInsecure(allowInsecure);
	}

	/**
	 * Build 14 (issue #13): only the exact value {@code "true"} (any letter
	 * case, no surrounding spaces) turns certificate validation off. Anything
	 * else, including {@code null}, blank, {@code "yes"} or {@code " true"},
	 * validates. The same strict reading as every build before 14, so no
	 * stored value changes meaning on upgrade; it is pinned here because the
	 * describe.xml default moved from {@code true} to {@code false}.
	 */
	static boolean parseAllowInsecure(String raw) {
		return "true".equalsIgnoreCase(raw);
	}

	/**
	 * Build 14 (review W1, fork issue #33): the controller endpoint VCF
	 * Operations offers for certificate review on Validate Connection, from
	 * the raw identifier values of the passed (possibly unsaved) config.
	 * Empty when the host is blank, the port is not a number from 1 to
	 * 65535, or Allow Insecure SSL is on, so the platform check stays off
	 * and the connection test reports the real problem. The insecure test is
	 * {@link #parseAllowInsecure} on the untrimmed value, the same one the
	 * client uses: a stored {@code " true"} connects securely, so it must
	 * get the dialog.
	 *
	 * @return {@code https://<host>:<port>/} (port 443 when blank), or empty
	 */
	static List<String> certificateCheckUrls(String host, String port,
			String allowInsecure) {
		if (parseAllowInsecure(allowInsecure)) return Collections.emptyList();
		if (host == null || host.trim().isEmpty()) {
			return Collections.emptyList();
		}
		String p = port == null ? "" : port.trim();
		int n = 443;
		if (!p.isEmpty()) {
			try {
				n = Integer.parseInt(p);
			} catch (NumberFormatException e) {
				return Collections.emptyList();
			}
			if (n < 1 || n > 65535) return Collections.emptyList();
		}
		return Collections.singletonList("https://" + host.trim() + ":" + n
				+ "/");
	}

	/**
	 * Build 14 (issue #13): the WARN logged on every configure while insecure
	 * mode is on, or {@code null} when the controller certificate is
	 * validated. No credential or token text, only the address.
	 */
	String insecureWarning() {
		if (!allowInsecure) return null;
		return "Allow Insecure SSL is true for " + host + ":" + port
				+ ": the controller certificate is not validated, so the UniFi "
				+ "credentials and session token go to whatever answers at that "
				+ "address. Set Allow Insecure SSL to false unless this is a lab "
				+ "console you accept that for.";
	}

	public String baseUrl() {
		return "https://" + host + ":" + port;
	}
}
