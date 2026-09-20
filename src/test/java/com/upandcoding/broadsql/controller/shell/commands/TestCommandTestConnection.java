package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandTestConnection;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandTestConnection {

	private DatabaseConnection db;
	private CapturingShellConsole console;
	private DatabaseDefinitionsVault vault;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory();
		console = new CapturingShellConsole();
		vault = TestDatabaseConnections.newVault(TestDatabaseConnections.newInMemoryTarget("TARGET"));
		TestDatabaseConnections.attachVault(db, vault);
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void reportsSuccessForAReachableConnection() throws BroadSQLException {
		CommandTestConnection cmd = CommandTestSupport.create(CommandTestConnection.class, db, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("PING TARGET");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Connection to database: SUCCESS"),
				"expected a success message, got:\n" + output);
	}

	@Test
	void reportsAnErrorForAConnectionNotInTheCdf() throws BroadSQLException {
		CommandTestConnection cmd = CommandTestSupport.create(CommandTestConnection.class, db, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("PING DOESNOTEXIST");

		Assertions.assertTrue(console.getOutput().contains("Database DOESNOTEXIST does not exist in the CDF file"),
				"expected the unknown-connection error, got:\n" + console.getOutput());
	}

	@Test
	void rejectsAMissingDatabaseId() throws BroadSQLException {
		CommandTestConnection cmd = CommandTestSupport.create(CommandTestConnection.class, db, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("PING");

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01),
				"expected the missing-id error, got:\n" + console.getOutput());
	}
}
