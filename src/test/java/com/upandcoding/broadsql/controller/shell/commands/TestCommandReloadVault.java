package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandReloadVault;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandReloadVault {

	@Test
	void reloadsTheConnectionsVaultAndConfirms() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(TestDatabaseConnections.newInMemoryTarget("TARGET"));
		CommandReloadVault cmd = CommandTestSupport.create(CommandReloadVault.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("RELOAD VAULT");

		Assertions.assertTrue(vault.contains("TARGET"), "expected the vault to still contain TARGET after reload");
		Assertions.assertTrue(console.getOutput().contains("connections refreshed from CDF file"),
				"expected the reload confirmation, got:\n" + console.getOutput());
	}
}
