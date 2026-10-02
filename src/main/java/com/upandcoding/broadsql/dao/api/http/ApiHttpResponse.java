package com.upandcoding.broadsql.dao.api.http;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The result of an {@link ApiHttpTransport#send} call - status, headers, raw body, and duration.
 *
 * <p>The body is captured as raw bytes ({@link #getBodyBytes()}) - {@link #getBody()} is a UTF-8-decoded
 * convenience view for the common (textual) case, but a caller deciding whether a response is safe to
 * render as text (sub-sprint 4's response renderer) should check {@link #getBodyBytes()}{@code .length}
 * for an accurate byte count rather than trust a lossy string's character count, and should generally
 * decide binary-vs-text from the response's {@code Content-Type} header before ever calling
 * {@link #getBody()} on a genuinely binary response.
 */
public class ApiHttpResponse {

	private final int statusCode;
	private final Map<String, String> headers;
	private final byte[] bodyBytes;
	private final long durationMillis;

	public ApiHttpResponse(int statusCode, Map<String, String> headers, byte[] bodyBytes, long durationMillis) {
		this.statusCode = statusCode;
		this.headers = headers;
		this.bodyBytes = bodyBytes;
		this.durationMillis = durationMillis;
	}

	public int getStatusCode() {
		return statusCode;
	}

	public boolean isSuccessful() {
		return statusCode >= 200 && statusCode < 300;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public byte[] getBodyBytes() {
		return bodyBytes;
	}

	public String getBody() {
		return new String(bodyBytes, StandardCharsets.UTF_8);
	}

	public long getDurationMillis() {
		return durationMillis;
	}
}
