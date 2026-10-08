package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageGroupAdd;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageGroupDelete;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageGroupEdit;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseGroupDefinition;

/**
 * {@code ADD}/{@code EDIT}/{@code DEL GROUP} are interactive wizards past their guard clauses (same
 * {@code console.inputField}/{@code readLine} limitation as {@code ADD}/{@code EDIT CONNECTION}, see
 * {@code TestCommandManageConnectionAdd}). Only the guard clauses are covered here.
 */
class TestCommandManageGroup {

	@Test
	void addRejectsAMissingId() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageGroupAdd cmd = CommandTestSupport.create(CommandManageGroupAdd.class, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		Assertions.assertDoesNotThrow(() -> cmd.execute("ADD GROUP"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_GROUP_01), "got:\n" + console.getOutput());
	}

	@Test
	void addRefusesAnIdThatAlreadyExistsActively() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, com.upandcoding.broadsql.dao.model.DatabaseDefinition.STATUS_ACTIVE));
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageGroupAdd cmd = CommandTestSupport.create(CommandManageGroupAdd.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("ADD GROUP TAT"));

		Assertions.assertEquals(BroadSQLErrorMessages.ERR_GROUP_02, ex.getLocalizedMessage());
	}

	@Test
	void addReportsAnInactiveIdWithoutThrowing() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, com.upandcoding.broadsql.dao.model.DatabaseDefinition.STATUS_ACTIVE));
		vault.softDeleteGroup("TAT");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageGroupAdd cmd = CommandTestSupport.create(CommandManageGroupAdd.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		Assertions.assertDoesNotThrow(() -> cmd.execute("ADD GROUP TAT"));

		Assertions.assertTrue(console.getOutput().contains("Use EDIT GROUP to reactivate it"), "got:\n" + console.getOutput());
	}

	@Test
	void editRejectsAMissingId() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageGroupEdit cmd = CommandTestSupport.create(CommandManageGroupEdit.class, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		Assertions.assertDoesNotThrow(() -> cmd.execute("EDIT GROUP"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_GROUP_01), "got:\n" + console.getOutput());
	}

	@Test
	void editRejectsAnUnknownId() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageGroupEdit cmd = CommandTestSupport.create(CommandManageGroupEdit.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		Assertions.assertDoesNotThrow(() -> cmd.execute("EDIT GROUP GHOST"));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains(BroadSQLErrorMessages.ERR_GROUP_04), "got:\n" + output);
		Assertions.assertTrue(output.contains("GHOST"), "got:\n" + output);
	}

	@Test
	void deleteRejectsAMissingId() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageGroupDelete cmd = CommandTestSupport.create(CommandManageGroupDelete.class, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		Assertions.assertDoesNotThrow(() -> cmd.execute("DEL GROUP"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_GROUP_01), "got:\n" + console.getOutput());
	}

	@Test
	void deleteRejectsAnUnknownId() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageGroupDelete cmd = CommandTestSupport.create(CommandManageGroupDelete.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DEL GROUP GHOST"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_GROUP_04), "got:\n" + console.getOutput());
	}

	@Test
	void deleteReportsAnAlreadyInactiveGroupWithoutPromptingAndDoesNotThrow() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, com.upandcoding.broadsql.dao.model.DatabaseDefinition.STATUS_ACTIVE));
		vault.softDeleteGroup("TAT");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageGroupDelete cmd = CommandTestSupport.create(CommandManageGroupDelete.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DEL GROUP TAT"));

		Assertions.assertTrue(console.getOutput().contains("is already inactive"), "got:\n" + console.getOutput());
	}
}
