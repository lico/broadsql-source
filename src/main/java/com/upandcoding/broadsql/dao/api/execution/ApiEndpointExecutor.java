package com.upandcoding.broadsql.dao.api.execution;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.auth.ApiAuthenticationRuntime;
import com.upandcoding.broadsql.dao.api.auth.ApiEffectiveAuthResolver;
import com.upandcoding.broadsql.dao.api.auth.EffectiveAuth;
import com.upandcoding.broadsql.dao.api.http.ApiHttpRequest;
import com.upandcoding.broadsql.dao.api.http.ApiHttpResponse;
import com.upandcoding.broadsql.dao.api.http.ApiHttpTransport;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthType;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * The MVP vertical slice - docs/SPRINT XT02 - Universal API Client.md, section 21:
 *
 * <pre>
 * ApiExecutionPolicy.checkExecutable(endpoint.getMethod())
 *         -&gt; ApiEndpointRequestBuilder.build(...)          (URL/query/path/headers resolved)
 *         -&gt; ApiAuthenticationRuntime.authenticate(...)    (sub-sprint 3, unchanged)
 *         -&gt; ApiHttpTransport.send(...)
 *         -&gt; ApiExecutionResult                            (2xx-5xx alike - a valid HTTP response
 *                                                             is not an exception, section 21.8)
 * </pre>
 *
 * Deliberately thin - every real piece of logic lives in the class that already owned it
 * ({@link ApiExecutionPolicy}, {@link ApiEndpointRequestBuilder}, {@link ApiAuthenticationRuntime},
 * {@link ApiHttpTransport}); this class only sequences them and builds the safe/redacted URL for display
 * (section 21.6/21.10) using whichever query parameter the effective authentication - if
 * {@code API_KEY_QUERY} - or an imported endpoint's own {@code secret}-flagged query parameter names.
 */
public class ApiEndpointExecutor {

	private final ApiDefinitionsVault vault;
	private final ApiEndpointRequestBuilder requestBuilder;
	private final ApiEffectiveAuthResolver effectiveAuthResolver;
	private final ApiAuthenticationRuntime authenticationRuntime;
	private final ApiHttpTransport transport;

	public ApiEndpointExecutor(ApiDefinitionsVault vault) {
		this(vault, new ApiAuthenticationRuntime(vault), new ApiHttpTransport());
	}

	public ApiEndpointExecutor(ApiDefinitionsVault vault, ApiAuthenticationRuntime authenticationRuntime, ApiHttpTransport transport) {
		this.vault = vault;
		this.requestBuilder = new ApiEndpointRequestBuilder(vault);
		this.effectiveAuthResolver = new ApiEffectiveAuthResolver(vault);
		this.authenticationRuntime = authenticationRuntime;
		this.transport = transport;
	}

	/**
	 * @throws BroadSQLException before any network request is attempted, for: an execution-policy refusal
	 *         (a write verb), an unresolved variable, unsupported authentication, an authentication/OAuth
	 *         token failure; or after, for a genuine network error/timeout reaching the endpoint itself. A
	 *         non-2xx HTTP response from the endpoint is <b>not</b> an exception - it is returned as a
	 *         normal {@link ApiExecutionResult} (section 21.8).
	 */
	public ApiExecutionResult execute(String apiId, ApiEnvironment environment, ApiEndpoint endpoint, Map<String, String> runtimeOverrides) throws BroadSQLException {
		ApiExecutionPolicy.checkExecutable(endpoint.getMethod());

		ApiHttpRequest request = requestBuilder.build(apiId, environment, endpoint, runtimeOverrides);
		authenticationRuntime.authenticate(request, apiId, environment, endpoint);

		Set<String> secretQueryParamNames = secretQueryParameterNames(apiId, endpoint);
		String safeUrl = ApiUrlRedactor.redact(request.buildUri(), secretQueryParamNames);

		ApiHttpResponse response = transport.send(request);
		return new ApiExecutionResult(endpoint.getMethod(), safeUrl, response);
	}

	/**
	 * The SPRINT XT02A (URL-Native API Execution) counterpart to {@link #execute} - takes an already
	 * fully-resolved {@link com.upandcoding.broadsql.dao.api.invocation.ApiParameterBinder.Binding}'s
	 * path/query values instead of the legacy id/alias engine's endpoint-template resolution.
	 * Authentication, redaction, and transport are identical to {@link #execute} - only URL/query
	 * construction differs, via {@link ApiEndpointRequestBuilder#buildForInvocation}.
	 */
	public ApiExecutionResult executeInvocation(String apiId, ApiEnvironment environment, ApiEndpoint endpoint,
			Map<String, String> pathParameterValues, List<Map.Entry<String, String>> queryParameters) throws BroadSQLException {
		ApiExecutionPolicy.checkExecutable(endpoint.getMethod());

		ApiHttpRequest request = requestBuilder.buildForInvocation(apiId, environment, endpoint, pathParameterValues, queryParameters);
		authenticationRuntime.authenticate(request, apiId, environment, endpoint);

		Set<String> secretQueryParamNames = secretQueryParameterNames(apiId, endpoint);
		String safeUrl = ApiUrlRedactor.redact(request.buildUri(), secretQueryParamNames);

		ApiHttpResponse response = transport.send(request);
		return new ApiExecutionResult(endpoint.getMethod(), safeUrl, response);
	}

	private Set<String> secretQueryParameterNames(String apiId, ApiEndpoint endpoint) throws BroadSQLException {
		Set<String> names = new HashSet<>();
		EffectiveAuth effectiveAuth = effectiveAuthResolver.resolve(apiId, endpoint);
		if (effectiveAuth.getAuthType() == ApiAuthType.API_KEY_QUERY) {
			String paramName = effectiveAuth.getProperty("name");
			if (paramName != null) {
				names.add(paramName);
			}
		}
		List<ApiAttribute> queryParams = vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER);
		for (ApiAttribute param : queryParams) {
			if (param.isSecret()) {
				names.add(param.getName());
			}
		}
		return names;
	}
}
