package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandRun;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoints;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
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
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * SPRINT XT02-8's own capstone acceptance workflow (section 75): a full create/update/read/export/
 * delete cycle through the interactive commands only, against a real local HTTP server, proving the SQL
 * connection is never touched by any of it - this single test exercises most of the sprint's
 * state-transition requirements (section 50) directly, end to end.
 */
class TestXt02WriteVerbAcceptanceWorkflow {

	private static final String API_ID = "TEST_API";

	private TestLocalHttpServer server;
	private ApiDefinitionsVault vault;
	private DatabaseConnection db;
	private CapturingShellConsole console;

	@AfterEach
	void tearDown() throws BroadSQLException {
		if (server != null) {
			server.close();
		}
		LastApiExecutionResultHolder.set(null);
		ApiSessionContextHolder.clear();
		if (db != null) {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void createUpdateReadExportDeleteCycleLeavesTheSqlConnectionCompletelyUntouched(@TempDir Path tempDir) throws BroadSQLException, IOException {
		server = new TestLocalHttpServer();
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		db = TestDatabaseConnections.connectInMemory();
		console = new CapturingShellConsole();

		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment environment = new ApiEnvironment(API_ID, "TEST", null, 0);
		vault.setEnvironmentBaseUrl(environment, server.baseUrl());

		ApiEndpoint createItem = new ApiEndpoint(version.getId(), null, "Create Item", "POST", "${baseUrl}/items", 0);
		createItem.setAlias("CREATEITEM");
		createItem.setBodyMode("json");
		createItem.setBodyContent("{\"name\": \"{{ITEM_NAME}}\"}");
		vault.saveEndpoint(createItem);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(createItem.getId()), ApiAttributeKind.VARIABLE, "ITEM_NAME", "Widget", false));

		ApiEndpoint updateItem = new ApiEndpoint(version.getId(), null, "Update Item", "PATCH", "${baseUrl}/items/1", 1);
		updateItem.setAlias("UPDATEITEM");
		updateItem.setBodyMode("json");
		updateItem.setBodyContent("{\"name\": \"Widget Updated\"}");
		vault.saveEndpoint(updateItem);

		ApiEndpoint getItem = new ApiEndpoint(version.getId(), null, "Get Item", "GET", "${baseUrl}/items/1", 2);
		getItem.setAlias("GETITEM");
		vault.saveEndpoint(getItem);

		ApiEndpoint deleteItem = new ApiEndpoint(version.getId(), null, "Delete Item", "DELETE", "${baseUrl}/items/1", 3);
		deleteItem.setAlias("DELETEITEM");
		vault.saveEndpoint(deleteItem);

		String platformBefore = db.getPlatform() == null ? null : db.getPlatform().getId();

		// CONNECT API
		CommandConnect connectCmd = CommandTestSupport.create(CommandConnect.class, db, console);
		connectCmd.setApiDefinitionsVault(vault);
		connectCmd.execute("CONNECT API " + API_ID + ":TEST");
		Assertions.assertNotNull(ApiSessionContextHolder.get());

		// SHOW ENDPOINTS VERB POST
		CapturingShellConsole showConsole = new CapturingShellConsole();
		CommandShowEndpoints showCmd = CommandTestSupport.create(CommandShowEndpoints.class, showConsole);
		showCmd.setApiDefinitionsVault(vault);
		showCmd.execute("SHOW ENDPOINTS VERB POST");
		Assertions.assertTrue(showConsole.getOutput().contains("CREATEITEM"), showConsole.getOutput());

		// RUN POST /items (SPRINT XT02A URL-native grammar)
		server.setRoute("/items", 201, "{\"id\":1,\"name\":\"Widget\"}");
		newRun().execute("RUN POST /items");
		Assertions.assertEquals(1, server.countRequestsTo("/items"));
		Assertions.assertEquals("{\"name\": \"Widget\"}", server.lastRequestTo("/items").body);

		// SHOW ENDPOINTS VERB PATCH MATCH item
		showConsole = new CapturingShellConsole();
		showCmd = CommandTestSupport.create(CommandShowEndpoints.class, showConsole);
		showCmd.setApiDefinitionsVault(vault);
		showCmd.execute("SHOW ENDPOINTS VERB PATCH MATCH item");
		Assertions.assertTrue(showConsole.getOutput().contains("UPDATEITEM"), showConsole.getOutput());

		// RUN PATCH /items/1
		server.setRoute("/items/1", 200, "{\"id\":1,\"name\":\"Widget Updated\"}");
		newRun().execute("RUN PATCH /items/1");
		Assertions.assertEquals("PATCH", server.lastRequestTo("/items/1").method);
		Assertions.assertEquals("{\"name\": \"Widget Updated\"}", server.lastRequestTo("/items/1").body);

		// RUN GET /items/1 (GET is the default method - omitted, matching section 2.2)
		newRun().execute("RUN /items/1");
		Assertions.assertEquals("GET", server.lastRequestTo("/items/1").method);
		Assertions.assertNotNull(LastApiExecutionResultHolder.get(), "GETITEM's successful response must be held for PULL API RESULT");

		// PULL API RESULT TO item_snapshot AS CSV
		var settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		CommandPull pullCmd = CommandTestSupport.create(CommandPull.class, null, console, settings);
		pullCmd.execute("PULL API RESULT TO item_snapshot AS CSV");
		File snapshotFile = tempDir.resolve("item_snapshot.csv").toFile();
		Assertions.assertTrue(snapshotFile.exists(), "expected item_snapshot.csv, got console:\n" + console.getOutput());
		Assertions.assertTrue(Files.readString(snapshotFile.toPath()).contains("Widget Updated"));

		// RUN DELETE /items/1
		server.setRoute("/items/1", 204, "");
		newRun().execute("RUN DELETE /items/1");
		Assertions.assertEquals("DELETE", server.lastRequestTo("/items/1").method);

		// DISCONNECT API
		CommandDisconnect disconnectCmd = CommandTestSupport.create(CommandDisconnect.class, db, console);
		disconnectCmd.setApiDefinitionsVault(vault);
		disconnectCmd.execute("DISCONNECT API");
		Assertions.assertNull(ApiSessionContextHolder.get());

		// The SQL connection/platform must be exactly what it was before any API command ran.
		Assertions.assertTrue(db.isConnected(), "the SQL connection must still be open after the full write-verb cycle");
		String platformAfter = db.getPlatform() == null ? null : db.getPlatform().getId();
		Assertions.assertEquals(platformBefore, platformAfter, "CONNECT API/RUN/SHOW ENDPOINTS/DISCONNECT API must never change the SQL platform");
	}

	private CommandRun newRun() {
		console = new CapturingShellConsole();
		CommandRun cmd = CommandTestSupport.create(CommandRun.class, console);
		cmd.setApiDefinitionsVault(vault);
		return cmd;
	}
}
