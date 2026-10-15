package com.vcfcf.adapters.unifi;

import java.io.IOException;

/**
 * Build 14 (issue #11): a non-200 answer from the UniFi controller, with its
 * HTTP status kept as a field so the snapshot cache can tell an
 * authentication-class refusal (401, 403, 429) from any other failure
 * without parsing message text. The message carries the redacted path and
 * the status only, never a request body or a cookie.
 */
final class UniFiHttpException extends IOException {

	private static final long serialVersionUID = 1L;

	private final int status;
	private final boolean login;

	UniFiHttpException(String message, int status, boolean login) {
		super(message);
		this.status = status;
		this.login = login;
	}

	/** The HTTP status the controller answered with. */
	int status() {
		return status;
	}

	/** True when the refused call was the login POST. */
	boolean isLogin() {
		return login;
	}
}
