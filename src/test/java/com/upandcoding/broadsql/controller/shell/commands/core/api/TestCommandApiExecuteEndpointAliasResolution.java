package com.upandcoding.broadsql.controller.shell.commands.core.api;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandTestSupport;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.LastApiExecutionResultHolder;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.auth.TestLocalHttpServer;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * SPRINT XT02-7B, section 10.2: {@code EXECUTE API ENDPOINT} must accept a BroadSQL alias in place of
 * the numeric endpoint id, so that {@code RUN}'s pure-delegation design needs no resolution logic of
 * its own.
 */
class TestCommandApiExecuteEndpointAliasResolution {

	private static final String API_ID = "DESK";

	private TestLocalHttpServer server;
	private ApiDefinitionsVault vault;
	private ApiVersion version;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws IOException, BroadSQLException {
		server = new TestLocalHttpServer();
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment environment = new ApiEnvironment(API_ID, "Test", null, 0);
		vault.setEnvironmentBaseUrl(environment, server.baseUrl());
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() {
		server.close();
		LastApiExecutionResultHolder.set(null);
	}

	private CommandApiExecuteEndpoint newCommand() {
		CommandApiExecuteEndpoint command = CommandTestSupport.create(CommandApiExecuteEndpoint.class, console);
		command.setApiDefinitionsVault(vault);
		return command;
	}

	@Test
	void resolvesAnEndpointByItsAlias() throws BroadSQLException {
		server.setRoute("/ping", 200, "{\"status\":\"ok\"}");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Ping", "GET", "${baseUrl}/ping", 0);
		endpoint.setAlias("PINGMAIL");
		vault.saveEndpoint(endpoint);

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " PINGMAIL Test");

		Assertions.assertEquals(1, server.countRequestsTo("/ping"));
		Assertions.assertTrue(console.getOutput().contains("HTTP 200"), console.getOutput());
	}

	@Test
	void aliasResolutionIsCaseInsensitive() throws BroadSQLException {
		server.setRoute("/ping", 200, "{\"status\":\"ok\"}");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Ping", "GET", "${baseUrl}/ping", 0);
		endpoint.setAlias("PINGMAIL");
		vault.saveEndpoint(endpoint);

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " pingmail Test");

		Assertions.assertEquals(1, server.countRequestsTo("/ping"));
	}

	@Test
	void anUnknownAliasIsReportedCleanly() throws BroadSQLException {
		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " DOES_NOT_EXIST Test");

		Assertions.assertTrue(console.getOutput().contains("not found"), console.getOutput());
		Assertions.assertEquals(0, server.countRequestsTo("/ping"));
	}

	@Test
	void anAliasBelongingToAnInactiveEndpointIsRefused() throws BroadSQLException {
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Ping", "GET", "${baseUrl}/ping", 0);
		endpoint.setAlias("PINGMAIL");
		vault.saveEndpoint(endpoint);
		vault.deactivateEndpoint(endpoint.getId());

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " PINGMAIL Test");

		// A deactivated endpoint's alias is immediately freed for reuse (existing amendment semantics,
		// unchanged by this sprint) - so this reports "not found", not "inactive"; that asymmetry with
		// the numeric-id path is intentional, not a bug.
		Assertions.assertTrue(console.getOutput().contains("not found"), console.getOutput());
		Assertions.assertEquals(0, server.countRequestsTo("/ping"));
	}

	@Test
	void theNumericIdPathIsUnchanged() throws BroadSQLException {
		server.setRoute("/ping", 200, "{\"status\":\"ok\"}");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Ping", "GET", "${baseUrl}/ping", 0);
		vault.saveEndpoint(endpoint);

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + endpoint.getId() + " Test");

		Assertions.assertEquals(1, server.countRequestsTo("/ping"));
	}

	@Test
	void anAliasIsScopedToItsOwnApi() throws BroadSQLException {
		ApiVersion otherVersion = vault.createApiWithDefaultVersion(new ApiDefinition("OTHER"));
		ApiEndpoint otherEndpoint = new ApiEndpoint(otherVersion.getId(), null, "Other ping", "GET", "/other-ping", 0);
		otherEndpoint.setAlias("PINGMAIL");
		vault.saveEndpoint(otherEndpoint);

		// PINGMAIL exists, but only under API "OTHER" - not under DESK.
		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " PINGMAIL Test");

		Assertions.assertTrue(console.getOutput().contains("not found"), console.getOutput());
	}
}
