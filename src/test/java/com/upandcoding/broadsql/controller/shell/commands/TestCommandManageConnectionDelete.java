package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageConnectionDelete;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;

/**
 * {@code DEL CONNECTION} asks for a {@code [y/n/c]} confirmation past its guard clauses via
 * {@code console.inputField}, which throws {@code NullPointerException} in this environment (no
 * console - see the note on {@link TestCommandManageConnectionAdd}). Only the two guard clauses that
 * run before any prompt are covered here.
 */
class TestCommandManageConnectionDelete {

	@Test
	void rejectsAMissingId() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageConnectionDelete cmd = CommandTestSupport.create(CommandManageConnectionDelete.class, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		Assertions.assertDoesNotThrow(() -> cmd.execute("DEL CONNECTION"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01),
				"expected the missing-id error, got:\n" + console.getOutput());
	}

	@Test
	void reportsAnErrorForAnIdThatDoesNotExist() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageConnectionDelete cmd = CommandTestSupport.create(CommandManageConnectionDelete.class, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		Assertions.assertDoesNotThrow(() -> cmd.execute("DEL CONNECTION DOESNOTEXIST"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01),
				"expected the unknown-connection error, got:\n" + console.getOutput());
	}
}
