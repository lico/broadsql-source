package com.upandcoding.broadsql.dao.api.execution;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Produces a safe-to-display form of a request URI with any secret-bearing query parameter value
 * replaced by {@code ******} - docs/SPRINT XT02 - Universal API Client.md, section 21.6/21.10. Needed
 * because {@code API_KEY_QUERY} authentication places its secret directly in the URL (unlike a header,
 * which never appears in a rendered URL at all) - the real request still carries the real value; only the
 * *displayed* form (execution result, command history, diagnostics) is redacted.
 */
public final class ApiUrlRedactor {

	private ApiUrlRedactor() {
	}

	/**
	 * @param uri               the actual request URI (unredacted - this method never mutates it)
	 * @param secretParamNames  query parameter names whose values must be redacted (e.g. an
	 *                          {@code API_KEY_QUERY} auth's configured parameter name, or any endpoint
	 *                          {@code QUERY_PARAMETER} attribute marked {@code secret})
	 * @return the URI as a string, with each matching parameter's value replaced by {@code ******}
	 */
	public static String redact(URI uri, Set<String> secretParamNames) {
		if (secretParamNames.isEmpty() || uri.getRawQuery() == null || uri.getRawQuery().isEmpty()) {
			return uri.toString();
		}
		StringBuilder redactedQuery = new StringBuilder();
		for (String pair : uri.getRawQuery().split("&")) {
			if (redactedQuery.length() > 0) {
				redactedQuery.append('&');
			}
			int eq = pair.indexOf('=');
			String rawName = eq >= 0 ? pair.substring(0, eq) : pair;
			String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
			if (secretParamNames.contains(name)) {
				redactedQuery.append(rawName).append("=******");
			} else {
				redactedQuery.append(pair);
			}
		}
		String base = uri.toString();
		int queryIndex = base.indexOf('?');
		String withoutQuery = queryIndex >= 0 ? base.substring(0, queryIndex) : base;
		return withoutQuery + "?" + redactedQuery;
	}
}
