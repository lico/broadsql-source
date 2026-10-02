package com.upandcoding.broadsql.dao.api.execution;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.auth.TestLocalHttpServer;
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
 * Covers {@link ApiEndpointExecutor} - docs/SPRINT XT02 - Universal API Client.md, section 21 - against a
 * real local server, proving the full policy -&gt; build -&gt; authenticate -&gt; send pipeline.
 */
class TestApiEndpointExecutor {

	private static final String API_ID = "EXECTEST";

	private TestLocalHttpServer server;
	private ApiDefinitionsVault vault;
	private ApiVersion version;
	private ApiEnvironment environment;

	@BeforeEach
	void setUp() throws IOException, BroadSQLException {
		server = new TestLocalHttpServer();
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		environment = new ApiEnvironment(API_ID, "Env", server.baseUrl(), 0);
		vault.saveEnvironment(environment);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(environment.getId()), ApiAttributeKind.VARIABLE, "baseUrl", server.baseUrl(), false));
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	void executesAGetRequestAndReturnsA2xxResult() throws BroadSQLException {
		server.setRoute("/users", 200, "[{\"id\":1,\"name\":\"Alice\"}]");
		ApiEndpoint endpoint = saveEndpoint("GET", "${baseUrl}/users");

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		Assertions.assertEquals(200, result.getStatusCode());
		Assertions.assertTrue(result.getBody().contains("Alice"));
	}

	@Test
	void nonSuccessfulHttpStatusIsReturnedAsANormalResultNotAnException() throws BroadSQLException {
		server.setRoute("/missing", 404, "{\"error\":\"not found\"}");
		ApiEndpoint endpoint = saveEndpoint("GET", "${baseUrl}/missing");

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		Assertions.assertEquals(404, result.getStatusCode(), "a valid HTTP error response must not be thrown as an exception");
		Assertions.assertTrue(result.getBody().contains("not found"));
	}

	@Test
	void executesAHeadRequestWithoutRequiringABody() throws BroadSQLException {
		server.setRoute("/resource", 200, "");
		ApiEndpoint endpoint = saveEndpoint("HEAD", "${baseUrl}/resource");

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		Assertions.assertEquals(200, result.getStatusCode());
		Assertions.assertEquals("HEAD", server.lastRequestTo("/resource").method);
	}

	// SPRINT XT02-8 made POST/PUT/PATCH/DELETE executable - OPTIONS remains the still-unsupported verb
	// this test uses to prove a refused method never reaches the network.
	@Test
	void anUnsupportedVerbIsRefusedBeforeAnyRequestReachesTheServer() throws BroadSQLException {
		ApiEndpoint endpoint = saveEndpoint("OPTIONS", "${baseUrl}/users");
		ApiEndpointExecutor executor = new ApiEndpointExecutor(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> executor.execute(API_ID, environment, endpoint, null));

		Assertions.assertTrue(ex.getLocalizedMessage().contains("Execution refused"));
		Assertions.assertEquals(0, server.countRequestsTo("/users"), "a refused verb must never reach the server");
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02-8: write-verb execution against a real local server
	// ------------------------------------------------------------------------------------------

	@Test
	void postSendsAnInterpolatedJsonBodyAndReturnsA201() throws BroadSQLException {
		server.setRoute("/customers", 201, "{\"id\":42,\"status\":\"created\"}");
		ApiEndpoint endpoint = saveEndpoint("POST", "${baseUrl}/customers");
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{\"name\": \"{{CUSTOMER_NAME}}\"}");
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "source", "broadsql", false));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.HEADER, "X-Tenant", "acme", false));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.VARIABLE, "CUSTOMER_NAME", "Alice", false));

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		Assertions.assertEquals(201, result.getStatusCode());
		TestLocalHttpServer.RecordedRequest received = server.lastRequestTo("/customers");
		Assertions.assertEquals("POST", received.method);
		Assertions.assertEquals("{\"name\": \"Alice\"}", received.body);
		Assertions.assertEquals("broadsql", extractQueryValue(received.uri.getRawQuery(), "source"));
		Assertions.assertEquals("acme", received.headers.getFirst("X-Tenant"));
		Assertions.assertEquals("application/json", received.headers.getFirst("Content-Type"));
		Assertions.assertTrue(result.getBody().contains("created"));
	}

	@Test
	void putSendsToAPathParameterInterpolatedUrlWithABody() throws BroadSQLException {
		server.setRoute("/customers/42", 200, "{\"id\":42,\"status\":\"updated\"}");
		ApiEndpoint endpoint = saveEndpoint("PUT", "${baseUrl}/customers/${customerId}");
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{\"name\": \"Alice Updated\"}");
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.PATH_PARAMETER, "customerId", "42", false));

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		Assertions.assertEquals(200, result.getStatusCode());
		Assertions.assertEquals(1, server.countRequestsTo("/customers/42"));
		Assertions.assertEquals("PUT", server.lastRequestTo("/customers/42").method);
		Assertions.assertEquals("{\"name\": \"Alice Updated\"}", server.lastRequestTo("/customers/42").body);
	}

	// PATCH is sometimes accidentally implemented as PUT internally - this asserts the literal wire method.
	@Test
	void patchIsSentAsTheLiteralPatchMethodNotRewrittenToPut() throws BroadSQLException {
		server.setRoute("/customers/42", 200, "{\"id\":42}");
		ApiEndpoint endpoint = saveEndpoint("PATCH", "${baseUrl}/customers/42");
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{\"status\": \"active\"}");
		vault.saveEndpoint(endpoint);

		new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		Assertions.assertEquals("PATCH", server.lastRequestTo("/customers/42").method, "PATCH must be sent as PATCH on the wire, never rewritten to PUT");
		Assertions.assertEquals("{\"status\": \"active\"}", server.lastRequestTo("/customers/42").body);
	}

	@Test
	void deleteWithPathAndQueryParametersHandlesA204NoContentCleanly() throws BroadSQLException {
		server.setRoute("/customers/42", 204, "");
		ApiEndpoint endpoint = saveEndpoint("DELETE", "${baseUrl}/customers/${customerId}");
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.PATH_PARAMETER, "customerId", "42", false));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "hard", "true", false));

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		Assertions.assertEquals(204, result.getStatusCode());
		Assertions.assertEquals("DELETE", server.lastRequestTo("/customers/42").method);
		Assertions.assertEquals("true", extractQueryValue(server.lastRequestTo("/customers/42").uri.getRawQuery(), "hard"));
		Assertions.assertEquals(0, result.getBodyBytes().length, "a 204 must be handled as a normal, bodiless result - not an error");
	}

	@Test
	void deleteMayHaveNoBodyAndStillExecute() throws BroadSQLException {
		server.setRoute("/customers/1", 204, "");
		ApiEndpoint endpoint = saveEndpoint("DELETE", "${baseUrl}/customers/1");
		vault.saveEndpoint(endpoint);
		// No bodyMode/bodyContent set at all - DELETE must not require one.

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		Assertions.assertEquals(204, result.getStatusCode());
	}

	@Test
	void aNonSuccessfulWriteResponseIsStillReturnedAsANormalResult() throws BroadSQLException {
		server.setRoute("/customers", 409, "{\"error\":\"duplicate\"}");
		ApiEndpoint endpoint = saveEndpoint("POST", "${baseUrl}/customers");
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{\"name\": \"Alice\"}");
		vault.saveEndpoint(endpoint);

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		Assertions.assertEquals(409, result.getStatusCode(), "a 409 from a write endpoint must not be thrown as an exception");
		Assertions.assertTrue(result.getBody().contains("duplicate"));
	}

	@Test
	void bearerAuthenticationIsAppliedFromTheEffectiveAuthChain() throws BroadSQLException {
		server.setRoute("/secure", 200, "{\"ok\":true}");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(environment.getId()), ApiAttributeKind.VARIABLE, "token", "s3cr3t", true));
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.API, API_ID, ApiAuthType.BEARER));
		ApiAuthConfig apiAuth = vault.getAuth(ApiOwnerType.API, API_ID);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.AUTH, String.valueOf(apiAuth.getId()), ApiAttributeKind.PROPERTY, "token", "${token}", true));
		ApiEndpoint endpoint = saveEndpoint("GET", "${baseUrl}/secure");

		new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		Assertions.assertEquals("Bearer s3cr3t", server.lastRequestTo("/secure").headers.getFirst("Authorization"));
	}

	// ------------------------------------------------------------------------------------------
	// XT02 final corrective patch (Codex finding 1): header identity must be case-insensitive, so an
	// inherited header is replaced, not duplicated, by a more specific one that differs only by case -
	// verified against the real request the local server actually receives, not just the in-memory model.
	// ------------------------------------------------------------------------------------------

	@Test
	void endpointHeaderOverridesAnApiLevelHeaderWithDifferentCaseAndNoDuplicateReachesTheServer() throws BroadSQLException {
		server.setRoute("/resource", 200, "{}");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.API, API_ID, ApiAttributeKind.HEADER, "Content-Type", "application/json", false));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Patch Resource", "PATCH", "${baseUrl}/resource", 0);
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{}");
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.HEADER, "content-type", "application/merge-patch+json", false));

		new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		List<String> received = server.lastRequestTo("/resource").headers.get("Content-Type");
		Assertions.assertEquals(1, received.size(), "exactly one Content-Type value must reach the server, never two conflicting ones");
		Assertions.assertEquals("application/merge-patch+json", received.get(0), "the more specific endpoint value must win on the wire");
	}

	@Test
	void authenticationGeneratedAuthorizationHeaderReplacesADifferentlyCasedEndpointHeader() throws BroadSQLException {
		server.setRoute("/secure", 200, "{}");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(environment.getId()), ApiAttributeKind.VARIABLE, "token", "real-token", true));
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.API, API_ID, ApiAuthType.BEARER));
		ApiAuthConfig apiAuth = vault.getAuth(ApiOwnerType.API, API_ID);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.AUTH, String.valueOf(apiAuth.getId()), ApiAttributeKind.PROPERTY, "token", "${token}", true));
		ApiEndpoint endpoint = saveEndpoint("GET", "${baseUrl}/secure");
		// A stray, differently-cased Authorization header configured directly on the endpoint must never
		// survive alongside the authentication-generated one.
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.HEADER, "authorization", "should-never-be-sent", false));

		new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		List<String> received = server.lastRequestTo("/secure").headers.get("Authorization");
		Assertions.assertEquals(1, received.size(), "exactly one Authorization value must reach the server");
		Assertions.assertEquals("Bearer real-token", received.get(0), "authentication must win over a stray, differently-cased endpoint header");
	}

	@Test
	void apiKeyQuerySecretIsRedactedInTheResultsSafeUrlButPresentInTheActualRequest() throws BroadSQLException {
		server.setRoute("/data", 200, "{}");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(environment.getId()), ApiAttributeKind.VARIABLE, "apiKey", "topsecretvalue", true));
		ApiEndpoint endpoint = saveEndpoint("GET", "${baseUrl}/data");
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.API_KEY_QUERY));
		ApiAuthConfig auth = vault.getAuth(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.AUTH, String.valueOf(auth.getId()), ApiAttributeKind.PROPERTY, "name", "api_key", false));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.AUTH, String.valueOf(auth.getId()), ApiAttributeKind.PROPERTY, "value", "${apiKey}", true));

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);

		Assertions.assertFalse(result.getSafeUrl().contains("topsecretvalue"), "the displayed URL must never contain the real secret");
		Assertions.assertTrue(result.getSafeUrl().contains("api_key=******"));
		Assertions.assertEquals("topsecretvalue", extractQueryValue(server.lastRequestTo("/data").uri.getRawQuery(), "api_key"), "the real network request must still carry the real value");
	}

	@Test
	void unresolvedVariableIsRefusedBeforeAnyRequestReachesTheServer() throws BroadSQLException {
		ApiEndpoint endpoint = saveEndpoint("GET", "${baseUrl}/project/${cloudId}");
		ApiEndpointExecutor executor = new ApiEndpointExecutor(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> executor.execute(API_ID, environment, endpoint, null));

		Assertions.assertEquals("Unable to resolve variable: cloudId", ex.getLocalizedMessage());
	}

	@Test
	void unsupportedAuthenticationIsRefusedBeforeAnyRequestReachesTheServer() throws BroadSQLException {
		ApiEndpoint endpoint = saveEndpoint("GET", "${baseUrl}/secure");
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.UNSUPPORTED));
		ApiAuthConfig auth = vault.getAuth(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.AUTH, String.valueOf(auth.getId()), ApiAttributeKind.PROPERTY, "unsupportedSourceAuthType", "digest", false));
		ApiEndpointExecutor executor = new ApiEndpointExecutor(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> executor.execute(API_ID, environment, endpoint, null));

		Assertions.assertTrue(ex.getLocalizedMessage().contains("DIGEST"));
		Assertions.assertEquals(0, server.countRequestsTo("/secure"));
	}

	private ApiEndpoint saveEndpoint(String method, String path) throws BroadSQLException {
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Endpoint", method, path, 0);
		vault.saveEndpoint(endpoint);
		return endpoint;
	}

	private String extractQueryValue(String rawQuery, String name) {
		for (String pair : rawQuery.split("&")) {
			String[] kv = pair.split("=", 2);
			if (kv[0].equals(name)) {
				return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
			}
		}
		return null;
	}
}
