package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowConnection;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

class TestCommandShowConnection {

	@Test
	void showsTheDetailsOfAGivenConnection() throws BroadSQLException {
		DatabaseDefinition def = new DatabaseDefinition("WORLD");
		def.setDbDriver("org.h2.Driver");
		def.setDbType("H2");
		def.setDbName("World database");
		def.setUrl("jdbc:h2:mem:world");
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(def);
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowConnection cmd = CommandTestSupport.create(CommandShowConnection.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SHOW CONNECTION WORLD");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("CONNECTION: WORLD"), "expected the connection id, got:\n" + output);
		Assertions.assertTrue(output.contains("World database"), "expected the connection name, got:\n" + output);
	}

	@Test
	void showsTheCurrentConnectionWithNoArgument() throws BroadSQLException {
		DatabaseDefinition def = new DatabaseDefinition("WORLD");
		def.setDbDriver("org.h2.Driver");
		def.setDbType("H2");
		def.setDbName("World database");
		def.setUrl("jdbc:h2:mem:world");
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(def);
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowConnection cmd = CommandTestSupport.create(CommandShowConnection.class, console);
		cmd.setDatabaseConnectionsVault(vault);
		cmd.setPlatform("WORLD");

		cmd.execute("SHOW CONNECTION");

		Assertions.assertTrue(console.getOutput().contains("CONNECTION: WORLD"),
				"expected the current connection id, got:\n" + console.getOutput());
	}

	@Test
	void warnsForAConnectionThatDoesNotExist() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault();
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowConnection cmd = CommandTestSupport.create(CommandShowConnection.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SHOW CONNECTION DOESNOTEXIST");

		Assertions.assertTrue(console.getOutput().contains("Connection DOESNOTEXIST does not exist"),
				"expected the unknown-connection warning, got:\n" + console.getOutput());
	}
}
