package com.upandcoding.broadsql.controller.shell.commands;

import java.util.HashMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandDuplicateConnection;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * {@code DUPLICATE CONNECTION} is an interactive wizard past its guard clauses (same
 * {@code console.inputField}/{@code readLine} limitation as {@code ADD}/{@code EDIT CONNECTION}, see
 * {@code TestCommandManageConnectionAdd}). Only the guard clauses - missing arguments, an unknown or
 * inactive source, a target ID already in active use - are covered here.
 */
class TestCommandDuplicateConnection {

	private CommandDuplicateConnection newCommand(DatabaseDefinitionsVault vault, CapturingShellConsole console) {
		CommandDuplicateConnection cmd = CommandTestSupport.create(CommandDuplicateConnection.class, console);
		cmd.setDatabaseConnectionsVault(vault);
		return cmd;
	}

	@Test
	void rejectsMissingArguments() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandDuplicateConnection cmd = newCommand(new DatabaseDefinitionsVault(), console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DUPLICATE CONNECTION"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01), "got:\n" + console.getOutput());
	}

	@Test
	void rejectsANewIdWithNoSourceId() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandDuplicateConnection cmd = newCommand(new DatabaseDefinitionsVault(), console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DUPLICATE CONNECTION MYDB01"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01), "got:\n" + console.getOutput());
	}

	@Test
	void rejectsAnUnknownSourceId() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandDuplicateConnection cmd = newCommand(new DatabaseDefinitionsVault(), console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DUPLICATE CONNECTION GHOST MYDB02"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01), "got:\n" + console.getOutput());
	}

	@Test
	void rejectsAnInactiveSourceIdWithADistinctMessage() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		DatabaseDefinition source = new DatabaseDefinition("MYDB01", "H2", "org.h2.Driver", "jdbc:h2:mem:mydb01", "sa", "sa", "MyDB");
		source.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(source);
		vault.load();
		vault.softDeleteDatabaseDefinition("MYDB01");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandDuplicateConnection cmd = newCommand(vault, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DUPLICATE CONNECTION MYDB01 MYDB02"));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("is inactive"), "expected the inactive-source message, got:\n" + output);
	}

	@Test
	void refusesANewIdThatAlreadyExistsActively() {
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("MYDB01", new DatabaseDefinition("MYDB01"));
		connections.put("MYDB02", new DatabaseDefinition("MYDB02"));
		vault.setDatabaseConnections(connections);
		CommandDuplicateConnection cmd = newCommand(vault, console);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("DUPLICATE CONNECTION MYDB01 MYDB02"));

		Assertions.assertEquals(BroadSQLErrorMessages.ERR_CONN_02, ex.getLocalizedMessage());
	}
}
