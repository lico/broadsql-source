package com.upandcoding.broadsql.dao.api.invocation;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionVariablesHolder;
import com.upandcoding.broadsql.dao.api.ApiUrlQueryStringSync;
import com.upandcoding.broadsql.dao.api.ApiVariableResolver;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * Resolves every path/query parameter value for one matched endpoint - SPRINT XT02A (URL-Native API
 * Execution), section 7 (resolution precedence) and section 10 (validation), before any HTTP request
 * is built.
 *
 * <p><b>Path parameters</b> are always resolved (they are structurally required by the URL). A
 * {@code :name} placeholder segment is resolved, in order (SPRINT XT02B, section 4.3, adds the second
 * step below to the original XT02A chain): session {@code VAR} (looked up by the name the user
 * actually typed after the colon - not necessarily the endpoint's own parameter name, so
 * {@code RUN /api/customer/:CUSTOMER_ID;} can reuse an existing {@code VAR CUSTOMER_ID} against an
 * endpoint parameter literally named {@code id}, per section 13.10's completion example); then the
 * current API+environment variable of that same name (the exact scope {@code CONFIG API}'s
 * Environment tab edits, via {@link com.upandcoding.broadsql.dao.api.ApiVariableResolver} narrowed to
 * skip its group/endpoint layers, since those are a separate, more specific concept - see below); then
 * the endpoint's own persisted value for that template position's canonical parameter name; then its
 * configured default; else a missing-parameter error naming the canonical name. A literal segment is
 * used as-is (URL-decoded), then validated against that position's parameter metadata (type/allowed
 * values) if any is configured.
 *
 * <p><b>Query parameters are URL-native</b> (section 3.1/11): a name explicitly present in the RUN
 * URL is always bound and sent (literal or {@code :name} placeholder, same three-step precedence,
 * keyed by the URL's own query key for persisted/default lookup - unlike path parameters there is no
 * separate "typed name vs. canonical name" distinction to make, since the query key already is the
 * parameter's identity).
 *
 * <p><b>A configured query parameter omitted from the URL entirely</b> (SPRINT XT02A corrective pass,
 * 16/09/2026 - this precise required/optional distinction was verified and corrected against the
 * original closure report's looser description) still goes through the exact same VAR -&gt; persisted
 * -&gt; default precedence as one written explicitly - {@code required} only changes what happens if
 * <i>none</i> of the three resolve:
 * <pre>
 * required, omitted from URL:  session VAR -&gt; persisted value -&gt; default -&gt; ERROR (never silently omitted)
 * optional, omitted from URL:  session VAR -&gt; persisted value -&gt; default -&gt; simply not sent
 * </pre>
 * This fills in one specific, URL-native-consistent reading of section 7/10 (the spec leaves "required
 * query parameter" resolution at the conceptual level) - documented in docs/TECHNICAL_CHANGE.md, not a
 * deviation from anything explicitly stated. An unknown query parameter (no matching metadata at all)
 * is passed through unvalidated - BroadSQL has no per-endpoint "strict unknown query parameter" policy
 * setting today (section 10's "according to the configured endpoint policy" names a policy that does
 * not exist in this codebase), a scoped-down simplification also recorded in the closure report.
 */
public class ApiParameterBinder {

	private final ApiDefinitionsVault vault;

	public ApiParameterBinder(ApiDefinitionsVault vault) {
		this.vault = vault;
	}

	public static final class Binding {
		private final Map<String, String> pathValues;
		private final List<Map.Entry<String, String>> queryValues;

		public Binding(Map<String, String> pathValues, List<Map.Entry<String, String>> queryValues) {
			this.pathValues = pathValues;
			this.queryValues = queryValues;
		}

		public Map<String, String> getPathValues() {
			return pathValues;
		}

		public List<Map.Entry<String, String>> getQueryValues() {
			return queryValues;
		}
	}

	public Binding bind(ApiEndpoint endpoint, ApiPathTemplate template, List<String> incomingSegments,
			List<ApiUrlQueryStringSync.RawQueryParam> incomingQuery, String apiId, ApiEnvironment environment) throws BroadSQLException {

		List<ApiAttribute> pathMetadata = vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.PATH_PARAMETER);
		List<ApiAttribute> queryMetadata = vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER);
		// SPRINT XT02B, section 4.2/4.3: resolved ONCE here (not per-parameter, which would mean N
		// redundant DB round-trips for N parameters) - API+Environment scope only (endpoint=null stops
		// ApiVariableResolver before its group/endpoint layers, a separate, more specific concept: an
		// endpoint's own persisted parameter value, handled by tryResolveFromVarPersistedOrDefault below).
		Map<String, String> apiEnvironmentVariables = new ApiVariableResolver(vault).resolve(apiId, environment, null, null);

		Map<String, String> pathValues = new LinkedHashMap<>();
		for (int i = 0; i < template.segmentCount(); i++) {
			String canonicalName = template.parameterName(i);
			if (canonicalName == null) {
				continue;
			}
			String rawIncoming = incomingSegments.get(i);
			ApiAttribute meta = findByName(pathMetadata, canonicalName);
			String resolved = resolveValue(rawIncoming, canonicalName, meta, endpoint, template, "path parameter", apiEnvironmentVariables);
			validate(resolved, canonicalName, meta, "path parameter");
			pathValues.put(canonicalName, resolved);
		}

		List<Map.Entry<String, String>> queryValues = new ArrayList<>();
		Set<String> namesSeenInUrl = new LinkedHashSet<>();
		for (ApiUrlQueryStringSync.RawQueryParam raw : incomingQuery) {
			String rawValue = raw.value == null ? "" : raw.value;
			ApiAttribute meta = findByName(queryMetadata, raw.name);
			String resolved = resolveValue(rawValue, raw.name, meta, endpoint, template, "query parameter", apiEnvironmentVariables);
			validate(resolved, raw.name, meta, "query parameter");
			queryValues.add(new AbstractMap.SimpleEntry<>(raw.name, resolved));
			namesSeenInUrl.add(raw.name.toLowerCase());
		}
		for (ApiAttribute meta : queryMetadata) {
			if (namesSeenInUrl.contains(meta.getName().toLowerCase())) {
				continue;
			}
			// SPRINT XT02A corrective pass (16/09/2026): every configured query parameter omitted from
			// the RUN URL - required or optional alike - goes through the same VAR -> persisted ->
			// default precedence. Only what happens when NONE of those resolve differs: required errors
			// (never silently omitted from the outgoing request), optional is simply not sent.
			String resolved = tryResolveFromVarPersistedOrDefault(meta.getName(), meta, apiEnvironmentVariables);
			if (resolved == null) {
				if (meta.isRequired()) {
					throw missingParameterError(meta.getName(), endpoint, template);
				}
				continue;
			}
			validate(resolved, meta.getName(), meta, "query parameter");
			queryValues.add(new AbstractMap.SimpleEntry<>(meta.getName(), resolved));
		}

		return new Binding(pathValues, queryValues);
	}

	/** Dispatches to placeholder resolution ({@code :name}) or returns the literal value (URL-decoded) as-is. */
	private String resolveValue(String rawIncoming, String canonicalName, ApiAttribute meta, ApiEndpoint endpoint,
			ApiPathTemplate template, String parameterKind, Map<String, String> apiEnvironmentVariables) throws BroadSQLException {
		if (rawIncoming != null && rawIncoming.startsWith(":")) {
			String typedName = rawIncoming.substring(1);
			if (typedName.isEmpty()) {
				throw new BroadSQLException("Invalid placeholder ':' in the RUN URL - expected a name after ':'.");
			}
			if (ApiSessionVariablesHolder.isSet(typedName)) {
				return ApiSessionVariablesHolder.get(typedName);
			}
			return resolveFromVarPersistedOrDefault(canonicalName, meta, endpoint, template, parameterKind, apiEnvironmentVariables);
		}
		return urlDecode(rawIncoming);
	}

	/**
	 * Steps 2/3/4/5 of the resolution precedence (section 7.3, extended by SPRINT XT02B section 4.3)
	 * once a session VAR under the typed name is not set - throws the standard missing-parameter error
	 * (section 21) if nothing resolves. Used for: a path parameter (always required by URL structure);
	 * a {@code :name} placeholder explicitly typed in the URL (required or optional - the user asked
	 * for a substitution, so a silent omission would be wrong either way); and a required query
	 * parameter omitted from the URL.
	 */
	private String resolveFromVarPersistedOrDefault(String canonicalName, ApiAttribute meta, ApiEndpoint endpoint,
			ApiPathTemplate template, String parameterKind, Map<String, String> apiEnvironmentVariables) throws BroadSQLException {
		String resolved = tryResolveFromVarPersistedOrDefault(canonicalName, meta, apiEnvironmentVariables);
		if (resolved != null) {
			return resolved;
		}
		throw missingParameterError(canonicalName, endpoint, template);
	}

	/**
	 * The non-throwing form of the same VAR -&gt; API/environment variable -&gt; persisted -&gt; default
	 * precedence - {@code null} if none of the four resolve. Used for an <i>optional</i> query
	 * parameter omitted from the URL (SPRINT XT02A corrective pass, 16/09/2026), where an unresolved
	 * value means "omit", not "error".
	 */
	private String tryResolveFromVarPersistedOrDefault(String canonicalName, ApiAttribute meta, Map<String, String> apiEnvironmentVariables) {
		if (ApiSessionVariablesHolder.isSet(canonicalName)) {
			return ApiSessionVariablesHolder.get(canonicalName);
		}
		// SPRINT XT02B, section 4.2/4.3: the current API's current environment variable of this name,
		// between session VAR and the endpoint's own persisted value - the same store CONFIG API's
		// Environment tab edits. Case-insensitive, matching session VAR's own lookup convention just
		// above (a variable's "genericness" - usable regardless of exactly how its name was cased where
		// it's referenced - is the whole point of this precedence step existing at all).
		String environmentVariable = findCaseInsensitive(apiEnvironmentVariables, canonicalName);
		if (StringUtils.isNotBlank(environmentVariable)) {
			return environmentVariable;
		}
		if (meta != null && StringUtils.isNotBlank(meta.getValue())) {
			return meta.getValue();
		}
		if (meta != null && StringUtils.isNotBlank(meta.getDefaultValue())) {
			return meta.getDefaultValue();
		}
		return null;
	}

	private BroadSQLException missingParameterError(String canonicalName, ApiEndpoint endpoint, ApiPathTemplate template) {
		String endpointLabel = StringUtils.isNotBlank(endpoint.getAlias()) ? endpoint.getAlias() : endpoint.getName();
		return new BroadSQLException("Missing required parameter '" + canonicalName + "' for endpoint " + endpointLabel + ".\n\n"
				+ "Syntax:\n  " + endpoint.getMethod() + " " + template.toCanonicalDisplay() + "\n\n"
				+ "Set it in CONFIG API, define VAR " + canonicalName.toUpperCase() + "=..., or provide a literal value.");
	}

	private void validate(String value, String name, ApiAttribute meta, String parameterKind) throws BroadSQLException {
		if (meta == null) {
			return;
		}
		String type = meta.getParamType();
		if ("integer".equalsIgnoreCase(type)) {
			try {
				Long.parseLong(value);
			} catch (NumberFormatException e) {
				throw new BroadSQLException("Invalid value '" + value + "' for " + parameterKind + " '" + name + "'.\nExpected: integer.");
			}
		} else if ("boolean".equalsIgnoreCase(type)) {
			if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
				throw new BroadSQLException("Invalid value '" + value + "' for " + parameterKind + " '" + name + "'.\nExpected: true or false.");
			}
		}
		List<String> allowed = meta.getAllowedValuesList();
		if (!allowed.isEmpty() && allowed.stream().noneMatch(v -> v.equalsIgnoreCase(value))) {
			StringBuilder sb = new StringBuilder("Invalid value '" + value + "' for " + parameterKind + " '" + name + "'.\n\nAllowed values:\n");
			for (String candidate : allowed) {
				sb.append("  ").append(candidate).append('\n');
			}
			throw new BroadSQLException(sb.toString().stripTrailing());
		}
	}

	private String findCaseInsensitive(Map<String, String> variables, String name) {
		for (Map.Entry<String, String> entry : variables.entrySet()) {
			if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue();
			}
		}
		return null;
	}

	private ApiAttribute findByName(List<ApiAttribute> attributes, String name) {
		for (ApiAttribute attr : attributes) {
			if (attr.getName() != null && attr.getName().equalsIgnoreCase(name)) {
				return attr;
			}
		}
		return null;
	}

	private String urlDecode(String value) {
		if (value == null) {
			return "";
		}
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			// Not a validly percent-encoded value - used as-is (BroadSQL's URL parsing is otherwise
			// deliberately conservative/non-decoding elsewhere, see ApiUrlQueryStringSync's own javadoc).
			return value;
		}
	}
}
