package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageConnectionEdit;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;

/**
 * {@code EDIT CONNECTION} is an interactive wizard past its guard clause - see the equivalent note on
 * {@link TestCommandManageConnectionAdd}. Only the guard clause is covered here.
 */
class TestCommandManageConnectionEdit {

	@Test
	void rejectsAMissingId() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageConnectionEdit cmd = CommandTestSupport.create(CommandManageConnectionEdit.class, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		Assertions.assertDoesNotThrow(() -> cmd.execute("EDIT CONNECTION"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01),
				"expected the missing-id error, got:\n" + console.getOutput());
	}

	@Test
	void refusesAnIdThatDoesNotExist() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageConnectionEdit cmd = CommandTestSupport.create(CommandManageConnectionEdit.class, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("EDIT CONNECTION DOESNOTEXIST"));

		Assertions.assertEquals(BroadSQLErrorMessages.ERR_CONN_01, ex.getLocalizedMessage());
	}
}
