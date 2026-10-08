package com.upandcoding.broadsql.dao.api.http;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A generic, provider-agnostic HTTP request being assembled - SPRINT XT02 (Universal API Client),
 * sub-sprint 3. Deliberately knows nothing about authentication schemes, Bruno, or any specific API - it
 * is mutated by whatever is preparing a request (today, {@code ApiAuthenticationRuntime}; from sub-sprint
 * 4 onward, the endpoint request resolver too) and then handed to {@link ApiHttpTransport#send}.
 *
 * <p><b>Deliberately has no {@code toString()} override</b> - the default identity-based one is the safe
 * choice, since headers/query parameters routinely carry resolved secrets (a Bearer token, an API key).
 * Never add one that renders {@link #headers}/{@link #queryParameters}/{@link #body} verbatim.
 *
 * <p><b>Header names are case-insensitive</b> (XT02 final corrective patch, Codex finding 1) - HTTP
 * itself treats {@code Content-Type}/{@code content-type}/{@code CONTENT-TYPE} as the same header, so
 * {@link #headers} is a {@link TreeMap} keyed by {@link String#CASE_INSENSITIVE_ORDER} rather than a
 * plain {@code LinkedHashMap}. This is enforced once, generically, in the shared model - not as a
 * Content-Type-specific workaround - so every caller that sets a header (API-level, group/folder-level,
 * endpoint-level in {@code ApiEndpointRequestBuilder}, or authentication-generated in
 * {@code ApiAuthenticationRuntime}) already gets correct case-insensitive replacement with no code change
 * of its own: a more specific scope's header, however differently cased, replaces the inherited one
 * rather than coexisting alongside it, and only one logical header is ever transmitted. The existing
 * precedence (API -&gt; group chain root-to-leaf -&gt; endpoint -&gt; authentication, applied last so it always
 * wins) is unchanged - only header *identity* changed, not *ordering*. The casing displayed for a header
 * is whichever call first established that logical header (a cosmetic detail only - header names are
 * transmitted byte-for-byte as stored, and no HTTP server distinguishes on casing).
 */
public class ApiHttpRequest {

	private String method = "GET";
	private URI uri;
	private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private final List<Map.Entry<String, String>> queryParameters = new ArrayList<>();
	private String body;

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public void setUri(URI uri) {
		this.uri = uri;
	}

	public void setHeader(String name, String value) {
		headers.put(name, value);
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public void addQueryParameter(String name, String value) {
		queryParameters.add(new AbstractMap.SimpleEntry<>(name, value));
	}

	public void setBody(String body) {
		this.body = body;
	}

	public String getBody() {
		return body;
	}

	/**
	 * The final request URI, with {@link #queryParameters} correctly URI-encoded and appended to any
	 * query string {@link #uri} already carries - never built by unsafe string concatenation.
	 */
	public URI buildUri() {
		if (queryParameters.isEmpty()) {
			return uri;
		}
		StringBuilder query = new StringBuilder();
		String existing = uri.getRawQuery();
		if (existing != null && !existing.isEmpty()) {
			query.append(existing);
		}
		for (Map.Entry<String, String> param : queryParameters) {
			if (query.length() > 0) {
				query.append('&');
			}
			query.append(urlEncode(param.getKey())).append('=').append(urlEncode(param.getValue()));
		}
		return buildUriWithQuery(query.toString());
	}

	/**
	 * Appends the already-percent-encoded {@code query} directly onto the base URI's string form - the
	 * multi-argument {@code URI(scheme, authority, path, query, fragment)} constructor was tried first and
	 * rejected: it treats its {@code query} argument as *unencoded* and percent-encodes it itself, which
	 * double-encodes a query this class already encoded (turning a literal {@code %26} into {@code %2526}) -
	 * confirmed by a real request received at a local test server. {@link URI#create(String)} does not
	 * re-encode a syntactically valid URI string, so it is used instead.
	 */
	private URI buildUriWithQuery(String query) {
		String base = uri.toString();
		int queryIndex = base.indexOf('?');
		String withoutQuery = queryIndex >= 0 ? base.substring(0, queryIndex) : base;
		return URI.create(withoutQuery + "?" + query);
	}

	private String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
