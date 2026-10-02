package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageEnvironmentAdd;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageEnvironmentDelete;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageEnvironmentEdit;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/**
 * {@code ADD}/{@code EDIT}/{@code DEL ENVIRONMENT} are interactive wizards past their guard clauses
 * (same {@code console.inputField}/{@code readLine} limitation as {@code ADD}/{@code EDIT
 * CONNECTION}, see {@code TestCommandManageConnectionAdd}). Only the guard clauses are covered here.
 */
class TestCommandManageEnvironment {

	@Test
	void addRejectsAMissingId() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageEnvironmentAdd cmd = CommandTestSupport.create(CommandManageEnvironmentAdd.class, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		Assertions.assertDoesNotThrow(() -> cmd.execute("ADD ENVIRONMENT"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_ENV_01), "got:\n" + console.getOutput());
	}

	@Test
	void addRefusesAnIdThatAlreadyExistsActivelyCaseInsensitively() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageEnvironmentAdd cmd = CommandTestSupport.create(CommandManageEnvironmentAdd.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("ADD ENVIRONMENT pr"));

		Assertions.assertEquals(BroadSQLErrorMessages.ERR_ENV_02, ex.getLocalizedMessage());
	}

	@Test
	void editRejectsAnUnknownId() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageEnvironmentEdit cmd = CommandTestSupport.create(CommandManageEnvironmentEdit.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		Assertions.assertDoesNotThrow(() -> cmd.execute("EDIT ENVIRONMENT GHOST"));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains(BroadSQLErrorMessages.ERR_ENV_04), "got:\n" + output);
	}

	@Test
	void deleteRejectsAnUnknownId() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageEnvironmentDelete cmd = CommandTestSupport.create(CommandManageEnvironmentDelete.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DEL ENVIRONMENT GHOST"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_ENV_04), "got:\n" + console.getOutput());
	}

	@Test
	void deleteReportsAnAlreadyInactiveEnvironmentWithoutPromptingAndDoesNotThrow() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("QA", "Quality", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.softDeleteEnvironment("QA");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageEnvironmentDelete cmd = CommandTestSupport.create(CommandManageEnvironmentDelete.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DEL ENVIRONMENT qa"));

		Assertions.assertTrue(console.getOutput().contains("is already inactive"), "got:\n" + console.getOutput());
	}
}
