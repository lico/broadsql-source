package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageConnectionDeleteHard;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;

/**
 * {@code HARDDEL} - unlike {@code DEL CONNECTION} - does not check that the id exists before asking
 * for confirmation, so a valid non-blank id always reaches {@code console.inputField}, which throws
 * {@code NullPointerException} in this environment (no console - see the note on
 * {@link TestCommandManageConnectionAdd}). Only the missing-id guard clause is covered here.
 */
class TestCommandManageConnectionDeleteHard {

	@Test
	void rejectsAMissingId() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandManageConnectionDeleteHard cmd = CommandTestSupport.create(CommandManageConnectionDeleteHard.class, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		Assertions.assertDoesNotThrow(() -> cmd.execute("HARDDEL"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01),
				"expected the missing-id error, got:\n" + console.getOutput());
	}
}
