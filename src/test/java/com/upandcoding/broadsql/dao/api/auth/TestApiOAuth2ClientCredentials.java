package com.upandcoding.broadsql.dao.api.auth;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.http.ApiHttpRequest;
import com.upandcoding.broadsql.dao.api.http.ApiHttpTransport;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiAuthType;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * Covers {@link ApiAuthenticationRuntime}'s OAuth2 Client Credentials support - docs/SPRINT XT02 -
 * Universal API Client.md, section 19.7/19.8/19.9 - against a real local server with two routes,
 * {@code /oauth/token} and {@code /api/resource}, exactly as the sub-sprint's testing requirement
 * specifies.
 */
class TestApiOAuth2ClientCredentials {

	private static final String API_ID = "OAUTHTEST";

	private TestLocalHttpServer server;
	private ApiDefinitionsVault vault;
	private ApiVersion version;
	private ApiEndpoint endpoint;

	@BeforeEach
	void setUp() throws IOException, BroadSQLException {
		server = new TestLocalHttpServer();
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		endpoint = new ApiEndpoint(version.getId(), null, "Resource", "GET", "/api/resource", 0);
		vault.saveEndpoint(endpoint);
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	void firstUseFetchesATokenAndAppliesItAsBearerOnTheApiRequest() throws BroadSQLException, IOException, InterruptedException {
		server.setRoute("/oauth/token", 200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
		ApiAuthenticationRuntime runtime = newRuntime();
		saveOAuth2Auth(null);
		ApiEnvironment env = environment(Map.of("clientId", "client-a", "clientSecret", "secret-a"));

		sendAuthenticatedResourceRequest(runtime, env);

		Assertions.assertEquals(1, server.countRequestsTo("/oauth/token"));
		Assertions.assertEquals("Bearer tok-1", server.lastRequestTo("/api/resource").headers.getFirst("Authorization"));
	}

	@Test
	void secondUseReusesTheCachedTokenWithoutCallingTheTokenEndpointAgain() throws BroadSQLException, IOException, InterruptedException {
		server.setRoute("/oauth/token", 200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");
		ApiAuthenticationRuntime runtime = newRuntime();
		saveOAuth2Auth(null);
		ApiEnvironment env = environment(Map.of("clientId", "client-a", "clientSecret", "secret-a"));

		sendAuthenticatedResourceRequest(runtime, env);
		sendAuthenticatedResourceRequest(runtime, env);

		Assertions.assertEquals(1, server.countRequestsTo("/oauth/token"), "a valid cached token must not be re-fetched");
		Assertions.assertEquals(2, server.countRequestsTo("/api/resource"));
	}

	@Test
	void aTokenWithNoUsableRemainingLifetimeTriggersAnotherTokenRequestOnNextUse() throws BroadSQLException, IOException, InterruptedException {
		// expires_in shorter than the cache's built-in safety margin - correctly never trusted as reusable.
		server.setRoute("/oauth/token", 200, "{\"access_token\":\"tok-1\",\"expires_in\":1}");
		ApiAuthenticationRuntime runtime = newRuntime();
		saveOAuth2Auth(null);
		ApiEnvironment env = environment(Map.of("clientId", "client-a", "clientSecret", "secret-a"));

		sendAuthenticatedResourceRequest(runtime, env);
		sendAuthenticatedResourceRequest(runtime, env);

		Assertions.assertEquals(2, server.countRequestsTo("/oauth/token"), "a token with no safely-usable remaining lifetime must be re-fetched, not reused");
	}

	@Test
	void clientCredentialsSentViaHttpBasicHeaderByDefault() throws BroadSQLException, IOException, InterruptedException {
		server.setRoute("/oauth/token", 200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");
		ApiAuthenticationRuntime runtime = newRuntime();
		saveOAuth2Auth(null); // tokenPlacement not set - basic_auth_header is the default
		ApiEnvironment env = environment(Map.of("clientId", "client-a", "clientSecret", "secret-a"));

		sendAuthenticatedResourceRequest(runtime, env);

		TestLocalHttpServer.RecordedRequest tokenRequest = server.lastRequestTo("/oauth/token");
		String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("client-a:secret-a".getBytes(StandardCharsets.UTF_8));
		Assertions.assertEquals(expectedAuth, tokenRequest.headers.getFirst("Authorization"));
		Assertions.assertEquals("grant_type=client_credentials", tokenRequest.body);
		Assertions.assertEquals("application/x-www-form-urlencoded", tokenRequest.headers.getFirst("Content-Type"));
	}

	@Test
	void clientCredentialsSentInFormBodyWhenConfigured() throws BroadSQLException, IOException, InterruptedException {
		server.setRoute("/oauth/token", 200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");
		ApiAuthenticationRuntime runtime = newRuntime();
		saveOAuth2Auth("body");
		ApiEnvironment env = environment(Map.of("clientId", "client-a", "clientSecret", "secret-a"));

		sendAuthenticatedResourceRequest(runtime, env);

		TestLocalHttpServer.RecordedRequest tokenRequest = server.lastRequestTo("/oauth/token");
		Assertions.assertNull(tokenRequest.headers.getFirst("Authorization"), "no Basic header when credentials are placed in the body");
		Assertions.assertEquals("grant_type=client_credentials&client_id=client-a&client_secret=secret-a", tokenRequest.body);
	}

	@Test
	void scopeIsIncludedInTheTokenRequestWhenConfigured() throws BroadSQLException, IOException, InterruptedException {
		server.setRoute("/oauth/token", 200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");
		ApiAuthConfig auth = new ApiAuthConfig(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.OAUTH2_CLIENT_CREDENTIALS);
		vault.saveAuth(auth);
		saveProperty(auth, "accessTokenUrl", "${baseUrl}/oauth/token", false);
		saveProperty(auth, "clientId", "${clientId}", false);
		saveProperty(auth, "clientSecret", "${clientSecret}", true);
		saveProperty(auth, "scope", "read write", false);
		ApiEnvironment env = environment(Map.of("clientId", "client-a", "clientSecret", "secret-a"));
		ApiAuthenticationRuntime runtime = newRuntime();

		sendAuthenticatedResourceRequest(runtime, env);

		Assertions.assertTrue(server.lastRequestTo("/oauth/token").body.contains("scope=read+write"));
	}

	@Test
	void differentEnvironmentsNeverShareACachedTokenEvenWithinTheSameRuntime() throws BroadSQLException, IOException, InterruptedException {
		server.setRoute("/oauth/token", 200, "{\"access_token\":\"tok-shared-endpoint\",\"expires_in\":3600}");
		ApiAuthenticationRuntime runtime = newRuntime();
		saveOAuth2Auth(null);
		ApiEnvironment devEnv = environment(Map.of("clientId", "dev-client", "clientSecret", "dev-secret"));
		ApiEnvironment prodEnv = environment(Map.of("clientId", "prod-client", "clientSecret", "prod-secret"));

		sendAuthenticatedResourceRequest(runtime, devEnv);
		sendAuthenticatedResourceRequest(runtime, prodEnv);

		Assertions.assertEquals(2, server.countRequestsTo("/oauth/token"), "two different credential sets must never be satisfied by one cached token");
	}

	@Test
	void sameTokenUrlAndClientIdUnderDifferentEnvironmentsNeverShareACachedToken() throws BroadSQLException, IOException, InterruptedException {
		// Same clientId/clientSecret literal values, but two distinct ApiEnvironment rows - the cache
		// key must still distinguish them (sprint doc section 20's corrective requirement), since two
		// environments could coincidentally share credentials without genuinely being the same context.
		server.setRoute("/oauth/token", 200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");
		ApiAuthenticationRuntime runtime = newRuntime();
		saveOAuth2Auth(null);
		ApiEnvironment envA = environment(Map.of("clientId", "shared-client", "clientSecret", "shared-secret"));
		ApiEnvironment envB = environment(Map.of("clientId", "shared-client", "clientSecret", "shared-secret"));

		sendAuthenticatedResourceRequest(runtime, envA);
		sendAuthenticatedResourceRequest(runtime, envB);

		Assertions.assertEquals(2, server.countRequestsTo("/oauth/token"), "identical credentials under two different environment rows must still be fetched separately");
	}

	@Test
	void sameTokenUrlAndClientIdWithDifferentScopeNeverShareACachedToken() throws BroadSQLException, IOException, InterruptedException {
		server.setRoute("/oauth/token", 200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");
		ApiEnvironment env = environment(Map.of("clientId", "client-a", "clientSecret", "secret-a"));
		ApiAuthenticationRuntime runtime = newRuntime();

		ApiEndpoint secondEndpoint = new ApiEndpoint(version.getId(), null, "Resource 2", "GET", "/api/resource2", 0);
		vault.saveEndpoint(secondEndpoint);
		ApiAuthConfig authRead = new ApiAuthConfig(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.OAUTH2_CLIENT_CREDENTIALS);
		vault.saveAuth(authRead);
		saveProperty(authRead, "accessTokenUrl", "${baseUrl}/oauth/token", false);
		saveProperty(authRead, "clientId", "${clientId}", false);
		saveProperty(authRead, "clientSecret", "${clientSecret}", true);
		saveProperty(authRead, "scope", "read", false);
		ApiAuthConfig authWrite = new ApiAuthConfig(ApiOwnerType.ENDPOINT, String.valueOf(secondEndpoint.getId()), ApiAuthType.OAUTH2_CLIENT_CREDENTIALS);
		vault.saveAuth(authWrite);
		saveProperty(authWrite, "accessTokenUrl", "${baseUrl}/oauth/token", false);
		saveProperty(authWrite, "clientId", "${clientId}", false);
		saveProperty(authWrite, "clientSecret", "${clientSecret}", true);
		saveProperty(authWrite, "scope", "write", false);

		ApiHttpRequest request1 = resourceRequest();
		runtime.authenticate(request1, API_ID, env, endpoint);
		new ApiHttpTransport().send(request1);
		ApiHttpRequest request2 = new ApiHttpRequest();
		request2.setMethod("GET");
		request2.setUri(URI.create(server.baseUrl() + "/api/resource2"));
		runtime.authenticate(request2, API_ID, env, secondEndpoint);
		new ApiHttpTransport().send(request2);

		Assertions.assertEquals(2, server.countRequestsTo("/oauth/token"), "a different scope must never reuse a token cached under a different scope");
	}

	@Test
	void changingTheClientSecretOnTheSameAuthDefinitionNeverReusesThePreviousToken() throws BroadSQLException, IOException, InterruptedException {
		server.setRoute("/oauth/token", 200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");
		ApiAuthConfig auth = new ApiAuthConfig(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.OAUTH2_CLIENT_CREDENTIALS);
		vault.saveAuth(auth);
		saveProperty(auth, "accessTokenUrl", "${baseUrl}/oauth/token", false);
		saveProperty(auth, "clientId", "${clientId}", false);
		ApiAttribute secretProperty = new ApiAttribute(ApiOwnerType.AUTH, String.valueOf(auth.getId()), ApiAttributeKind.PROPERTY, "clientSecret", "${clientSecret}", true);
		vault.saveAttribute(secretProperty);
		ApiEnvironment env = environment(Map.of("clientId", "client-a", "clientSecret", "secret-v1"));
		ApiAuthenticationRuntime runtime = newRuntime();

		sendAuthenticatedResourceRequest(runtime, env);

		// The effective credential configuration changes (same auth row, same token URL, same client ID)
		// while the token cached for the old secret would still report as unexpired - this must not be
		// reused, since it was never actually issued for the new secret.
		List<ApiAttribute> envVars = vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE);
		ApiAttribute clientSecretVar = envVars.stream().filter(a -> "clientSecret".equals(a.getName())).findFirst().orElseThrow();
		clientSecretVar.setValue("secret-v2");
		vault.saveAttribute(clientSecretVar);

		sendAuthenticatedResourceRequest(runtime, env);

		Assertions.assertEquals(2, server.countRequestsTo("/oauth/token"), "a changed client secret must never reuse a token cached under the previous secret");
	}

	@Test
	void tokenEndpointErrorStatusThrowsAndNeverAppliesAToken() throws BroadSQLException {
		server.setRoute("/oauth/token", 401, "{\"error\":\"invalid_client\"}");
		ApiAuthenticationRuntime runtime = newRuntime();
		saveOAuth2Auth(null);
		ApiEnvironment env = environment(Map.of("clientId", "wrong", "clientSecret", "wrong"));
		ApiHttpRequest request = resourceRequest();

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> runtime.authenticate(request, API_ID, env, endpoint));

		Assertions.assertTrue(ex.getLocalizedMessage().contains("401"));
		Assertions.assertFalse(ex.getLocalizedMessage().contains("invalid_client"), "the token endpoint's response body must never be echoed - it can contain submitted values");
	}

	@Test
	void malformedJsonTokenResponseThrows() throws BroadSQLException {
		server.setRoute("/oauth/token", 200, "not json at all {{{");
		ApiAuthenticationRuntime runtime = newRuntime();
		saveOAuth2Auth(null);
		ApiEnvironment env = environment(Map.of("clientId", "client-a", "clientSecret", "secret-a"));
		ApiHttpRequest request = resourceRequest();

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> runtime.authenticate(request, API_ID, env, endpoint));
		Assertions.assertTrue(ex.getLocalizedMessage().toLowerCase().contains("malformed"));
	}

	@Test
	void missingAccessTokenInResponseThrows() throws BroadSQLException {
		server.setRoute("/oauth/token", 200, "{\"token_type\":\"Bearer\",\"expires_in\":3600}");
		ApiAuthenticationRuntime runtime = newRuntime();
		saveOAuth2Auth(null);
		ApiEnvironment env = environment(Map.of("clientId", "client-a", "clientSecret", "secret-a"));
		ApiHttpRequest request = resourceRequest();

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> runtime.authenticate(request, API_ID, env, endpoint));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("access_token"));
	}

	private ApiAuthenticationRuntime newRuntime() {
		return new ApiAuthenticationRuntime(vault);
	}

	private void saveOAuth2Auth(String tokenPlacement) throws BroadSQLException {
		ApiAuthConfig auth = new ApiAuthConfig(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.OAUTH2_CLIENT_CREDENTIALS);
		vault.saveAuth(auth);
		saveProperty(auth, "accessTokenUrl", "${baseUrl}/oauth/token", false);
		saveProperty(auth, "clientId", "${clientId}", false);
		saveProperty(auth, "clientSecret", "${clientSecret}", true);
		if (tokenPlacement != null) {
			saveProperty(auth, "tokenPlacement", tokenPlacement, false);
		}
	}

	private void saveProperty(ApiAuthConfig auth, String name, String value, boolean secret) throws BroadSQLException {
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.AUTH, String.valueOf(auth.getId()), ApiAttributeKind.PROPERTY, name, value, secret));
	}

	private ApiEnvironment environment(Map<String, String> credentialVariables) throws BroadSQLException {
		ApiEnvironment env = new ApiEnvironment(API_ID, "Env-" + System.nanoTime(), null, 0);
		vault.saveEnvironment(env);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "baseUrl", server.baseUrl(), false));
		for (Map.Entry<String, String> entry : credentialVariables.entrySet()) {
			vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, entry.getKey(), entry.getValue(), false));
		}
		return env;
	}

	private ApiHttpRequest resourceRequest() {
		ApiHttpRequest request = new ApiHttpRequest();
		request.setMethod("GET");
		request.setUri(URI.create(server.baseUrl() + "/api/resource"));
		return request;
	}

	private void sendAuthenticatedResourceRequest(ApiAuthenticationRuntime runtime, ApiEnvironment env) throws BroadSQLException, IOException, InterruptedException {
		ApiHttpRequest request = resourceRequest();
		runtime.authenticate(request, API_ID, env, endpoint);
		new ApiHttpTransport().send(request);
	}
}
