package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetMasterPassword;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;

/**
 * {@code SET MASTER PASSWORD} is an interactive prompt end to end ({@code console.readPassword()},
 * which reliably returns {@code null} here since {@code System.console()} is {@code null} in this
 * environment - see {@code CLAUDE.md}, "Environment quirks"). With no CDF file configured on the
 * vault, re-authenticating with the (null) entered password cannot succeed - this test only confirms
 * that failure aborts cleanly rather than corrupting anything, not the full interactive flow (which
 * needs a real, AES-encrypted CDF fixture - out of scope for this pass, see
 * {@code docs/TESTS_STRATEGY.md}).
 */
class TestCommandSetMasterPassword {

	@Test
	void abortsWhenTheVaultCannotBeReached() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandSetMasterPassword cmd = CommandTestSupport.create(CommandSetMasterPassword.class, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("SET MASTER PASSWORD"));
	}
}
