package com.upandcoding.broadsql.controller.shell.commands;

import java.util.HashMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageConnectionAdd;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * {@code ADD CONNECTION} is an interactive wizard past its guard clause ({@code console.inputField}
 * calling {@code ShellConsole.readLine()}, which - unlike {@code readPassword()} - has no
 * {@code System.console() == null} guard and throws {@code NullPointerException} immediately in this
 * environment, see {@code CLAUDE.md}, "Environment quirks"). Only the guard clause is covered here;
 * the wizard itself needs a real console, out of scope for this pass (see
 * {@code docs/TESTS_STRATEGY.md}).
 */
class TestCommandManageConnectionAdd {

	@Test
	void rejectsAMissingId() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageConnectionAdd cmd = CommandTestSupport.create(CommandManageConnectionAdd.class, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		Assertions.assertDoesNotThrow(() -> cmd.execute("ADD CONNECTION"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01),
				"expected the missing-id error, got:\n" + console.getOutput());
	}

	@Test
	void refusesAnIdThatAlreadyExists() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageConnectionAdd cmd = CommandTestSupport.create(CommandManageConnectionAdd.class, console);
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("WORLD", new DatabaseDefinition("WORLD"));
		vault.setDatabaseConnections(connections);
		cmd.setDatabaseConnectionsVault(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("ADD CONNECTION WORLD"));

		Assertions.assertEquals(BroadSQLErrorMessages.ERR_CONN_02, ex.getLocalizedMessage());
	}
}
