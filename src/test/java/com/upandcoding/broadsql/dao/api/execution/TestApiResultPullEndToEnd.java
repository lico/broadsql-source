package com.upandcoding.broadsql.dao.api.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandTestSupport;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandApiExecuteEndpoint;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.LastApiExecutionResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.auth.TestLocalHttpServer;
import com.upandcoding.broadsql.dao.api.bruno.BrunoCollectionImporter;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * The XT02 overnight batch's sub-sprint 7 ("API result export and local snapshot") true end-to-end
 * acceptance test - a sibling of {@link TestBrunoImportToExecutionEndToEnd}/
 * {@link com.upandcoding.broadsql.dao.api.execution.TestApiTabularResultEndToEnd}, same real architecture, one
 * step further: real OpenCollection YAML -&gt; {@link BrunoCollectionImporter} -&gt; a real local HTTP
 * server -&gt; {@link CommandApiExecuteEndpoint} (which tabularizes and captures
 * {@link LastApiExecutionResultHolder}) -&gt; {@link CommandPull}'s new {@code PULL API RESULT TO ...}
 * source form -&gt; verified by a plain {@code SELECT} against the real H2 target it created.
 */
class TestApiResultPullEndToEnd {

	private static final String API_ID = "PULL01";

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
		LastApiExecutionResultHolder.set(null);
	}

	@Test
	void aRealArrayOfObjectsHttpResponseCanBePulledIntoARealH2Table() throws BroadSQLException, IOException, SQLException {
		server.setRoute("/users", 200, "[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
		importFixtureCollection();

		CapturingShellConsole executeConsole = new CapturingShellConsole();
		CommandApiExecuteEndpoint executeCommand = CommandTestSupport.create(CommandApiExecuteEndpoint.class, executeConsole);
		executeCommand.setApiDefinitionsVault(vault);
		executeCommand.execute("EXECUTE API ENDPOINT " + API_ID + " " + listUsersEndpointId() + " Test");

		Assertions.assertNotNull(LastApiExecutionResultHolder.get(),
				"expected EXECUTE API ENDPOINT to have populated LastApiExecutionResultHolder");

		DatabaseDefinition targetDef = TestDatabaseConnections.newInMemoryTarget("PULLTARGET");
		CapturingShellConsole pullConsole = new CapturingShellConsole();
		CommandPull pullCommand = CommandTestSupport.create(CommandPull.class, pullConsole);
		DatabaseDefinitionsVault connectionsVault = new DatabaseDefinitionsVault();
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("PULLTARGET", targetDef);
		connectionsVault.setDatabaseConnections(connections);
		pullCommand.setDatabaseConnectionsVault(connectionsVault);

		pullCommand.execute("PULL API RESULT TO PULLTARGET.USERS AS H2");

		Assertions.assertTrue(pullConsole.getOutput().contains("2 row(s) pulled into PULLTARGET.USERS"),
				"expected the pull result summary, got:\n" + pullConsole.getOutput());

		try (Connection targetConn = DriverManager.getConnection(targetDef.getUrl());
				Statement stmt = targetConn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT \"id\", \"name\" FROM \"USERS\" ORDER BY \"id\"")) {
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals("1", rs.getString(1));
			Assertions.assertEquals("Alice", rs.getString(2));
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals("2", rs.getString(1));
			Assertions.assertEquals("Bob", rs.getString(2));
			Assertions.assertFalse(rs.next());
		}
	}

	private void importFixtureCollection() throws BroadSQLException, IOException {
		String yaml = """
				opencollection: "1.0.0"
				bundled: true
				info:
				  name: Pull API Result Test API
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

		collectionFile = Files.createTempFile("xt02-pull-e2e-", ".yml");
		Files.writeString(collectionFile, yaml, StandardCharsets.UTF_8);

		new BrunoCollectionImporter(vault).importFile(API_ID, collectionFile.toFile());
	}

	private String listUsersEndpointId() throws BroadSQLException {
		ApiVersion version = vault.getDefaultVersion(API_ID);
		return String.valueOf(findEndpoint(version, "List Users").getId());
	}

	private ApiEndpoint findEndpoint(ApiVersion version, String name) throws BroadSQLException {
		List<ApiEndpoint> endpoints = vault.getEndpointsForVersion(version.getId());
		return endpoints.stream().filter(e -> name.equals(e.getName())).findFirst()
				.orElseThrow(() -> new AssertionError("Expected endpoint '" + name + "' not found"));
	}
}
