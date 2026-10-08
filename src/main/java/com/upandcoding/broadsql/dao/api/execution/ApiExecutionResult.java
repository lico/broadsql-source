package com.upandcoding.broadsql.dao.api.execution;

import java.util.Map;

import com.upandcoding.broadsql.dao.api.http.ApiHttpResponse;

/**
 * The outcome of executing one endpoint - docs/SPRINT XT02 - Universal API Client.md, section 21.7/21.8:
 * method, the *safe* (secret-redacted) URL, status, headers, raw body, duration, and content type. A
 * non-2xx HTTP status is a normal, successful {@code ApiExecutionResult} - not an exception - since an
 * HTTP error response is valid data a user needs to see (section 21.4/21.8). Only a failure to obtain any
 * HTTP response at all (a resolution error, a policy refusal, an authentication failure, a network error)
 * is a {@code BroadSQLException} thrown by {@link ApiEndpointExecutor} before this object ever exists.
 *
 * <p>Deliberately holds no rendering logic itself - see {@link ApiResponseRenderer}.
 */
public class ApiExecutionResult {

	private final String method;
	private final String safeUrl;
	private final ApiHttpResponse response;

	public ApiExecutionResult(String method, String safeUrl, ApiHttpResponse response) {
		this.method = method;
		this.safeUrl = safeUrl;
		this.response = response;
	}

	public String getMethod() {
		return method;
	}

	/** The request URL with any secret-bearing query value redacted - see {@link ApiUrlRedactor}. Safe to print, log, or store in command history. */
	public String getSafeUrl() {
		return safeUrl;
	}

	public int getStatusCode() {
		return response.getStatusCode();
	}

	public Map<String, String> getHeaders() {
		return response.getHeaders();
	}

	public String getBody() {
		return response.getBody();
	}

	public byte[] getBodyBytes() {
		return response.getBodyBytes();
	}

	public long getDurationMillis() {
		return response.getDurationMillis();
	}

	public String getContentType() {
		return getHeaders().entrySet().stream()
				.filter(e -> e.getKey().equalsIgnoreCase("Content-Type"))
				.map(Map.Entry::getValue)
				.findFirst().orElse(null);
	}
}
