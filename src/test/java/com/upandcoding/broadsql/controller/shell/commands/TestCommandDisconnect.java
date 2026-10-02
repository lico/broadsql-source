package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandDisconnect;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Only the guard clause and the failed-test-connection path are covered here. The success path
 * reconnects through {@code CONNECT $CDF} via a full {@code CommandInterpreter} - out of scope for
 * this pass, see {@code docs/TESTS_STRATEGY.md} (same reasoning as {@link TestCommandConnect}).
 */
class TestCommandDisconnect {

	private DatabaseConnection db;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory();
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void refusesWhenTheCdfConnectionIsNotDefined() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault();
		CommandDisconnect cmd = CommandTestSupport.create(CommandDisconnect.class, db, console);
		cmd.setDatabaseConnectionsVault(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("DISCONNECT"));

		Assertions.assertEquals("Connection '$CDF' not defined", ex.getLocalizedMessage());
	}

	/**
	 * See the equivalent note on {@code TestCommandConnect.propagatesTheConnectionErrorWhenTheTargetIsUnreachable()}:
	 * an unreachable {@code $CDF} throws rather than printing "Connection ... FAILED".
	 */
	@Test
	void propagatesTheConnectionErrorWhenTheCdfConnectionIsUnreachable() throws BroadSQLException {
		DatabaseDefinition cdf = new DatabaseDefinition("$CDF");
		cdf.setDbDriver("org.h2.Driver");
		cdf.setDbType("H2");
		cdf.setDbName("$CDF");
		cdf.setUrl("jdbc:h2:C:\\definitely\\does\\not\\exist\\nested\\folder\\cdf;IFEXISTS=TRUE");
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(cdf);
		TestDatabaseConnections.attachVault(db, vault);
		CommandDisconnect cmd = CommandTestSupport.create(CommandDisconnect.class, db, console);
		cmd.setDatabaseConnectionsVault(vault);

		Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("DISCONNECT"));
	}
}
