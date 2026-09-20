package com.upandcoding.broadsql.controller.shell.commands;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowLoginScript;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.UserScriptLine;

class TestCommandShowLoginScript {

	private CommandShowLoginScript newCommand(DatabaseDefinitionsVault vault, CapturingShellConsole console) {
		CommandShowLoginScript cmd = CommandTestSupport.create(CommandShowLoginScript.class, console);
		cmd.setDatabaseConnectionsVault(vault);
		return cmd;
	}

	@Test
	void rejectsAMissingId() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowLoginScript cmd = newCommand(TestDatabaseConnections.newFileBackedVault(), console);

		cmd.execute("SHOW LOGIN SCRIPT");

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01), "got:\n" + console.getOutput());
	}

	@Test
	void listsLinesInOrderWithLineNumbers() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		DatabaseDefinition connection = new DatabaseDefinition("MYDB01", "H2", "org.h2.Driver", "jdbc:h2:mem:mydb01", "sa", "sa", "MyDB");
		connection.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(connection);
		vault.load();
		vault.saveUserScriptLines("MYDB01", List.of(new UserScriptLine("MYDB01", "SET SCHEMA APP", 1, UserScriptLine.STATUS_ACTIVE, "switch schema"),
				new UserScriptLine("MYDB01", "ALTER SESSION SET X=1", 2, UserScriptLine.STATUS_INACTIVE, null)));

		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowLoginScript cmd = newCommand(vault, console);

		cmd.execute("SHOW LOGIN SCRIPT MYDB01");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("SET SCHEMA APP"), "got:\n" + output);
		Assertions.assertTrue(output.contains("ALTER SESSION SET X=1"), "got:\n" + output);
		Assertions.assertTrue(output.contains("2 login script line(s) found"), "got:\n" + output);
	}

	@Test
	void rejectsAnUnknownConnectionId() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowLoginScript cmd = newCommand(TestDatabaseConnections.newFileBackedVault(), console);

		cmd.execute("SHOW LOGIN SCRIPT GHOST");

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01), "got:\n" + console.getOutput());
	}
}
