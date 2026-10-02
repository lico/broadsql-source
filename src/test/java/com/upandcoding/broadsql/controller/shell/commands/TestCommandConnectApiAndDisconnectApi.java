package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandConnect;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandDisconnect;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * SPRINT XT02-7B: {@code CONNECT API <api>:<environment>;} / {@code DISCONNECT API;}, implemented as
 * branches inside {@link CommandConnect}/{@link CommandDisconnect} rather than separate registered
 * keywords (see the plan's "verified codebase facts" section on why - a second, more specific
 * {@code CONNECT API} keyword would be ambiguous under {@code CommandList}'s unordered {@code HashMap}
 * dispatch). Neither branch touches {@code sqlDatabase}/{@code platform} at all, so these tests use
 * {@code db=null} - exactly like the SQL-side {@code CONNECT}/{@code DISCONNECT} guard-clause tests.
 */
class TestCommandConnectApiAndDisconnectApi {

	private static final String API_ID = "DESK";

	private CapturingShellConsole console;
	private ApiDefinitionsVault vault;

	@AfterEach
	void tearDown() {
		ApiSessionContextHolder.clear();
	}

	private ApiDefinitionsVault newVaultWithActiveEnvironment(String environmentName) throws BroadSQLException {
		ApiDefinitionsVault v = TestApiDefinitionsVaults.newFileBackedVault();
		v.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		v.saveEnvironment(new ApiEnvironment(API_ID, environmentName, "https://example.test", 0));
		return v;
	}

	private CommandConnect newConnect() {
		console = new CapturingShellConsole();
		CommandConnect cmd = CommandTestSupport.create(CommandConnect.class, console);
		cmd.setApiDefinitionsVault(vault);
		return cmd;
	}

	private CommandDisconnect newDisconnect() {
		CommandDisconnect cmd = CommandTestSupport.create(CommandDisconnect.class, console);
		cmd.setApiDefinitionsVault(vault);
		return cmd;
	}

	@Test
	void connectApiSucceedsAndSetsTheActiveContext() throws BroadSQLException {
		vault = newVaultWithActiveEnvironment("PROD");
		CommandConnect cmd = newConnect();

		cmd.execute("CONNECT API " + API_ID + ":PROD");

		Assertions.assertNotNull(ApiSessionContextHolder.get());
		Assertions.assertEquals(API_ID, ApiSessionContextHolder.get().getApiId());
		Assertions.assertEquals("PROD", ApiSessionContextHolder.get().getEnvironmentName());
		Assertions.assertTrue(console.getOutput().contains("API " + API_ID + " connected using environment PROD."), console.getOutput());
	}

	@Test
	void connectApiRefusesAnUnknownApi() throws BroadSQLException {
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		CommandConnect cmd = newConnect();

		cmd.execute("CONNECT API BOGUS:PROD");

		Assertions.assertNull(ApiSessionContextHolder.get());
		Assertions.assertTrue(console.getOutput().contains("not found"), console.getOutput());
	}

	@Test
	void connectApiRefusesAnInactiveApi() throws BroadSQLException {
		vault = newVaultWithActiveEnvironment("PROD");
		ApiDefinition api = vault.getApi(API_ID);
		api.setStatusId(DatabaseDefinition.STATUS_INACTIVE);
		vault.saveApi(api);
		CommandConnect cmd = newConnect();

		cmd.execute("CONNECT API " + API_ID + ":PROD");

		Assertions.assertNull(ApiSessionContextHolder.get());
		Assertions.assertTrue(console.getOutput().contains("is inactive"), console.getOutput());
	}

	@Test
	void connectApiRefusesAnUnknownEnvironment() throws BroadSQLException {
		vault = newVaultWithActiveEnvironment("PROD");
		CommandConnect cmd = newConnect();

		cmd.execute("CONNECT API " + API_ID + ":DOES_NOT_EXIST");

		Assertions.assertNull(ApiSessionContextHolder.get());
		Assertions.assertTrue(console.getOutput().contains("not found"), console.getOutput());
	}

	@Test
	void connectApiRejectsAMalformedTargetMissingColon() throws BroadSQLException {
		vault = newVaultWithActiveEnvironment("PROD");
		CommandConnect cmd = newConnect();

		cmd.execute("CONNECT API " + API_ID + "PROD");

		Assertions.assertNull(ApiSessionContextHolder.get());
		Assertions.assertTrue(console.getOutput().contains("Malformed API target"), console.getOutput());
	}

	@Test
	void connectApiRejectsAMissingApiIdentifier() throws BroadSQLException {
		vault = newVaultWithActiveEnvironment("PROD");
		CommandConnect cmd = newConnect();

		cmd.execute("CONNECT API :PROD");

		Assertions.assertNull(ApiSessionContextHolder.get());
		Assertions.assertTrue(console.getOutput().contains("Missing API identifier"), console.getOutput());
	}

	@Test
	void connectApiRejectsAMissingEnvironmentName() throws BroadSQLException {
		vault = newVaultWithActiveEnvironment("PROD");
		CommandConnect cmd = newConnect();

		cmd.execute("CONNECT API " + API_ID + ":");

		Assertions.assertNull(ApiSessionContextHolder.get());
		Assertions.assertTrue(console.getOutput().contains("Missing environment name"), console.getOutput());
	}

	@Test
	void disconnectApiClearsOnlyTheApiContext() throws BroadSQLException {
		vault = newVaultWithActiveEnvironment("PROD");
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "PROD"));
		console = new CapturingShellConsole();
		CommandDisconnect cmd = newDisconnect();

		cmd.execute("DISCONNECT API");

		Assertions.assertNull(ApiSessionContextHolder.get());
	}

	@Test
	void disconnectApiIsANoOpWhenNoContextIsActive() throws BroadSQLException {
		vault = newVaultWithActiveEnvironment("PROD");
		console = new CapturingShellConsole();
		CommandDisconnect cmd = newDisconnect();

		Assertions.assertDoesNotThrow(() -> cmd.execute("DISCONNECT API"));
		Assertions.assertNull(ApiSessionContextHolder.get());
	}

	@Test
	void stateTransitionSequenceEndsWithTheLastConnectApiWinning() throws BroadSQLException {
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("A"));
		vault.saveEnvironment(new ApiEnvironment("A", "DEV", "https://a-dev.test", 0));
		vault.saveEnvironment(new ApiEnvironment("A", "PROD", "https://a-prod.test", 0));
		vault.createApiWithDefaultVersion(new ApiDefinition("B"));
		vault.saveEnvironment(new ApiEnvironment("B", "TEST", "https://b-test.test", 0));

		newConnect().execute("CONNECT API A:DEV");
		Assertions.assertEquals("A", ApiSessionContextHolder.get().getApiId());
		Assertions.assertEquals("DEV", ApiSessionContextHolder.get().getEnvironmentName());

		newConnect().execute("CONNECT API A:PROD");
		Assertions.assertEquals("PROD", ApiSessionContextHolder.get().getEnvironmentName());

		newConnect().execute("CONNECT API B:TEST");
		Assertions.assertEquals("B", ApiSessionContextHolder.get().getApiId());

		console = new CapturingShellConsole();
		newDisconnect().execute("DISCONNECT API");
		Assertions.assertNull(ApiSessionContextHolder.get());
	}
}
