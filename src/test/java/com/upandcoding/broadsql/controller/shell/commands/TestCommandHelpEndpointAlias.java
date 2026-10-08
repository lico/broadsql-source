package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.help.CommandHelp;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * SPRINT XT02A (URL-Native API Execution), section 3.5 - {@code HELP <endpoint-alias>;}, consulted
 * only once no command keyword matches, using exactly the same endpoint metadata {@code SYNTAX} does.
 */
class TestCommandHelpEndpointAlias {

	private DatabaseConnection db;

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
		LastQueryResultHolder.set(null);
		ApiSessionContextHolder.clear();
	}

	private CommandHelp newHelp(CapturingShellConsole console, ApiDefinitionsVault vault) {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		CommandHelp cmd = CommandTestSupport.create(CommandHelp.class, null, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console));
		cmd.setApiDefinitionsVault(vault);
		return cmd;
	}

	@Test
	void showsGeneratedSyntaxForAKnownEndpointAliasUnderTheActiveApiSession() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("DESK"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Get customer", "GET", "${baseUrl}/api/customer/${id}", 0);
		endpoint.setAlias("CUST");
		vault.saveEndpoint(endpoint);
		ApiSessionContextHolder.set(new ApiSessionContext("DESK", "Test"));

		CapturingShellConsole console = new CapturingShellConsole();
		newHelp(console, vault).execute("HELP CUST");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("GET /api/customer/:id"), output);
		Assertions.assertFalse(output.contains("No help available"), output);
	}

	@Test
	void fallsThroughToTheNormalNoHelpMessageWithoutAnActiveApiSession() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();

		CapturingShellConsole console = new CapturingShellConsole();
		newHelp(console, vault).execute("HELP CUST");

		Assertions.assertTrue(console.getOutput().contains("No help available"), console.getOutput());
	}
}
