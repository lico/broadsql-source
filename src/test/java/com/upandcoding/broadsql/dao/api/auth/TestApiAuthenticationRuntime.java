package com.upandcoding.broadsql.dao.api.auth;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
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
 * Covers {@link ApiAuthenticationRuntime} for every static (non-OAuth2) authentication type - docs/
 * SPRINT XT02 - Universal API Client.md, section 19 - against a real local HTTP server
 * ({@link TestLocalHttpServer}), so what is asserted is what a real server actually received, not an
 * internal representation. OAuth2 Client Credentials has its own dedicated test class
 * ({@link TestApiOAuth2ClientCredentials}) given its size.
 */
class TestApiAuthenticationRuntime {

	private static final String API_ID = "AUTHRUNTIME";

	private TestLocalHttpServer server;

	@BeforeEach
	void startServer() throws IOException {
		server = new TestLocalHttpServer();
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void basicAuthenticationSendsCorrectlyEncodedAuthorizationHeader() throws BroadSQLException, IOException, InterruptedException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = saveEndpoint(vault, version);
		saveAuth(vault, ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.BASIC,
				attr("username", "${username}", false), attr("password", "${password}", true));
		ApiEnvironment env = environment(vault, Map.of("username", "alice", "password", "s3cr3t"));

		sendAuthenticatedRequest(vault, endpoint, env);

		String authHeader = server.lastRequestTo("/resource").headers.getFirst("Authorization");
		String expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Assertions.assertEquals(expected, authHeader);
	}

	@Test
	void bearerAuthenticationSendsTheResolvedToken() throws BroadSQLException, IOException, InterruptedException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = saveEndpoint(vault, version);
		saveAuth(vault, ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.BEARER, attr("token", "${token}", true));
		ApiEnvironment env = environment(vault, Map.of("token", "dev-secret-token"));

		sendAuthenticatedRequest(vault, endpoint, env);

		Assertions.assertEquals("Bearer dev-secret-token", server.lastRequestTo("/resource").headers.getFirst("Authorization"));
	}

	@Test
	void apiKeyHeaderAuthenticationUsesTheConfiguredHeaderNameNeverHardcoded() throws BroadSQLException, IOException, InterruptedException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = saveEndpoint(vault, version);
		saveAuth(vault, ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.API_KEY_HEADER,
				attr("name", "X-Custom-Key", false), attr("value", "${apiKey}", true));
		ApiEnvironment env = environment(vault, Map.of("apiKey", "my-key-123"));

		sendAuthenticatedRequest(vault, endpoint, env);

		Assertions.assertEquals("my-key-123", server.lastRequestTo("/resource").headers.getFirst("X-Custom-Key"));
		Assertions.assertNull(server.lastRequestTo("/resource").headers.getFirst("X-API-Key"), "the header name must come from configuration, never a hardcoded default");
	}

	@Test
	void apiKeyQueryAuthenticationCorrectlyEncodesTheValue() throws BroadSQLException, IOException, InterruptedException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = saveEndpoint(vault, version);
		saveAuth(vault, ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.API_KEY_QUERY,
				attr("name", "api_key", false), attr("value", "${apiKey}", true));
		ApiEnvironment env = environment(vault, Map.of("apiKey", "value with spaces & symbols"));

		sendAuthenticatedRequest(vault, endpoint, env);

		String query = server.lastRequestTo("/resource").uri.getRawQuery();
		Assertions.assertEquals("api_key=value+with+spaces+%26+symbols", query);
	}

	@Test
	void switchingEnvironmentProducesDifferentAuthenticationWithoutChangingTheApiDefinition() throws BroadSQLException, IOException, InterruptedException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = saveEndpoint(vault, version);
		saveAuth(vault, ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.BEARER, attr("token", "${token}", true));
		ApiEnvironment dev = environment(vault, Map.of("token", "dev-token"));
		ApiEnvironment prod = environment(vault, Map.of("token", "prod-token"));

		sendAuthenticatedRequest(vault, endpoint, dev);
		String devHeader = server.lastRequestTo("/resource").headers.getFirst("Authorization");
		sendAuthenticatedRequest(vault, endpoint, prod);
		String prodHeader = server.lastRequestTo("/resource").headers.getFirst("Authorization");

		Assertions.assertEquals("Bearer dev-token", devHeader);
		Assertions.assertEquals("Bearer prod-token", prodHeader);
		Assertions.assertNotEquals(devHeader, prodHeader, "the same persisted auth definition must produce different credentials per environment");
	}

	@Test
	void explicitNoneAtEndpointSuppressesInheritedAuthenticationAndSendsNoAuthorizationHeader() throws BroadSQLException, IOException, InterruptedException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		saveAuth(vault, ApiOwnerType.API, API_ID, ApiAuthType.BEARER, attr("token", "${token}", true));
		ApiEndpoint endpoint = saveEndpoint(vault, version);
		saveAuth(vault, ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.NONE);
		ApiEnvironment env = environment(vault, Map.of("token", "should-never-be-sent"));

		sendAuthenticatedRequest(vault, endpoint, env);

		Assertions.assertNull(server.lastRequestTo("/resource").headers.getFirst("Authorization"), "explicit NONE must suppress the API's inherited Bearer token entirely");
	}

	@Test
	void unsupportedAuthenticationThrowsBeforeAnyRequestReachesTheServer() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = saveEndpoint(vault, version);
		saveAuth(vault, ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.UNSUPPORTED, attr("unsupportedSourceAuthType", "digest", false));
		ApiEnvironment env = environment(vault, Map.of());
		ApiAuthenticationRuntime runtime = new ApiAuthenticationRuntime(vault);
		ApiHttpRequest request = new ApiHttpRequest();
		request.setMethod("GET");
		request.setUri(URI.create(server.baseUrl() + "/resource"));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> {
			runtime.authenticate(request, API_ID, env, endpoint);
			new ApiHttpTransport().send(request); // must never execute
		});

		Assertions.assertTrue(ex.getLocalizedMessage().contains("DIGEST"), "the message must name the original unsupported type");
		Assertions.assertTrue(ex.getLocalizedMessage().contains("not supported"));
		Assertions.assertEquals(0, server.countRequestsTo("/resource"), "no request may reach the target server when authentication preparation fails");
	}

	@Test
	void missingRequiredVariableThrowsBeforeAnyRequestReachesTheServer() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = saveEndpoint(vault, version);
		saveAuth(vault, ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.BEARER, attr("token", "${token}", true));
		ApiEnvironment env = environment(vault, Map.of()); // "token" deliberately not defined anywhere
		ApiAuthenticationRuntime runtime = new ApiAuthenticationRuntime(vault);
		ApiHttpRequest request = new ApiHttpRequest();
		request.setMethod("GET");
		request.setUri(URI.create(server.baseUrl() + "/resource"));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> {
			runtime.authenticate(request, API_ID, env, endpoint);
			new ApiHttpTransport().send(request); // must never execute
		});

		Assertions.assertEquals("Unable to resolve authentication variable: token", ex.getLocalizedMessage());
		Assertions.assertEquals(0, server.countRequestsTo("/resource"), "no request may reach the target server, and never an empty/unresolved credential, when a variable cannot be resolved");
	}

	@Test
	void secretAttributeToStringNeverRevealsItsValue() throws BroadSQLException {
		ApiAttribute secret = new ApiAttribute(ApiOwnerType.AUTH, "1", ApiAttributeKind.PROPERTY, "token", "super-secret-value", true);
		Assertions.assertFalse(secret.toString().contains("super-secret-value"));
		Assertions.assertTrue(secret.toString().contains("******"));

		ApiAttribute nonSecret = new ApiAttribute(ApiOwnerType.AUTH, "1", ApiAttributeKind.PROPERTY, "name", "X-Api-Key", false);
		Assertions.assertTrue(nonSecret.toString().contains("X-Api-Key"), "a non-secret attribute's value is fine to show");
	}

	@Test
	void effectiveAuthToStringRedactsSecretPropertiesButNotOthers() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = saveEndpoint(vault, version);
		saveAuth(vault, ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.API_KEY_HEADER,
				attr("name", "X-Api-Key", false), attr("value", "top-secret", true));

		EffectiveAuth effective = new ApiEffectiveAuthResolver(vault).resolve(API_ID, endpoint);

		Assertions.assertFalse(effective.toString().contains("top-secret"));
		Assertions.assertTrue(effective.toString().contains("X-Api-Key"));
	}

	private void sendAuthenticatedRequest(ApiDefinitionsVault vault, ApiEndpoint endpoint, ApiEnvironment environment) throws BroadSQLException, IOException, InterruptedException {
		ApiAuthenticationRuntime runtime = new ApiAuthenticationRuntime(vault);
		ApiHttpRequest request = new ApiHttpRequest();
		request.setMethod("GET");
		request.setUri(URI.create(server.baseUrl() + "/resource"));
		runtime.authenticate(request, API_ID, environment, endpoint);
		new ApiHttpTransport().send(request);
	}

	private ApiEndpoint saveEndpoint(ApiDefinitionsVault vault, ApiVersion version) throws BroadSQLException {
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Resource", "GET", "/resource", 0);
		vault.saveEndpoint(endpoint);
		return endpoint;
	}

	private ApiEnvironment environment(ApiDefinitionsVault vault, Map<String, String> variables) throws BroadSQLException {
		ApiEnvironment env = new ApiEnvironment(API_ID, "Env-" + System.nanoTime(), null, 0);
		vault.saveEnvironment(env);
		for (Map.Entry<String, String> entry : variables.entrySet()) {
			vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, entry.getKey(), entry.getValue(), false));
		}
		return env;
	}

	private void saveAuth(ApiDefinitionsVault vault, ApiOwnerType ownerType, String ownerId, ApiAuthType type, ApiAttribute... properties) throws BroadSQLException {
		ApiAuthConfig config = new ApiAuthConfig(ownerType, ownerId, type);
		vault.saveAuth(config);
		for (ApiAttribute property : properties) {
			vault.saveAttribute(new ApiAttribute(ApiOwnerType.AUTH, String.valueOf(config.getId()), ApiAttributeKind.PROPERTY, property.getName(), property.getValue(), property.isSecret()));
		}
	}

	private ApiAttribute attr(String name, String value, boolean secret) {
		return new ApiAttribute(ApiOwnerType.AUTH, null, ApiAttributeKind.PROPERTY, name, value, secret);
	}
}
