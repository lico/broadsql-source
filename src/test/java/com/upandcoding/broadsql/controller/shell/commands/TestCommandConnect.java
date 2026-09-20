package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandConnect;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Only the guard clauses and the failed-test-connection path are covered here. The success path
 * closes the current connection, calls {@code Session.openDatabase()} (whose {@code currentDatabase}
 * field has no public setter, package-private in {@code com.upandcoding.broadsql.controller.shell}), and
 * runs {@code SHOW DBINFO}/{@code SHOW AUTOCOMMIT} through a full {@code CommandInterpreter} - out of
 * scope for this pass, see {@code docs/TESTS_STRATEGY.md}.
 */
class TestCommandConnect {

	private DatabaseConnection db;
	private CapturingShellConsole console;
	private DatabaseDefinitionsVault vault;

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
	void rejectsAMissingConnectionId() {
		CommandConnect cmd = CommandTestSupport.create(CommandConnect.class, db, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		Assertions.assertDoesNotThrow(() -> cmd.execute("CONNECT"));

		Assertions.assertTrue(console.getOutput().contains("You must specify a connection ID"),
				"expected the missing-id error, got:\n" + console.getOutput());
	}

	@Test
	void refusesAConnectionThatIsNotDefined() throws BroadSQLException {
		vault = TestDatabaseConnections.newVault();
		CommandConnect cmd = CommandTestSupport.create(CommandConnect.class, db, console);
		cmd.setDatabaseConnectionsVault(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("CONNECT DOESNOTEXIST"));

		Assertions.assertEquals("Connection 'DOESNOTEXIST' not defined", ex.getLocalizedMessage());
	}

	/**
	 * {@code testConnectionToPlatform()} always either throws on a real connection failure or returns
	 * a non-empty result on success (see {@code DatabaseConnection.java}) - the "empty result, no
	 * exception" shape that {@code CommandConnect}'s own "Connection FAILED" branch guards against is
	 * not one this method can currently produce, confirmed empirically here rather than assumed: an
	 * unreachable target throws, it does not print "Connection FAILED".
	 */
	@Test
	void propagatesTheConnectionErrorWhenTheTargetIsUnreachable() throws BroadSQLException {
		DatabaseDefinition unreachable = new DatabaseDefinition("UNREACHABLE");
		unreachable.setDbDriver("org.h2.Driver");
		unreachable.setDbType("H2");
		unreachable.setDbName("UNREACHABLE");
		unreachable.setUrl("jdbc:h2:C:\\definitely\\does\\not\\exist\\nested\\folder\\db;IFEXISTS=TRUE");
		vault = TestDatabaseConnections.newVault(unreachable);
		TestDatabaseConnections.attachVault(db, vault);
		CommandConnect cmd = CommandTestSupport.create(CommandConnect.class, db, console);
		cmd.setDatabaseConnectionsVault(vault);

		Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("CONNECT UNREACHABLE"));
	}

	/**
	 * A connection whose TYPE's driver jar was never copied into drivers/lib must fail with an
	 * actionable message naming the missing class and where to put it, not a raw ClassNotFoundException -
	 * see DatabaseConnection#missingDriverMessage, docs/TECHNICAL_CHANGE.md, 10/09/2026.
	 */
	@Test
	void reportsAClearErrorWhenTheDriverClassIsNotOnTheClasspath() throws BroadSQLException {
		DatabaseDefinition noDriver = new DatabaseDefinition("NODRIVER");
		noDriver.setDbDriver("com.example.NoSuchDriver");
		noDriver.setDbType("FakeType");
		noDriver.setDbName("NODRIVER");
		noDriver.setUrl("jdbc:fake://localhost/db");
		vault = TestDatabaseConnections.newVault(noDriver);
		TestDatabaseConnections.attachVault(db, vault);
		CommandConnect cmd = CommandTestSupport.create(CommandConnect.class, db, console);
		cmd.setDatabaseConnectionsVault(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("CONNECT NODRIVER"));

		Assertions.assertTrue(ex.getLocalizedMessage().contains("com.example.NoSuchDriver"),
				"expected the missing driver class name in the error, got:\n" + ex.getLocalizedMessage());
		Assertions.assertTrue(ex.getLocalizedMessage().contains("drivers/"),
				"expected guidance pointing at the drivers/ folder, got:\n" + ex.getLocalizedMessage());
	}
}
