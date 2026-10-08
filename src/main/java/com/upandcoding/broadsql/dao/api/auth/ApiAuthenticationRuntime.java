package com.upandcoding.broadsql.dao.api.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiVariableResolver;
import com.upandcoding.broadsql.dao.api.ApiVariableSubstitutor;
import com.upandcoding.broadsql.dao.api.http.ApiHttpRequest;
import com.upandcoding.broadsql.dao.api.http.ApiHttpResponse;
import com.upandcoding.broadsql.dao.api.http.ApiHttpTransport;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;

/**
 * Applies an endpoint's effective authentication (see {@link ApiEffectiveAuthResolver}) to an
 * {@link ApiHttpRequest}, resolving every credential through {@link ApiVariableResolver}/
 * {@link ApiVariableSubstitutor} at authentication time - never at import time, never baked in - so
 * switching {@link ApiEnvironment} automatically switches credentials with no change to the persisted
 * authentication definition. See docs/SPRINT XT02 - Universal API Client.md, section 19 for the full
 * architecture.
 *
 * <p>One instance is meant to be kept and reused across multiple {@link #authenticate} calls (by whatever
 * eventually owns a "session" against an API - a future command, or a test) - it owns the
 * {@link ApiOAuth2TokenCache}, which must persist across calls for OAuth2 token reuse to mean anything.
 *
 * <p>Never logs a header, property value, or token - see {@link ApiHttpTransport}'s own Javadoc for the
 * same rule applied to the transport layer.
 */
public class ApiAuthenticationRuntime {

	private static final String AUTH_VARIABLE_CONTEXT = "authentication variable";

	private final ApiEffectiveAuthResolver effectiveAuthResolver;
	private final ApiVariableResolver variableResolver;
	private final ApiHttpTransport transport;
	private final ApiOAuth2TokenCache tokenCache;

	public ApiAuthenticationRuntime(ApiDefinitionsVault vault) {
		this(vault, new ApiHttpTransport(), new ApiOAuth2TokenCache());
	}

	public ApiAuthenticationRuntime(ApiDefinitionsVault vault, ApiHttpTransport transport, ApiOAuth2TokenCache tokenCache) {
		this.effectiveAuthResolver = new ApiEffectiveAuthResolver(vault);
		this.variableResolver = new ApiVariableResolver(vault);
		this.transport = transport;
		this.tokenCache = tokenCache;
	}

	/**
	 * Mutates {@code request} (setting whatever header(s)/query parameter the effective authentication
	 * requires) so it is ready to send. Throws, without touching {@code request} any further, and without
	 * ever sending it, when:
	 * <ul>
	 * <li>the effective authentication is {@link com.upandcoding.broadsql.dao.api.model.ApiAuthType#UNSUPPORTED} -
	 * a mechanism this release does not implement (Digest, NTLM, OAuth1, non-client-credentials OAuth2,
	 * ...), naming the original imported type;</li>
	 * <li>a required variable (e.g. {@code ${token}}) cannot be resolved in the effective scope.</li>
	 * </ul>
	 * Explicit {@link com.upandcoding.broadsql.dao.api.model.ApiAuthType#NONE} (or genuinely no authentication
	 * configured anywhere in the chain) applies nothing and returns normally.
	 */
	public void authenticate(ApiHttpRequest request, String apiId, ApiEnvironment environment, ApiEndpoint endpoint) throws BroadSQLException {
		EffectiveAuth effective = effectiveAuthResolver.resolve(apiId, endpoint);
		switch (effective.getAuthType()) {
			case NONE:
				return;
			case UNSUPPORTED: {
				String original = effective.getProperty("unsupportedSourceAuthType");
				throw new BroadSQLException("Authentication type " + (StringUtils.isBlank(original) ? "UNKNOWN" : original.toUpperCase().replace(':', ' '))
						+ " is not supported by this BroadSQL release.");
			}
			default:
				break;
		}

		Map<String, String> variables = variableResolver.resolve(apiId, environment, endpoint, null);
		switch (effective.getAuthType()) {
			case BASIC:
				applyBasic(request, effective, variables);
				break;
			case BEARER:
				applyBearer(request, effective, variables);
				break;
			case API_KEY_HEADER:
				applyApiKeyHeader(request, effective, variables);
				break;
			case API_KEY_QUERY:
				applyApiKeyQuery(request, effective, variables);
				break;
			case OAUTH2_CLIENT_CREDENTIALS:
				applyOAuth2ClientCredentials(request, effective, environment, variables);
				break;
			default:
				throw new BroadSQLException("Unhandled authentication type: " + effective.getAuthType());
		}
	}

	private void applyBasic(ApiHttpRequest request, EffectiveAuth auth, Map<String, String> variables) throws BroadSQLException {
		String username = ApiVariableSubstitutor.substitute(orEmpty(auth.getProperty("username")), variables, AUTH_VARIABLE_CONTEXT);
		String password = ApiVariableSubstitutor.substitute(orEmpty(auth.getProperty("password")), variables, AUTH_VARIABLE_CONTEXT);
		request.setHeader("Authorization", "Basic " + base64(username + ":" + password));
	}

	private void applyBearer(ApiHttpRequest request, EffectiveAuth auth, Map<String, String> variables) throws BroadSQLException {
		String token = ApiVariableSubstitutor.substitute(orEmpty(auth.getProperty("token")), variables, AUTH_VARIABLE_CONTEXT);
		request.setHeader("Authorization", "Bearer " + token);
	}

	private void applyApiKeyHeader(ApiHttpRequest request, EffectiveAuth auth, Map<String, String> variables) throws BroadSQLException {
		String headerName = orEmpty(auth.getProperty("name"));
		String value = ApiVariableSubstitutor.substitute(orEmpty(auth.getProperty("value")), variables, AUTH_VARIABLE_CONTEXT);
		request.setHeader(headerName, value);
	}

	private void applyApiKeyQuery(ApiHttpRequest request, EffectiveAuth auth, Map<String, String> variables) throws BroadSQLException {
		String paramName = orEmpty(auth.getProperty("name"));
		String value = ApiVariableSubstitutor.substitute(orEmpty(auth.getProperty("value")), variables, AUTH_VARIABLE_CONTEXT);
		request.addQueryParameter(paramName, value);
	}

	/**
	 * Resolves an OAuth2 Client Credentials token (from cache, or by fetching a fresh one - see
	 * {@link #fetchAccessToken}) and applies it as a Bearer token, exactly like {@link #applyBearer} would
	 * for a static one - the endpoint request itself never knows OAuth was involved, per the sprint doc's
	 * section 19's acceptance question ("can the authentication layer... authenticate without
	 * endpoint-specific or provider-specific code").
	 */
	private void applyOAuth2ClientCredentials(ApiHttpRequest request, EffectiveAuth auth, ApiEnvironment environment, Map<String, String> variables) throws BroadSQLException {
		String tokenUrl = ApiVariableSubstitutor.substitute(orEmpty(auth.getProperty("accessTokenUrl")), variables, AUTH_VARIABLE_CONTEXT);
		String clientId = ApiVariableSubstitutor.substitute(orEmpty(auth.getProperty("clientId")), variables, AUTH_VARIABLE_CONTEXT);
		String clientSecret = ApiVariableSubstitutor.substitute(orEmpty(auth.getProperty("clientSecret")), variables, AUTH_VARIABLE_CONTEXT);
		String scopeTemplate = auth.getProperty("scope");
		String scope = scopeTemplate == null ? null : ApiVariableSubstitutor.substitute(scopeTemplate, variables, AUTH_VARIABLE_CONTEXT);
		String tokenPlacement = orEmpty(auth.getProperty("tokenPlacement"));
		boolean credentialsInBasicHeader = !"body".equalsIgnoreCase(tokenPlacement);

		String cacheKey = buildOAuth2CacheKey(auth, environment, tokenUrl, clientId, clientSecret, scope, tokenPlacement);
		String accessToken = tokenCache.getValidToken(cacheKey);
		if (accessToken == null) {
			accessToken = fetchAccessToken(tokenUrl, clientId, clientSecret, scope, credentialsInBasicHeader, cacheKey);
		}
		request.setHeader("Authorization", "Bearer " + accessToken);
	}

	/**
	 * Builds the OAuth2 token-cache key as a SHA-256 fingerprint of the *entire* effective context, not
	 * just the resolved token URL and client ID (a sub-sprint 3 review finding - docs/SPRINT XT02 -
	 * Universal API Client.md, section 20). Token URL + client ID alone could stay identical while the
	 * environment, client secret, scope, or client-authentication method differ, incorrectly reusing a
	 * token issued under different credentials. The fingerprint incorporates the auth definition's own
	 * owner (which {@code API_AUTH} row is in effect), the selected environment's ID, and every
	 * credential-bearing resolved value (including the client secret) - so a token is reused only when the
	 * effective OAuth context is actually equivalent, and never otherwise. Hashing (rather than
	 * concatenating in the clear) also means the cache key itself - which could end up in a diagnostic
	 * dump of the cache's internal state - never contains a plaintext secret.
	 */
	private String buildOAuth2CacheKey(EffectiveAuth auth, ApiEnvironment environment, String tokenUrl, String clientId, String clientSecret, String scope, String tokenPlacement) {
		String environmentId = environment == null ? "no-environment" : String.valueOf(environment.getId());
		String authOwner = auth.getSourceOwnerType() + ":" + auth.getSourceOwnerId();
		String composite = authOwner + '|' + environmentId + '|' + tokenUrl + '|' + clientId + '|' + clientSecret + '|' + scope + '|' + tokenPlacement;
		return sha256Hex(composite);
	}

	private String sha256Hex(String value) {
		try {
			byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			// SHA-256 is guaranteed available on every JVM implementation.
			throw new IllegalStateException(e);
		}
	}

	/**
	 * The OAuth2 token exchange itself - {@code POST} to the token endpoint, {@code
	 * application/x-www-form-urlencoded}, {@code grant_type=client_credentials}. This is the one place in
	 * the authentication layer that issues a network request of its own, ahead of - and independent from -
	 * the user endpoint's own request; {@link ApiHttpTransport} places no method restriction on it (see
	 * its own Javadoc).
	 */
	private String fetchAccessToken(String tokenUrl, String clientId, String clientSecret, String scope, boolean credentialsInBasicHeader, String cacheKey)
			throws BroadSQLException {
		ApiHttpRequest tokenRequest = new ApiHttpRequest();
		tokenRequest.setMethod("POST");
		tokenRequest.setUri(URI.create(tokenUrl));
		tokenRequest.setHeader("Content-Type", "application/x-www-form-urlencoded");

		StringBuilder form = new StringBuilder("grant_type=client_credentials");
		if (credentialsInBasicHeader) {
			tokenRequest.setHeader("Authorization", "Basic " + base64(clientId + ":" + clientSecret));
		} else {
			form.append("&client_id=").append(urlEncode(clientId)).append("&client_secret=").append(urlEncode(clientSecret));
		}
		if (StringUtils.isNotBlank(scope)) {
			form.append("&scope=").append(urlEncode(scope));
		}
		tokenRequest.setBody(form.toString());

		ApiHttpResponse response = transport.send(tokenRequest);
		if (!response.isSuccessful()) {
			// Deliberately never includes the response body - OAuth error responses can echo submitted
			// values (docs/SPRINT XT02 - Universal API Client.md, original notes on OAuth diagnostics).
			throw new BroadSQLException("Token endpoint returned HTTP " + response.getStatusCode());
		}

		Map<String, Object> tokenResponse;
		try {
			tokenResponse = new Gson().fromJson(response.getBody(), new TypeToken<Map<String, Object>>() {
			}.getType());
		} catch (JsonSyntaxException e) {
			throw new BroadSQLException("Token endpoint returned a malformed JSON response.");
		}
		if (tokenResponse == null) {
			throw new BroadSQLException("Token endpoint returned a malformed JSON response.");
		}

		Object accessTokenValue = tokenResponse.get("access_token");
		if (!(accessTokenValue instanceof String) || StringUtils.isBlank((String) accessTokenValue)) {
			throw new BroadSQLException("Token endpoint response is missing 'access_token'.");
		}
		String accessToken = (String) accessTokenValue;

		Instant expiresAt = null;
		Object expiresInValue = tokenResponse.get("expires_in");
		if (expiresInValue instanceof Number) {
			expiresAt = Instant.now().plusSeconds(((Number) expiresInValue).longValue());
		}

		tokenCache.put(cacheKey, accessToken, expiresAt);
		return accessToken;
	}

	private String base64(String value) {
		return java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private String orEmpty(String value) {
		return value == null ? "" : value;
	}
}
