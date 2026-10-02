package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandRun;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoints;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandConnect;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandDisconnect;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastApiExecutionResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.auth.TestLocalHttpServer;
import com.upandcoding.broadsql.dao.api.bruno.BrunoCollectionImporter;

/**
 * SPRINT XT02-7B, sections 30.16/46: the mixed SQL+API workflow this whole sprint is built around -
 * {@code CONNECT API}, {@code SHOW ENDPOINTS MATCH ...}, {@code RUN <url>} (SPRINT XT02A's URL-native
 * grammar) - proving throughout that
 * the independently-established SQL connection is never touched. Real OpenCollection YAML (using the
 * Bruno {@code {{baseUrl}}} mustache syntax, exercising the new SPRINT XT02-7B interpolation support)
 * imported through the real {@link BrunoCollectionImporter}, executed against a real local HTTP
 * server, driven entirely through the actual command classes (not by calling internals directly).
 */
class TestApiInteractiveWorkflowEndToEnd {

	private static final String API_ID = "DESK";

	private TestLocalHttpServer server;
	private ApiDefinitionsVault vault;
	private DatabaseConnection db;
	private Path collectionFile;
	private CapturingShellConsole console;

	@AfterEach
	void tearDown() throws BroadSQLException, IOException {
		if (server != null) {
			server.close();
		}
		if (collectionFile != null) {
			Files.deleteIfExists(collectionFile);
		}
		LastApiExecutionResultHolder.set(null);
		ApiSessionContextHolder.clear();
		if (db != null) {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void connectApiShowEndpointsAndRunLeaveTheSqlConnectionCompletelyUntouched() throws BroadSQLException, IOException {
		server = new TestLocalHttpServer();
		server.setRoute("/api/rest/emails/v1/ping", 200, "{\"status\":\"ok\"}");
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		db = TestDatabaseConnections.connectInMemory();
		console = new CapturingShellConsole();

		importFixtureCollection();

		String platformBeforeAnyApiCommand = db.getPlatform() == null ? null : db.getPlatform().getId();

		// 1. CONNECT API - must not disturb the SQL connection.
		CommandConnect connectCmd = CommandTestSupport.create(CommandConnect.class, db, console);
		connectCmd.setApiDefinitionsVault(vault);
		connectCmd.execute("CONNECT API " + API_ID + ":Test");
		Assertions.assertNotNull(ApiSessionContextHolder.get());
		Assertions.assertTrue(console.getOutput().contains("API " + API_ID + " connected using environment Test."), console.getOutput());
		Assertions.assertTrue(db.isConnected(), "the SQL connection must remain open after CONNECT API");

		// 2. SHOW ENDPOINTS MATCH - discovery within the active context, no API id repeated. Uses its own
		// console (SHOW ENDPOINTS never delegates through the interpreter, so there is no console-identity
		// concern here).
		CapturingShellConsole showConsole = new CapturingShellConsole();
		CommandShowEndpoints showCmd = CommandTestSupport.create(CommandShowEndpoints.class, showConsole);
		showCmd.setApiDefinitionsVault(vault);
		showCmd.execute("SHOW ENDPOINTS MATCH ping");
		Assertions.assertTrue(showConsole.getOutput().contains("PINGMAIL"), showConsole.getOutput());
		Assertions.assertTrue(showConsole.getOutput().contains("1 endpoint"), showConsole.getOutput());

		// 3. RUN <url> - SPRINT XT02A's URL-native grammar, resolved against the active CONNECT API
		// session (no explicit API/environment clause, no alias/id lookup).
		console = new CapturingShellConsole();
		CommandRun runCmd = CommandTestSupport.create(CommandRun.class, console);
		runCmd.setApiDefinitionsVault(vault);
		runCmd.execute("RUN /api/rest/emails/v1/ping");
		Assertions.assertEquals(1, server.countRequestsTo("/api/rest/emails/v1/ping"));
		Assertions.assertTrue(console.getOutput().contains("HTTP 200"), console.getOutput());

		// The SQL connection/platform must be exactly what it was before any API command ran.
		Assertions.assertTrue(db.isConnected(), "the SQL connection must still be open after RUN");
		String platformAfter = db.getPlatform() == null ? null : db.getPlatform().getId();
		Assertions.assertEquals(platformBeforeAnyApiCommand, platformAfter, "CONNECT API/SHOW ENDPOINTS/RUN must never change the SQL platform");

		// 4. DISCONNECT API clears only the API context.
		console = new CapturingShellConsole();
		CommandDisconnect disconnectApiCmd = CommandTestSupport.create(CommandDisconnect.class, db, console);
		disconnectApiCmd.setApiDefinitionsVault(vault);
		disconnectApiCmd.execute("DISCONNECT API");
		Assertions.assertNull(ApiSessionContextHolder.get());
		Assertions.assertTrue(db.isConnected(), "DISCONNECT API must never close the SQL connection");
	}

	private void importFixtureCollection() throws BroadSQLException, IOException {
		// Global variable KEY=DESK, referenced by the endpoint as {{KEY}} in a query parameter - the
		// real-world scenario from docs/SPRINT XT02-7B, section 26/38.
		String yaml = """
				opencollection: "1.0.0"
				bundled: true
				info:
				  name: Desk Definition API
				config:
				  environments:
				    - name: Test
				      variables:
				        - name: baseUrl
				          value: %s
				items:
				  - info:
				      name: Check email service
				      type: http
				      seq: 1
				    http:
				      method: GET
				      url: "{{baseUrl}}/api/rest/emails/v1/ping"
				""".formatted(server.baseUrl());

		collectionFile = Files.createTempFile("xt02-7b-e2e-", ".yml");
		Files.writeString(collectionFile, yaml, StandardCharsets.UTF_8);

		new BrunoCollectionImporter(vault).importFile(API_ID, collectionFile.toFile());

		// Assign the alias by hand (aliases are never derived from import identity/never auto-generated).
		var version = vault.getDefaultVersion(API_ID);
		var endpoint = vault.getEndpointsForVersion(version.getId()).stream()
				.filter(e -> "Check email service".equals(e.getName())).findFirst().orElseThrow();
		endpoint.setAlias("PINGMAIL");
		vault.saveEndpoint(endpoint);
	}
}
