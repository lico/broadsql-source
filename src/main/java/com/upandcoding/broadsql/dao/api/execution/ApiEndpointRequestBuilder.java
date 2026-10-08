package com.upandcoding.broadsql.dao.api.execution;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiUrlQueryStringSync;
import com.upandcoding.broadsql.dao.api.ApiVariableResolver;
import com.upandcoding.broadsql.dao.api.ApiVariableSubstitutor;
import com.upandcoding.broadsql.dao.api.http.ApiHttpRequest;
import com.upandcoding.broadsql.dao.api.invocation.ApiPathTemplate;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * Builds a resolved {@link ApiHttpRequest} for one endpoint - docs/SPRINT XT02 - Universal API Client.md,
 * section 21.2-21.6. Deliberately separate from authentication ({@link
 * com.upandcoding.broadsql.dao.api.auth.ApiAuthenticationRuntime}, applied afterward, unchanged) and from
 * transport - this class only resolves variables and assembles URL/query/headers; nothing here knows how
 * to send a request or what any particular header is for.
 *
 * <p><b>URL resolution</b> (section 21.3) reuses the same variable-resolution mechanism as everything else
 * in this sprint - no special-cased {@code baseUrl} concatenation. An endpoint's path template is
 * substituted against the full resolved variable scope (which already includes the environment's
 * {@code baseUrl} as an ordinary variable, per section 16.2); if the result is not already an absolute
 * URL (an imported Bruno endpoint's template already is, e.g. {@code ${baseUrl}/users}), the environment's
 * {@code baseUrl} column is prefixed - covering a manually-created endpoint's bare relative path too.
 * {@link ApiPathTemplate#normalizeColonSegments} runs first, rewriting any bare {@code :name} colon-style
 * path segment (real Bruno {@code .bru} files use this natively for a path parameter, never converted to
 * {@code ${name}} by the importer - see {@link ApiPathTemplate}'s own class javadoc) to {@code ${name}}
 * form, since {@link ApiVariableSubstitutor#substitute} itself has no idea a colon-style segment is a
 * placeholder at all - SPRINT XT02B acceptance correction: without this, a resolved path-parameter value
 * was computed correctly for matching/binding purposes but the literal {@code :name} text still reached
 * the outgoing request URL, unsubstituted.
 *
 * <p><b>Path parameters</b> (section 21.5) are merged into the variable scope before URL substitution (an
 * enabled {@code PATH_PARAMETER} attribute becomes available as {@code ${name}} exactly like any other
 * variable, itself substituted first in case its own value references another variable), giving the
 * {@code PATH_PARAMETER}/{@code QUERY_PARAMETER} distinction introduced in sub-sprint 2 real execution
 * semantics. Only the path parameter's own resolved value is pre-encoded with
 * {@link ApiVariableSubstitutor#encodePathSegment} (path-segment rules - never the query-string
 * {@code +}-for-space encoding, which would be wrong here) before being placed into the variable map -
 * every other {@code ${name}} in the URL template (`${baseUrl}`, a multi-segment `${resource}`) is
 * structural and is substituted verbatim, since blanket-encoding the whole template would corrupt those.
 *
 * <p><b>Query parameters</b> (section 21.4) are added structurally via {@link ApiHttpRequest#addQueryParameter}
 * (which itself URI-encodes exactly once, correctly, and never manually concatenates a query string) - a
 * disabled {@code QUERY_PARAMETER} row is omitted entirely, and a repeated parameter name (multiple rows)
 * is preserved as multiple query entries.
 *
 * <p><b>Correctness fix (API Quality and UX Consolidation sprint)</b>: {@code endpoint.getEndpointPath()}
 * is split via {@link ApiUrlQueryStringSync#split} <i>before</i> substitution, so only the base path
 * (never any query string still embedded literally in legacy/imported path text) is resolved against
 * {@code ${...}} placeholders. Previously the entire raw template - including an embedded
 * {@code ?expand=${expand}} - was substituted as one string, so a <i>disabled</i> {@code QUERY_PARAMETER}
 * whose placeholder happened to also be embedded in the URL text still got resolved and threw
 * "Unable to resolve variable", even though the structured attribute controlling it was disabled. Each
 * embedded query occurrence is now reconciled against the structured attributes by name <i>and</i>
 * occurrence index within that name (BroadSQL allows duplicate query parameter names, so matching by
 * name alone would misattribute one occurrence's enabled/disabled state to another) - see
 * {@link #appendEmbeddedLegacyQueryParameters}. A matched occurrence is skipped here entirely (the
 * structured attribute is authoritative, appended below); an unmatched occurrence (genuinely legacy
 * data with no structured counterpart) is still substituted and sent, so nothing that predates the
 * structured model silently stops being sent. A URL fragment, if present in the template, is preserved
 * through {@link ApiUrlQueryStringSync#split} for display/editing purposes elsewhere (the GUI, {@code
 * SHOW ENDPOINT}) but is deliberately never attached to the outgoing request URI here - an HTTP
 * fragment is dereferenced solely by the client (RFC 3986 section 3.5) and is never meaningful to send
 * to an origin server.
 *
 * <p><b>Headers</b> (section 21.6) apply in order - API-level, then each ancestor group root-to-leaf, then
 * the endpoint's own - a later, more specific header overwriting an earlier same-named one.
 * Authentication-generated headers are applied afterward, by the caller invoking
 * {@code ApiAuthenticationRuntime} on the request this class returns, so they always win over any
 * same-named endpoint header (e.g. a stray {@code Authorization} header can never shadow real
 * authentication) - see the sprint doc for why this ordering, not the reverse, is the safe one. A disabled
 * header is never sent.
 *
 * <p><b>Request body</b> (SPRINT XT02-8) is attached after headers, through the exact same resolved
 * variable scope - no method-specific or body-specific resolution. Only the modes stored as literal
 * wire-format text ({@code json}/{@code text}/{@code xml}/{@code sparql} - see {@link #EXECUTABLE_BODY_MODES})
 * can actually be sent; every other mode ({@code form-urlencoded}, {@code multipart-form}, {@code file},
 * or anything unrecognized) stores Bruno's own structural JSON representation of the body in
 * {@code bodyContent}, not real wire bytes - sending that verbatim would produce a genuinely wrong
 * request, not a preserved one, so execution is refused for those modes before any network call, naming
 * the endpoint and its body mode. This is a real, current, documented limitation, not silently worked
 * around - {@code CONFIG API} continues to store/round-trip every mode losslessly regardless of whether
 * this class can execute it. No JSON-body validation is attempted after interpolation: the configured
 * text is sent exactly as substituted, matching how URL/header substitution already works.
 */
public class ApiEndpointRequestBuilder {

	private static final String VARIABLE_CONTEXT = "variable";

	/**
	 * Body modes stored as literal wire-format text - kept in sync by design with
	 * {@code BrunoCollectionImporter.RAW_BODY_TYPES} (a separate constant in the unrelated
	 * {@code dao.api.bruno} package, which classifies import/export storage rather than execution
	 * capability; the two currently coincide but are deliberately not the same field, to avoid coupling
	 * this execution-only class to the Bruno importer).
	 */
	private static final List<String> EXECUTABLE_BODY_MODES = List.of("json", "text", "xml", "sparql");

	private final ApiDefinitionsVault vault;
	private final ApiVariableResolver variableResolver;

	public ApiEndpointRequestBuilder(ApiDefinitionsVault vault) {
		this.vault = vault;
		this.variableResolver = new ApiVariableResolver(vault);
	}

	/**
	 * Builds a resolved {@link ApiHttpRequest} for one SPRINT XT02A (URL-Native API Execution)
	 * invocation - the counterpart to {@link #build} used by the URL-native {@code RUN} engine
	 * instead of the legacy id/alias execution path. Path and query construction is deliberately
	 * different from {@link #build}: {@code pathParameterValues} are already fully resolved literal
	 * values (from {@link com.upandcoding.broadsql.dao.api.invocation.ApiParameterBinder} - session VAR,
	 * persisted value, or default already applied) and simply override whatever the endpoint's own
	 * template variable of the same name would otherwise resolve to; {@code queryParameters} are sent
	 * exactly as given, with <b>none</b> of the legacy structured/embedded {@code QUERY_PARAMETER}
	 * auto-attachment this class's {@link #build} performs - URL-native execution sends only what the
	 * RUN URL specified (plus whatever the binder already decided to auto-attach for a required-but-
	 * omitted parameter), matching the spec's "what's in the URL is what's sent" model (section 3.1).
	 * Headers, authentication, and the request body are entirely unchanged from {@link #build} - same
	 * variable scope, same precedence, same execution engine, reused rather than duplicated (section 17).
	 */
	public ApiHttpRequest buildForInvocation(String apiId, ApiEnvironment environment, ApiEndpoint endpoint,
			Map<String, String> pathParameterValues, List<Map.Entry<String, String>> queryParameters) throws BroadSQLException {
		Map<String, String> variables = variableResolver.resolve(apiId, environment, endpoint, null);

		Map<String, String> urlVariables = new LinkedHashMap<>(variables);
		for (ApiAttribute pathParam : vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.PATH_PARAMETER)) {
			if (pathParam.isEnabled() && !pathParameterValues.containsKey(pathParam.getName())) {
				String resolvedValue = ApiVariableSubstitutor.substitute(pathParam.getValue(), variables, VARIABLE_CONTEXT);
				urlVariables.put(pathParam.getName(), ApiVariableSubstitutor.encodePathSegment(resolvedValue));
			}
		}
		for (Map.Entry<String, String> resolved : pathParameterValues.entrySet()) {
			urlVariables.put(resolved.getKey(), ApiVariableSubstitutor.encodePathSegment(resolved.getValue()));
		}

		ApiUrlQueryStringSync.UrlParts urlParts = ApiUrlQueryStringSync.split(endpoint.getEndpointPath());
		String normalizedBasePath = ApiPathTemplate.normalizeColonSegments(urlParts.basePath);
		String resolvedBasePath = ApiVariableSubstitutor.substitute(normalizedBasePath, urlVariables, VARIABLE_CONTEXT);
		String absoluteUrl = isAbsolute(resolvedBasePath) ? resolvedBasePath : joinBaseUrl(environment, resolvedBasePath);

		ApiHttpRequest request = new ApiHttpRequest();
		request.setMethod(endpoint.getMethod());
		request.setUri(URI.create(absoluteUrl));

		for (Map.Entry<String, String> queryParam : queryParameters) {
			request.addQueryParameter(queryParam.getKey(), queryParam.getValue());
		}

		applyHeaders(request, ApiOwnerType.API, apiId, variables);
		if (endpoint.getGroupId() != null) {
			for (ApiEndpointGroup group : vault.groupChainRootToLeaf(endpoint.getGroupId())) {
				applyHeaders(request, ApiOwnerType.GROUP, String.valueOf(group.getId()), variables);
			}
		}
		applyHeaders(request, ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), variables);
		applyBody(request, endpoint, variables);

		return request;
	}

	public ApiHttpRequest build(String apiId, ApiEnvironment environment, ApiEndpoint endpoint, Map<String, String> runtimeOverrides) throws BroadSQLException {
		Map<String, String> variables = variableResolver.resolve(apiId, environment, endpoint, runtimeOverrides);

		// Only a path parameter's own value is pre-encoded before insertion - most ${name} references in
		// a URL template (${baseUrl}, a multi-segment ${resource}) are structural and must be inserted
		// verbatim; only a path parameter is the single opaque value a path segment actually is.
		Map<String, String> urlVariables = new LinkedHashMap<>(variables);
		for (ApiAttribute pathParam : vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.PATH_PARAMETER)) {
			if (pathParam.isEnabled()) {
				String resolvedValue = ApiVariableSubstitutor.substitute(pathParam.getValue(), variables, VARIABLE_CONTEXT);
				urlVariables.put(pathParam.getName(), ApiVariableSubstitutor.encodePathSegment(resolvedValue));
			}
		}

		// Split before substitution - a disabled QUERY_PARAMETER's placeholder must never be resolved,
		// even when it is also embedded literally in the raw URL template's query string (see class
		// javadoc, "Correctness fix").
		ApiUrlQueryStringSync.UrlParts urlParts = ApiUrlQueryStringSync.split(endpoint.getEndpointPath());
		String normalizedBasePath = ApiPathTemplate.normalizeColonSegments(urlParts.basePath);
		String resolvedBasePath = ApiVariableSubstitutor.substitute(normalizedBasePath, urlVariables, VARIABLE_CONTEXT);
		String absoluteUrl = isAbsolute(resolvedBasePath) ? resolvedBasePath : joinBaseUrl(environment, resolvedBasePath);

		ApiHttpRequest request = new ApiHttpRequest();
		request.setMethod(endpoint.getMethod());
		request.setUri(URI.create(absoluteUrl));

		List<ApiAttribute> structuredQueryParams = vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER);
		appendEmbeddedLegacyQueryParameters(request, urlParts.queryParams, structuredQueryParams, variables);
		for (ApiAttribute queryParam : structuredQueryParams) {
			if (queryParam.isEnabled()) {
				request.addQueryParameter(queryParam.getName(), ApiVariableSubstitutor.substitute(queryParam.getValue(), variables, VARIABLE_CONTEXT));
			}
		}

		applyHeaders(request, ApiOwnerType.API, apiId, variables);
		if (endpoint.getGroupId() != null) {
			for (ApiEndpointGroup group : vault.groupChainRootToLeaf(endpoint.getGroupId())) {
				applyHeaders(request, ApiOwnerType.GROUP, String.valueOf(group.getId()), variables);
			}
		}
		applyHeaders(request, ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), variables);
		applyBody(request, endpoint, variables);

		return request;
	}

	/**
	 * Reconciles query parameters still embedded literally in the raw URL template's query string
	 * (legacy/unmigrated data, or a hand-typed literal never mirrored into the structured
	 * {@code QUERY_PARAMETER} model) against the structured attributes - the actual fix for the
	 * disabled-parameter "Unable to resolve variable" bug (see class javadoc). Matched by name
	 * <i>and</i> occurrence index within that name's group, since BroadSQL allows duplicate query
	 * parameter names; a matched occurrence is skipped here entirely (its structured counterpart is
	 * authoritative - enabled/disabled state and value - and is appended separately by the caller,
	 * whether or not it is actually enabled), so a disabled structured parameter's embedded
	 * placeholder is never substituted. An occurrence with no structured counterpart at all is treated
	 * as an implicit, always-enabled parameter, so data that predates the structured model does not
	 * silently stop being sent.
	 */
	private void appendEmbeddedLegacyQueryParameters(ApiHttpRequest request, List<ApiUrlQueryStringSync.RawQueryParam> embedded,
			List<ApiAttribute> structured, Map<String, String> variables) throws BroadSQLException {
		if (embedded.isEmpty()) {
			return;
		}
		Map<String, List<ApiUrlQueryStringSync.RawQueryParam>> embeddedByName = ApiUrlQueryStringSync.groupByName(embedded);
		Map<String, Integer> structuredCountByName = new HashMap<>();
		for (ApiAttribute attr : structured) {
			structuredCountByName.merge(attr.getName(), 1, Integer::sum);
		}
		for (Map.Entry<String, List<ApiUrlQueryStringSync.RawQueryParam>> entry : embeddedByName.entrySet()) {
			String name = entry.getKey();
			List<ApiUrlQueryStringSync.RawQueryParam> occurrences = entry.getValue();
			int structuredCount = structuredCountByName.getOrDefault(name, 0);
			for (int i = 0; i < occurrences.size(); i++) {
				if (i < structuredCount) {
					// Matched to a structured occurrence - that row is authoritative, handled by the caller.
					continue;
				}
				String value = occurrences.get(i).value == null ? "" : occurrences.get(i).value;
				request.addQueryParameter(name, ApiVariableSubstitutor.substitute(value, variables, VARIABLE_CONTEXT));
			}
		}
	}

	private void applyBody(ApiHttpRequest request, ApiEndpoint endpoint, Map<String, String> variables) throws BroadSQLException {
		String bodyMode = endpoint.getBodyMode();
		if (bodyMode == null) {
			return;
		}
		if (!EXECUTABLE_BODY_MODES.contains(bodyMode)) {
			throw new BroadSQLException("Endpoint '" + endpoint.getName() + "' has a '" + bodyMode + "' request body, which this "
					+ "release cannot execute (only json/text/xml/sparql bodies can be sent). The endpoint and its body remain "
					+ "fully visible and editable in CONFIG API.");
		}
		String interpolatedBody = ApiVariableSubstitutor.substitute(endpoint.getBodyContent(), variables, "request body variable");
		request.setBody(interpolatedBody);
		// ApiHttpRequest's headers are case-insensitive (XT02 final corrective patch) - containsKey already
		// matches "content-type"/"CONTENT-TYPE"/etc., no manual case-insensitive scan needed here anymore.
		if (!request.getHeaders().containsKey("Content-Type")) {
			request.setHeader("Content-Type", defaultContentType(bodyMode));
		}
	}

	private String defaultContentType(String bodyMode) {
		return switch (bodyMode) {
			case "json" -> "application/json";
			case "xml" -> "application/xml";
			case "sparql" -> "application/sparql-query";
			default -> "text/plain";
		};
	}

	private void applyHeaders(ApiHttpRequest request, ApiOwnerType ownerType, String ownerId, Map<String, String> variables) throws BroadSQLException {
		for (ApiAttribute header : vault.getAttributes(ownerType, ownerId, ApiAttributeKind.HEADER)) {
			if (header.isEnabled()) {
				request.setHeader(header.getName(), ApiVariableSubstitutor.substitute(header.getValue(), variables, VARIABLE_CONTEXT));
			}
		}
	}

	private boolean isAbsolute(String url) {
		return url != null && (url.regionMatches(true, 0, "http://", 0, 7) || url.regionMatches(true, 0, "https://", 0, 8));
	}

	private String joinBaseUrl(ApiEnvironment environment, String relativePath) {
		String baseUrl = environment == null ? "" : environment.getBaseUrl();
		if (baseUrl == null) {
			baseUrl = "";
		}
		String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		String path = relativePath.startsWith("/") ? relativePath : "/" + relativePath;
		return base + path;
	}
}
