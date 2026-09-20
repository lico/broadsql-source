package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowInactiveConnections;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

class TestCommandShowInactiveConnections {

	private CommandShowInactiveConnections newCommand(DatabaseDefinitionsVault vault, CapturingShellConsole console) {
		CommandShowInactiveConnections cmd = CommandTestSupport.create(CommandShowInactiveConnections.class, console);
		cmd.setDatabaseConnectionsVault(vault);
		return cmd;
	}

	@Test
	void listsOnlyInactiveConnections() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		DatabaseDefinition active = new DatabaseDefinition("MYDB01", "H2", "org.h2.Driver", "jdbc:h2:mem:mydb01", "sa", "sa", "MyDB");
		active.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(active);
		DatabaseDefinition toDeactivate = new DatabaseDefinition("MYDB02", "H2", "org.h2.Driver", "jdbc:h2:mem:mydb02", "sa", "sa", "MyOtherDB");
		toDeactivate.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(toDeactivate);
		vault.load();
		vault.softDeleteDatabaseDefinition("MYDB02");

		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowInactiveConnections cmd = newCommand(vault, console);

		cmd.execute("SHOW INACTIVE CONNECTIONS");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("MYDB02"), "expected the inactive connection, got:\n" + output);
		Assertions.assertFalse(output.contains("MYDB01"), "did not expect the active connection, got:\n" + output);
		Assertions.assertTrue(output.contains("1 inactive database connection(s) found"), "expected a count of 1, got:\n" + output);
	}

	@Test
	void reportsNoneFoundWhenEveryConnectionIsActive() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		DatabaseDefinition active = new DatabaseDefinition("MYDB01", "H2", "org.h2.Driver", "jdbc:h2:mem:mydb01", "sa", "sa", "MyDB");
		active.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(active);
		vault.load();

		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowInactiveConnections cmd = newCommand(vault, console);

		cmd.execute("SHOW INACTIVE CONNECTIONS");

		Assertions.assertTrue(console.getOutput().contains("0 inactive database connection(s) found"), "got:\n" + console.getOutput());
	}
}
