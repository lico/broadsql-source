package com.upandcoding.broadsql.dao.api.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.auth.TestLocalHttpServer;
import com.upandcoding.broadsql.dao.api.bruno.BrunoCollectionImporter;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * The SPRINT XT02 MVP acceptance test (docs/SPRINT XT02 - Universal API Client.md, section 21) - the
 * complete, real architecture, no shortcuts:
 *
 * <pre>
 * real OpenCollection YAML file
 *     -&gt; BrunoCollectionImporter.importFile (the public entry point - not importRoot, not a hand-built
 *        ApiDefinition)
 *     -&gt; persistent API/environment/folder/endpoint/auth metadata
 *     -&gt; environment looked up by name (as a user selecting one would)
 *     -&gt; endpoint looked up by name within its folder (as a user browsing would)
 *     -&gt; ApiEndpointExecutor (variable resolution -&gt; authentication -&gt; HTTP execution)
 *     -&gt; a real local HTTP server
 *     -&gt; ApiResponseRenderer
 * </pre>
 *
 * Covers a Bearer-authenticated GET (collection-level auth, inherited by the endpoint), an OAuth2 Client
 * Credentials-authenticated GET (folder-level auth, its own token endpoint on the same local server), and
 * (SPRINT XT02-8) an imported write endpoint (a POST with a configured JSON body) actually executing,
 * sending its interpolated body to the real local server.
 */
class TestBrunoImportToExecutionEndToEnd {

	private static final String API_ID = "E2E";

	private TestLocalHttpServer server;
	private ApiDefinitionsVault vault;
	private Path collectionFile;

	@BeforeEach
	void setUp() throws IOException, BroadSQLException {
		server = new TestLocalHttpServer();
		vault = TestApiDefinitionsVaults.newFileBackedVault();
	}

	@AfterEach
	void tearDown() throws IOException {
		server.close();
		if (collectionFile != null) {
			Files.deleteIfExists(collectionFile);
		}
	}

	@Test
	void importsARealCollectionThenExecutesABearerAuthenticatedGetEndpointAndRendersJson() throws BroadSQLException, IOException {
		server.setRoute("/users/7", 200, "{\"id\":7,\"name\":\"Grace Hopper\"}");
		importFixtureCollection();

		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEnvironment testEnv = findEnvironment("Test");
		ApiEndpoint getUser = findEndpoint(version, "Get User");

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, testEnv, getUser, null);
		// RAW: this test is about authentication reaching the endpoint, not display-mode rendering
		// (LIST, the default since the API Quality and UX Consolidation sprint, would not pretty-print the
		// JSON - it shows "name : Grace Hopper" - so RAW forces the pretty-JSON path this assertion needs) -
		// this assertion still verifies what it always verified.
		String rendered = ApiResponseRenderer.render(result, ApiResultDisplayMode.RAW);

		Assertions.assertEquals(200, result.getStatusCode());
		Assertions.assertEquals("Bearer e2e-bearer-token", server.lastRequestTo("/users/7").headers.getFirst("Authorization"));
		Assertions.assertTrue(rendered.contains("\"name\": \"Grace Hopper\""), "the JSON response must be pretty-printed in the rendered output");
	}

	@Test
	void importsARealCollectionThenExecutesAnOAuth2AuthenticatedGetEndpointAndRendersJson() throws BroadSQLException, IOException {
		server.setRoute("/oauth/token", 200, "{\"access_token\":\"e2e-access-token\",\"expires_in\":3600}");
		server.setRoute("/reports/summary", 200, "{\"total\":42}");
		importFixtureCollection();

		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEnvironment testEnv = findEnvironment("Test");
		ApiEndpoint getReport = findEndpoint(version, "Get Report");

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, testEnv, getReport, null);
		// RAW: see the comment in the Bearer-authenticated test above - LIST, the default, would not
		// pretty-print the JSON, and this test is about the OAuth2 exchange, not display-mode rendering.
		String rendered = ApiResponseRenderer.render(result, ApiResultDisplayMode.RAW);

		Assertions.assertEquals(1, server.countRequestsTo("/oauth/token"), "the OAuth2 token exchange must actually happen");
		Assertions.assertEquals("Bearer e2e-access-token", server.lastRequestTo("/reports/summary").headers.getFirst("Authorization"));
		Assertions.assertEquals(200, result.getStatusCode());
		Assertions.assertTrue(rendered.contains("\"total\": 42"));
	}

	// SPRINT XT02-8: this test used to prove the imported POST endpoint stayed visible but was refused at
	// execution. It now proves the opposite - and more - deliberately, not as a weakened assertion: the
	// endpoint actually executes and its configured JSON body is sent to the real server.
	@Test
	void importedWriteEndpointExecutesAndSendsItsConfiguredBody() throws BroadSQLException, IOException {
		importFixtureCollection();
		ApiVersion version = vault.getDefaultVersion(API_ID);

		ApiEndpoint createUser = findEndpoint(version, "Create User");
		Assertions.assertEquals("POST", createUser.getMethod(), "a write endpoint must still be imported and visible");
		server.setRoute("/users", 201, "{\"id\":7,\"name\":\"Bob\"}");

		ApiEndpointExecutor executor = new ApiEndpointExecutor(vault);
		ApiEnvironment testEnv = findEnvironment("Test");
		ApiExecutionResult result = executor.execute(API_ID, testEnv, createUser, null);

		Assertions.assertEquals(201, result.getStatusCode());
		Assertions.assertEquals(1, server.countRequestsTo("/users"));
		Assertions.assertEquals("{\"name\": \"Bob\"}", server.lastRequestTo("/users").body,
				"the endpoint's configured JSON body must be sent verbatim (no variables to interpolate here)");
		Assertions.assertEquals("POST", server.lastRequestTo("/users").method);
	}

	private void importFixtureCollection() throws BroadSQLException, IOException {
		String yaml = """
				opencollection: "1.0.0"
				bundled: true
				info:
				  name: End To End Test API
				config:
				  environments:
				    - name: Test
				      variables:
				        - name: baseUrl
				          value: %s
				        - name: token
				          value: e2e-bearer-token
				          secret: true
				        - name: clientId
				          value: e2e-client
				        - name: clientSecret
				          value: e2e-secret
				          secret: true
				        - name: userId
				          value: "7"
				request:
				  auth:
				    type: bearer
				    token: "{{token}}"
				items:
				  - info:
				      name: Users
				      type: folder
				      seq: 1
				    items:
				      - info:
				          name: Get User
				          type: http
				          seq: 1
				        http:
				          method: GET
				          url: "{{baseUrl}}/users/{{userId}}"
				      - info:
				          name: Create User
				          type: http
				          seq: 2
				        http:
				          method: POST
				          url: "{{baseUrl}}/users"
				          body:
				            type: json
				            data: '{"name": "Bob"}'
				  - info:
				      name: Reports
				      type: folder
				      seq: 2
				    request:
				      auth:
				        type: oauth2
				        flow: client_credentials
				        accessTokenUrl: "{{baseUrl}}/oauth/token"
				        credentials:
				          clientId: "{{clientId}}"
				          clientSecret: "{{clientSecret}}"
				    items:
				      - info:
				          name: Get Report
				          type: http
				          seq: 1
				        http:
				          method: GET
				          url: "{{baseUrl}}/reports/summary"
				""".formatted(server.baseUrl());

		collectionFile = Files.createTempFile("xt02-e2e-", ".yml");
		Files.writeString(collectionFile, yaml, StandardCharsets.UTF_8);

		new BrunoCollectionImporter(vault).importFile(API_ID, collectionFile.toFile());
	}

	private ApiEnvironment findEnvironment(String name) throws BroadSQLException {
		List<ApiEnvironment> environments = vault.getEnvironmentsForApi(API_ID);
		return environments.stream().filter(e -> name.equals(e.getName())).findFirst()
				.orElseThrow(() -> new AssertionError("Expected environment '" + name + "' not found"));
	}

	private ApiEndpoint findEndpoint(ApiVersion version, String name) throws BroadSQLException {
		List<ApiEndpoint> endpoints = vault.getEndpointsForVersion(version.getId());
		return endpoints.stream().filter(e -> name.equals(e.getName())).findFirst()
				.orElseThrow(() -> new AssertionError("Expected endpoint '" + name + "' not found"));
	}
}
