package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandFixInstanceEnvironmentSwap;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/**
 * {@code FIX INSTANCE ENVIRONMENT SWAP} has been disabled (see docs/TECHNICAL_CHANGE.md, 10/09/2026):
 * it must refuse to run and stay hidden from {@code HELP}, without actually touching the CDF. The
 * underlying vault mechanics ({@code backupCdfFile}/{@code swapInstanceAndEnvironment}) are still
 * covered on their own in {@code TestDatabaseDefinitionsVaultInstanceEnvironmentSwap} - they are kept,
 * just no longer reachable through this command.
 */
class TestCommandFixInstanceEnvironmentSwap {

	@Test
	void refusesToRunAndLeavesTheCdfUntouched() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault("DEV");
		vault.saveEnvironment(new EnvironmentDefinition("DESK", "Desk", false, null, DatabaseDefinition.STATUS_ACTIVE));
		DatabaseDefinition connection = new DatabaseDefinition("DESKCONN", "H2", "org.h2.Driver", "jdbc:h2:mem:deskconn", "sa", "sa", "Desk");
		connection.setDatabaseGroup("DEV");
		connection.setEnvironment("DESK");
		vault.saveDatabaseDefinition(connection);

		CapturingShellConsole console = new CapturingShellConsole();
		CommandFixInstanceEnvironmentSwap cmd = CommandTestSupport.create(CommandFixInstanceEnvironmentSwap.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("FIX INSTANCE ENVIRONMENT SWAP"),
				"a disabled command must refuse to run rather than perform the swap");
		Assertions.assertTrue(ex.getMessage().contains("disabled"), "expected a 'disabled' message, got: " + ex.getMessage());

		vault.load(); // saveDatabaseDefinition() writes straight to the CDF but does not refresh the in-memory cache itself
		DatabaseDefinition reloaded = vault.getDatabaseConnection("DESKCONN");
		Assertions.assertEquals("DEV", reloaded.getDatabaseGroup(), "the CDF must be untouched by the disabled command");
		Assertions.assertEquals("DESK", reloaded.getEnvironment(), "the CDF must be untouched by the disabled command");
	}

	@Test
	void isHiddenFromHelp() {
		CommandFixInstanceEnvironmentSwap cmd = new CommandFixInstanceEnvironmentSwap();
		Assertions.assertTrue(cmd.isHidden(), "must stay hidden from HELP and the generated command reference");
	}
}
