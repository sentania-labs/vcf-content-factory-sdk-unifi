package com.vcfcf.adapters.unifi;

import com.vcfcf.adapter.http.ManagedHttpClient;
import com.vcfcf.adapter.json.SimpleJson;

import com.integrien.alive.common.adapter3.Logger;

import java.io.IOException;
import java.net.http.HttpResponse;

/**
 * Wraps the UniFi Network (classic) and Protect REST APIs.
 *
 * <p><b>v2 logging.</b> The logger is the framework instance logger supplied via
 * {@code VcfCfAdapter.componentLogger(UniFiApiClient.class)} — a
 * {@code com.integrien.alive.common.adapter3.Logger} wired to the adapter
 * instance's log file and pinned to INFO. Never use {@code java.util.logging}
 * (its records do not reach the adapter log) and never shadow the framework
 * {@code adapterLogger()}.
 *
 * <p><b>Session auth.</b> UniFi OS authenticates with a {@code POST
 * /api/auth/login} (username/password JSON body) that returns a {@code TOKEN}
 * session cookie; the framework {@link com.vcfcf.adapter.auth.SessionCookieAuth}
 * re-presents that cookie on every request and re-runs {@link #login} on demand.
 *
 * <p><b>Secrets never leak.</b> The login body carries the plaintext password and
 * the session cookie carries the bearer token; any error/log path that could
 * surface a request path, body, or cookie value is passed through
 * {@link #redact(String)} first ({@code rules/no-secrets-on-disk.md}).
 */
public final class UniFiApiClient {

	private final ManagedHttpClient http;
	private final Logger log;

	public UniFiApiClient(ManagedHttpClient http, Logger log) {
		this.http = http;
		this.log = log;
	}

	// --- Network API endpoints ---

	public SimpleJson listSites() throws IOException, InterruptedException {
		return get("/proxy/network/api/self/sites");
	}

	public SimpleJson statDevice(String site) throws IOException, InterruptedException {
		return get("/proxy/network/api/s/" + site + "/stat/device");
	}

	public SimpleJson statHealth(String site) throws IOException, InterruptedException {
		return get("/proxy/network/api/s/" + site + "/stat/health");
	}

	// --- Protect API ---

	public SimpleJson protectBootstrap() throws IOException, InterruptedException {
		return get("/proxy/protect/api/bootstrap");
	}

	// --- HTTP ---

	private SimpleJson get(String path) throws IOException, InterruptedException {
		HttpResponse<String> resp = http.get(path, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() != 200) {
			throw new IOException("UniFi GET " + redact(path) + " returned HTTP "
					+ resp.statusCode());
		}
		return SimpleJson.parse(resp.body());
	}

	/** Login and return the session token value (for SessionCookieAuth). */
	public static String login(ManagedHttpClient rawHttp, Logger log,
			String username, String password)
			throws IOException, InterruptedException {
		String body = "{\"username\":\"" + jsonEscape(username)
				+ "\",\"password\":\"" + jsonEscape(password) + "\"}";
		HttpResponse<String> resp = rawHttp.post("/api/auth/login", body,
				"application/json", HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() != 200) {
			// Status only — never echo the request body (plaintext password).
			throw new IOException("UniFi login failed: HTTP " + resp.statusCode());
		}
		// UniFi OS sets TOKEN; classic controllers set unifises.
		String token = extractCookie(resp, "TOKEN");
		if (token == null) token = extractCookie(resp, "unifises");
		if (token == null) {
			throw new IOException("UniFi login: no session cookie in response");
		}
		if (log != null) log.info("UniFi session acquired");
		return token;
	}

	private static String extractCookie(HttpResponse<String> resp, String name) {
		return resp.headers().allValues("set-cookie").stream()
				.filter(c -> c.startsWith(name + "="))
				.map(c -> {
					int eq = c.indexOf('=');
					int semi = c.indexOf(';', eq);
					return semi > 0 ? c.substring(eq + 1, semi) : c.substring(eq + 1);
				})
				.findFirst()
				.orElse(null);
	}

	/**
	 * Mask secret-bearing values before a path/query/cookie string can reach the
	 * adapter log or a Test-connection error. UniFi authenticates with a session
	 * {@code TOKEN}/{@code unifises} cookie and (on login) a plaintext password in
	 * the request body. Redact any token cookie value or password parameter that
	 * could ride along on a logged path; the endpoint portion is left intact so the
	 * message still identifies the failing call.
	 */
	static String redact(String s) {
		if (s == null) return "";
		return s
				.replaceAll("(?i)(TOKEN=)[^;&\\s]*", "$1<redacted>")
				.replaceAll("(?i)(unifises=)[^;&\\s]*", "$1<redacted>")
				.replaceAll("(?i)(\"password\"\\s*:\\s*\")[^\"]*", "$1<redacted>")
				.replaceAll("(?i)(password=)[^&\\s]*", "$1<redacted>");
	}

	private static String jsonEscape(String s) {
		return s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}
}
