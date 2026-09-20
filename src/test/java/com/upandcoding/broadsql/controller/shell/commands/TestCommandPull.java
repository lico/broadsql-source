package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Covers {@code CommandPull}'s "reuse an already-registered H2 connection" path (the current top
 * product priority, see {@code docs/EXPORT_TO_H2.md}) using the lightweight in-memory
 * {@link DatabaseDefinitionsVault}, plus the "create a brand-new H2 connection" path - a new connection
 * is registered as <b>standalone</b> (no Database Group) in the built-in {@code LOCAL} Environment, per
 * the standalone-connections model (docs/CONNECTION_MODEL.md) - using
 * {@link TestDatabaseConnections#newFileBackedVault} - the only one of the two vault flavors
 * {@code DatabaseDefinitionsVault.saveDatabaseDefinition}/{@code saveGroup} actually work against,
 * since both only have a file-backed implementation. {@code MODE APPEND} is separate follow-up work,
 * not this sprint's scope - see {@code docs/TESTS_STRATEGY.md}, "Coverage plan".
 */
class TestCommandPull {

	private DatabaseConnection sourceDb;
	private DatabaseDefinition targetDef;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		sourceDb = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice'), (2, 'Bob')");
		targetDef = TestDatabaseConnections.newInMemoryTarget("TARGETDB");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(sourceDb);
	}

	@Test
	void pullsATableIntoAnExistingH2Connection() throws BroadSQLException, SQLException {
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, sourceDb, console);
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("TARGETDB", targetDef);
		vault.setDatabaseConnections(connections);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("PULL CUSTOMER TO TARGETDB.CUSTOMER AS H2");

		Assertions.assertTrue(
				console.getOutput().contains("2 row(s) pulled into TARGETDB.CUSTOMER (table dropped and recreated)."),
				"expected the pull result summary, got:\n" + console.getOutput());

		try (Connection targetConn = DriverManager.getConnection(targetDef.getUrl());
				Statement stmt = targetConn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM \"CUSTOMER\"")) {
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals(2, rs.getInt(1));
		}
	}

	@Test
	void refusesToReuseAConnectionThatIsNotH2() throws BroadSQLException {
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, sourceDb, console);
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		DatabaseDefinition nonH2 = new DatabaseDefinition("TARGETDB");
		nonH2.setDbType("PostgreSQL");
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("TARGETDB", nonH2);
		vault.setDatabaseConnections(connections);
		cmd.setDatabaseConnectionsVault(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> cmd.execute("PULL CUSTOMER TO TARGETDB.CUSTOMER AS H2"));

		Assertions.assertTrue(ex.getLocalizedMessage().contains("not H2"),
				"expected a 'not H2' refusal, got: " + ex.getLocalizedMessage());
	}

	/**
	 * A freshly created {@code PULL ... AS H2} connection is registered as a <b>standalone</b> connection
	 * (no Database Group) in the built-in {@code LOCAL} Environment - never derived from the
	 * {@code DefaultEnvironment} INI setting, never attributed to any Database Group. Replaces the old
	 * "auto-create/reuse a Database Group" regression test (docs/TODO.md item 5, now resolved) - see
	 * docs/TECHNICAL_CHANGE.md, standalone-connections model.
	 */
	@Test
	void createsANewH2ConnectionAsStandaloneInLocalEnvironment() throws BroadSQLException {
		String name = uniqueConnectionName();
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, sourceDb, console);
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		cmd.setDatabaseConnectionsVault(vault);

		try {
			cmd.execute("PULL CUSTOMER TO " + name + ".CUSTOMER AS H2");

			DatabaseDefinition created = vault.getDatabaseConnection(name);
			Assertions.assertNotNull(created, "expected a new connection '" + name + "' to be registered");
			Assertions.assertEquals("LOCAL", created.getEnvironment(),
					"expected the built-in LOCAL environment, since no case variant pre-existed");
			Assertions.assertNull(created.getDatabaseGroup(),
					"expected a standalone connection - no Database Group");
			Assertions.assertTrue(
					console.getOutput().contains("environment 'LOCAL' (standalone, no Database Group"),
					"expected the creation message to report standalone/LOCAL, got:\n" + console.getOutput());
		} finally {
			deleteH2File(name);
			deleteH2File(vault.getFileName());
		}
	}

	/**
	 * Same as above, but the CDF already has several Database Groups registered - {@code CommandPull}
	 * must never attribute the new connection to any of them, regardless of alphabetical ordering (the
	 * old {@code resolveGroup()} "pick the alphabetically-first group" behavior no longer exists at all).
	 */
	@Test
	void createsANewH2ConnectionIgnoringPreExistingGroups() throws BroadSQLException {
		String name = uniqueConnectionName();
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, sourceDb, console);
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault("AAA", "CP", "TAT");
		cmd.setDatabaseConnectionsVault(vault);

		try {
			cmd.execute("PULL CUSTOMER TO " + name + ".CUSTOMER AS H2");

			DatabaseDefinition created = vault.getDatabaseConnection(name);
			Assertions.assertNotNull(created, "expected a new connection '" + name + "' to be registered");
			Assertions.assertNull(created.getDatabaseGroup(),
					"expected a standalone connection even though Database Groups already existed");
			Assertions.assertEquals(3, vault.getGroups().size(),
					"no new group should have been created or reused");
		} finally {
			deleteH2File(name);
			deleteH2File(vault.getFileName());
		}
	}

	/**
	 * Successive {@code PULL ... AS H2} runs, each creating a brand-new connection, must all succeed and
	 * all be standalone/LOCAL - the exact scenario the {@code (Database Group, Environment)} uniqueness
	 * rule broke when PULL still auto-assigned every new connection to the same group/environment pair
	 * (see docs/TECHNICAL_CHANGE.md, standalone-connections model).
	 */
	@Test
	void successivePullsEachCreateAStandaloneLocalConnection() throws BroadSQLException {
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, sourceDb, console);
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		cmd.setDatabaseConnectionsVault(vault);

		String name1 = uniqueConnectionName();
		String name2 = uniqueConnectionName();
		String name3 = uniqueConnectionName();
		try {
			cmd.execute("PULL CUSTOMER TO " + name1 + ".CUSTOMER AS H2");
			cmd.execute("PULL CUSTOMER TO " + name2 + ".CUSTOMER AS H2");
			cmd.execute("PULL CUSTOMER TO " + name3 + ".CUSTOMER AS H2");

			for (String name : new String[] { name1, name2, name3 }) {
				DatabaseDefinition created = vault.getDatabaseConnection(name);
				Assertions.assertNotNull(created, "expected connection '" + name + "' to be registered");
				Assertions.assertNull(created.getDatabaseGroup(), "expected '" + name + "' to be standalone");
				Assertions.assertEquals("LOCAL", created.getEnvironment(), "expected '" + name + "' to be in LOCAL");
			}
		} finally {
			deleteH2File(name1);
			deleteH2File(name2);
			deleteH2File(name3);
			deleteH2File(vault.getFileName());
		}
	}

	private static String uniqueConnectionName() {
		return "PT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
	}

	/**
	 * Best-effort cleanup of the {@code .mv.db}/{@code .trace.db} files H2 creates for
	 * {@code basePathOrName} - a bare connection name (resolved against the default export folder, like
	 * {@code CommandPull} itself does) or an already-absolute path (as returned by
	 * {@link DatabaseDefinitionsVault#getFileName}).
	 */
	private static void deleteH2File(String basePathOrName) {
		String basePath = new File(basePathOrName).isAbsolute()
				? basePathOrName
				: TestDatabaseConnections.defaultConsoleSettings().getExtractFolderName() + basePathOrName;
		new File(basePath + ".mv.db").delete();
		new File(basePath + ".trace.db").delete();
	}
}
