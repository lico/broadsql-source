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
import com.upandcoding.broadsql.controller.shell.commands.CommandTestSupport;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandApiExecuteEndpoint;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.auth.TestLocalHttpServer;
import com.upandcoding.broadsql.dao.api.bruno.BrunoCollectionImporter;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * The XT02 overnight batch's sub-sprint 6 ("API results as tables") end-to-end acceptance test - a
 * sibling of {@link TestBrunoImportToExecutionEndToEnd}, same real architecture (real OpenCollection
 * YAML -&gt; {@link BrunoCollectionImporter} -&gt; a real local HTTP server -&gt; execution), but driven
 * through the actual {@link CommandApiExecuteEndpoint} command (via {@code CommandTestSupport}, a
 * captured console) rather than calling {@link ApiEndpointExecutor}/{@link ApiResponseRenderer} directly -
 * proving the {@code RAW} keyword parsing and the automatic table-vs-JSON decision both work from the
 * command's own argument handling, not just from the renderer in isolation.
 */
class TestApiTabularResultEndToEnd {

	private static final String API_ID = "TAB01";

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
	void anExplicitTableKeywordRendersARealArrayOfObjectsHttpResponseAsATableThroughTheActualCommand() throws BroadSQLException, IOException {
		// API Quality and UX Consolidation sprint: LIST is now the default - TABLE must be requested
		// explicitly to reach this pre-sprint "tabular" behavior, which is otherwise unchanged.
		server.setRoute("/users", 200, "[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
		importFixtureCollection();

		CommandApiExecuteEndpoint command = executeCommand("EXECUTE API ENDPOINT " + API_ID + " " + listUsersEndpointId() + " Test TABLE");
		String output = ((CapturingShellConsole) command.getConsole()).getOutput();

		Assertions.assertTrue(output.contains("id"), "the table header must include the 'id' column: " + output);
		Assertions.assertTrue(output.contains("name"), "the table header must include the 'name' column: " + output);
		Assertions.assertTrue(output.contains("Alice"));
		Assertions.assertTrue(output.contains("Bob"));
		Assertions.assertTrue(output.contains("2 rows."), "expected the row-count footer: " + output);
		Assertions.assertFalse(output.contains("[\n"), "a tabular result must not also be pretty-printed as JSON: " + output);
	}

	@Test
	void theDefaultRenderingIsNowACompleteListViewThroughTheActualCommand() throws BroadSQLException, IOException {
		server.setRoute("/users", 200, "[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
		importFixtureCollection();

		CommandApiExecuteEndpoint command = executeCommand("EXECUTE API ENDPOINT " + API_ID + " " + listUsersEndpointId() + " Test");
		String output = ((CapturingShellConsole) command.getConsole()).getOutput();

		Assertions.assertTrue(output.contains("[1]"), "the default must be the LIST view's indexed blocks: " + output);
		Assertions.assertTrue(output.contains("Alice"));
		Assertions.assertTrue(output.contains("Bob"));
		Assertions.assertFalse(output.contains("rows."), "the default must not print the TABLE mode's row-count footer: " + output);
	}

	@Test
	void theRawKeywordForcesPrettyJsonRenderingRegardlessOfShapeThroughTheActualCommand() throws BroadSQLException, IOException {
		server.setRoute("/users", 200, "[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
		importFixtureCollection();

		CommandApiExecuteEndpoint command = executeCommand("EXECUTE API ENDPOINT " + API_ID + " " + listUsersEndpointId() + " Test RAW");
		String output = ((CapturingShellConsole) command.getConsole()).getOutput();

		Assertions.assertTrue(output.contains("\"name\": \"Alice\""), "RAW must force pretty-printed JSON, not a table: " + output);
		Assertions.assertFalse(output.contains("rows."), "RAW must not print the tabular row-count footer: " + output);
	}

	@Test
	void theRawResponseBodyIsStillIntactAndReachableAfterTabularRendering() throws BroadSQLException, IOException {
		String rawBody = "[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]";
		server.setRoute("/users", 200, rawBody);
		importFixtureCollection();

		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEnvironment testEnv = findEnvironment("Test");
		ApiEndpoint listUsers = findEndpoint(version, "List Users");

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, testEnv, listUsers, null);
		// Rendering (twice - TABLE then RAW) must never mutate or consume the underlying result: the
		// original HTTP response body stays byte-for-byte intact and independently reachable afterwards.
		String tabularRendering = ApiResponseRenderer.render(result, ApiResultDisplayMode.TABLE);
		String rawRendering = ApiResponseRenderer.render(result, ApiResultDisplayMode.RAW);

		Assertions.assertTrue(tabularRendering.contains("Alice"));
		Assertions.assertTrue(rawRendering.contains("\"name\": \"Alice\""));
		Assertions.assertEquals(rawBody, result.getBody(), "the original response body must remain exactly intact after tabular rendering");
		Assertions.assertArrayEquals(rawBody.getBytes(StandardCharsets.UTF_8), result.getBodyBytes(),
				"the original response bytes must remain exactly intact after tabular rendering");
	}

	private CommandApiExecuteEndpoint executeCommand(String query) throws BroadSQLException {
		CommandApiExecuteEndpoint command = CommandTestSupport.create(CommandApiExecuteEndpoint.class, new CapturingShellConsole());
		command.setApiDefinitionsVault(vault);
		command.execute(query);
		return command;
	}

	private String listUsersEndpointId() throws BroadSQLException {
		ApiVersion version = vault.getDefaultVersion(API_ID);
		return String.valueOf(findEndpoint(version, "List Users").getId());
	}

	private void importFixtureCollection() throws BroadSQLException, IOException {
		String yaml = """
				opencollection: "1.0.0"
				bundled: true
				info:
				  name: Tabular Result Test API
				config:
				  environments:
				    - name: Test
				      variables:
				        - name: baseUrl
				          value: %s
				items:
				  - info:
				      name: List Users
				      type: http
				      seq: 1
				    http:
				      method: GET
				      url: "{{baseUrl}}/users"
				""".formatted(server.baseUrl());

		collectionFile = Files.createTempFile("xt02-tabular-e2e-", ".yml");
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
