package com.upandcoding.broadsql.controller.shell.commands;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageLoginScriptLineAdd;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageLoginScriptLineDelete;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageLoginScriptLineEdit;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageLoginScriptLineMove;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.UserScriptLine;

/**
 * {@code ADD}/{@code EDIT}/{@code DEL LOGIN SCRIPT LINE} are interactive wizards past their guard
 * clauses (same {@code console.inputField}/{@code readLine} limitation as {@code ADD}/{@code EDIT
 * CONNECTION}, see {@code TestCommandManageConnectionAdd}) - only the guard clauses are covered here.
 * {@code MOVE LOGIN SCRIPT LINE} is not interactive at all, so it is covered fully.
 */
class TestCommandManageLoginScriptLine {

	private DatabaseDefinitionsVault vaultWithConnectionAndLines(UserScriptLine... lines) throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		DatabaseDefinition connection = new DatabaseDefinition("MYDB01", "H2", "org.h2.Driver", "jdbc:h2:mem:mydb01", "sa", "sa", "MyDB");
		connection.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(connection);
		vault.load();
		if (lines.length > 0) {
			vault.saveUserScriptLines("MYDB01", List.of(lines));
		}
		return vault;
	}

	@Test
	void addRejectsAMissingConnectionId() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageLoginScriptLineAdd cmd = CommandTestSupport.create(CommandManageLoginScriptLineAdd.class, console);
		cmd.setDatabaseConnectionsVault(TestDatabaseConnections.newFileBackedVault());

		cmd.execute("ADD LOGIN SCRIPT LINE");

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01), "got:\n" + console.getOutput());
	}

	@Test
	void addRejectsAnUnknownConnectionId() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageLoginScriptLineAdd cmd = CommandTestSupport.create(CommandManageLoginScriptLineAdd.class, console);
		cmd.setDatabaseConnectionsVault(TestDatabaseConnections.newFileBackedVault());

		cmd.execute("ADD LOGIN SCRIPT LINE GHOST");

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01), "got:\n" + console.getOutput());
	}

	@Test
	void editRejectsAMissingLineNumber() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageLoginScriptLineEdit cmd = CommandTestSupport.create(CommandManageLoginScriptLineEdit.class, console);
		cmd.setDatabaseConnectionsVault(vaultWithConnectionAndLines());

		cmd.execute("EDIT LOGIN SCRIPT LINE MYDB01");

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_LOGINSCRIPT_02), "got:\n" + console.getOutput());
	}

	@Test
	void editRejectsALineNumberOutOfRange() throws BroadSQLException {
		DatabaseDefinitionsVault vault = vaultWithConnectionAndLines(new UserScriptLine("MYDB01", "SET SCHEMA APP", 1, UserScriptLine.STATUS_ACTIVE, null));
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageLoginScriptLineEdit cmd = CommandTestSupport.create(CommandManageLoginScriptLineEdit.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("EDIT LOGIN SCRIPT LINE MYDB01 5");

		Assertions.assertTrue(console.getOutput().contains("only 1 line(s) exist"), "got:\n" + console.getOutput());
	}

	@Test
	void deleteRejectsANonNumericLineNumber() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageLoginScriptLineDelete cmd = CommandTestSupport.create(CommandManageLoginScriptLineDelete.class, console);
		cmd.setDatabaseConnectionsVault(vaultWithConnectionAndLines());

		cmd.execute("DEL LOGIN SCRIPT LINE MYDB01 abc");

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_LOGINSCRIPT_02), "got:\n" + console.getOutput());
	}

	@Test
	void moveRejectsAnInvalidDirection() throws BroadSQLException {
		DatabaseDefinitionsVault vault = vaultWithConnectionAndLines(new UserScriptLine("MYDB01", "A", 1, UserScriptLine.STATUS_ACTIVE, null),
				new UserScriptLine("MYDB01", "B", 2, UserScriptLine.STATUS_ACTIVE, null));
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageLoginScriptLineMove cmd = CommandTestSupport.create(CommandManageLoginScriptLineMove.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("MOVE LOGIN SCRIPT LINE MYDB01 1 SIDEWAYS");

		Assertions.assertTrue(console.getOutput().contains("Direction must be UP or DOWN"), "got:\n" + console.getOutput());
	}

	@Test
	void moveRefusesToMoveTheFirstLineUp() throws BroadSQLException {
		DatabaseDefinitionsVault vault = vaultWithConnectionAndLines(new UserScriptLine("MYDB01", "A", 1, UserScriptLine.STATUS_ACTIVE, null),
				new UserScriptLine("MYDB01", "B", 2, UserScriptLine.STATUS_ACTIVE, null));
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageLoginScriptLineMove cmd = CommandTestSupport.create(CommandManageLoginScriptLineMove.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("MOVE LOGIN SCRIPT LINE MYDB01 1 UP");

		Assertions.assertTrue(console.getOutput().contains("is already first"), "got:\n" + console.getOutput());
	}

	@Test
	void moveSwapsTwoLinesAndPersistsTheNewOrder() throws BroadSQLException {
		DatabaseDefinitionsVault vault = vaultWithConnectionAndLines(new UserScriptLine("MYDB01", "A", 1, UserScriptLine.STATUS_ACTIVE, null),
				new UserScriptLine("MYDB01", "B", 2, UserScriptLine.STATUS_ACTIVE, null));
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageLoginScriptLineMove cmd = CommandTestSupport.create(CommandManageLoginScriptLineMove.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("MOVE LOGIN SCRIPT LINE MYDB01 1 DOWN");

		Assertions.assertTrue(console.getOutput().contains("moved down"), "got:\n" + console.getOutput());
		List<UserScriptLine> reloaded = vault.getUserScriptLines(vault.getFileName(), vault.getPassword(), "MYDB01");
		Assertions.assertEquals("B", reloaded.get(0).getSqlCommand());
		Assertions.assertEquals("A", reloaded.get(1).getSqlCommand());
	}
}
