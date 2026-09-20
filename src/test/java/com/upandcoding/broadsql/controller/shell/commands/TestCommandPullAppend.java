package com.upandcoding.broadsql.controller.shell.commands;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;

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
 * Covers {@code CommandPull ... MODE APPEND KEY(<column>) [FORCE]} end to end - through the real
 * {@code PullCommandParser} -> {@code PullSourceShapeValidator} -> {@code PullToH2Exporter} pipeline
 * against real, in-memory H2 databases, the same harness {@link TestCommandPull} uses for
 * {@code MODE OVERWRITE}. Closes a pre-existing gap: {@code MODE APPEND} previously had no automated
 * test coverage at all (see {@link TestCommandPull}'s own class Javadoc, "MODE APPEND is separate
 * follow-up work" - true when written, since the only verification it ever had was a throwaway,
 * uncommitted program, per docs/TECHNICAL_CHANGE.md, 28/08/2026). Also covers the "APPEND KEY(...)
 * opened up to joined queries" change (docs/EXPORT_TO_H2.md, 03/09/2026): a plain (non-join) baseline,
 * then the new join/FORCE/duplicate-column behavior.
 */
class TestCommandPullAppend {

	private DatabaseConnection sourceDb;
	private DatabaseDefinition targetDef;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		sourceDb = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(50))",
				"CREATE TABLE ORDERS (ID INT NOT NULL PRIMARY KEY, CUSTOMER_ID INT NOT NULL, TOTAL DECIMAL(10,2))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice'), (2, 'Bob')",
				"INSERT INTO ORDERS VALUES (100, 1, 50.0)");
		targetDef = TestDatabaseConnections.newInMemoryTarget("TARGETDB");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(sourceDb);
	}

	private CommandPull newCommand() {
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, sourceDb, console);
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("TARGETDB", targetDef);
		vault.setDatabaseConnections(connections);
		cmd.setDatabaseConnectionsVault(vault);
		return cmd;
	}

	private int countRows(String table) throws SQLException {
		try (Connection targetConn = DriverManager.getConnection(targetDef.getUrl());
				Statement stmt = targetConn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
			rs.next();
			return rs.getInt(1);
		}
	}

	// --- baseline (non-join) regression coverage - none existed before this change --------------------

	@Test
	void appendsEveryRowOnTheFirstPull() throws BroadSQLException, SQLException {
		CommandPull cmd = newCommand();
		cmd.execute("PULL CUSTOMER TO TARGETDB.CUSTOMER AS H2 MODE APPEND KEY(ID)");

		Assertions.assertTrue(console.getOutput().contains("2 row(s) pulled into TARGETDB.CUSTOMER (table created)."),
				"got:\n" + console.getOutput());
		Assertions.assertEquals(2, countRows("CUSTOMER"));
	}

	@Test
	void aSecondPullOnlyAppendsRowsNewerThanTheCurrentMaximumKey() throws BroadSQLException, SQLException {
		CommandPull cmd = newCommand();
		cmd.execute("PULL CUSTOMER TO TARGETDB.CUSTOMER AS H2 MODE APPEND KEY(ID)");

		sourceDb.executeUpdateQuery("INSERT INTO CUSTOMER VALUES (3, 'Carol')");
		console = new CapturingShellConsole();
		cmd = newCommand();
		cmd.execute("PULL CUSTOMER TO TARGETDB.CUSTOMER AS H2 MODE APPEND KEY(ID)");

		Assertions.assertTrue(console.getOutput().contains("1 row(s) pulled into TARGETDB.CUSTOMER (appended - delta since the last pull)."),
				"got:\n" + console.getOutput());
		Assertions.assertEquals(3, countRows("CUSTOMER"));
	}

	@Test
	void aThirdPullWithNoNewRowsAppendsNothing() throws BroadSQLException, SQLException {
		CommandPull cmd = newCommand();
		cmd.execute("PULL CUSTOMER TO TARGETDB.CUSTOMER AS H2 MODE APPEND KEY(ID)");
		console = new CapturingShellConsole();
		cmd = newCommand();
		cmd.execute("PULL CUSTOMER TO TARGETDB.CUSTOMER AS H2 MODE APPEND KEY(ID)");

		Assertions.assertTrue(console.getOutput().contains("0 row(s) pulled"), "got:\n" + console.getOutput());
		Assertions.assertEquals(2, countRows("CUSTOMER"));
	}

	@Test
	void rejectsANonOrderableKeyColumnType() {
		CommandPull cmd = newCommand();
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> cmd.execute("PULL CUSTOMER TO TARGETDB.CUSTOMER AS H2 MODE APPEND KEY(NAME)"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("does not support"), "got: " + ex.getLocalizedMessage());
	}

	// --- joined sources (this change) -------------------------------------------------------------------

	@Test
	void pullsAnInnerJoinWithoutNeedingForce() throws BroadSQLException, SQLException {
		CommandPull cmd = newCommand();
		cmd.execute("PULL (SELECT C.ID AS CUSTOMER_ID, O.ID AS ORDER_ID, O.TOTAL FROM CUSTOMER C "
				+ "JOIN ORDERS O ON O.CUSTOMER_ID = C.ID) TO TARGETDB.CUST_ORDERS AS H2 MODE APPEND KEY(ORDER_ID)");

		Assertions.assertTrue(console.getOutput().contains("1 row(s) pulled into TARGETDB.CUST_ORDERS (table created)."),
				"got:\n" + console.getOutput());
		Assertions.assertFalse(console.getOutput().contains("NOTE:"), "an inner join should not print the outer-join caution note");
		Assertions.assertFalse(console.getOutput().contains("WARNING"), "an inner join's confirmed-safe key needs no warning");
		Assertions.assertEquals(1, countRows("CUST_ORDERS"));
	}

	@Test
	void pullsALeftJoinAndPrintsTheOuterJoinCautionNoteRegardless() throws BroadSQLException, SQLException {
		CommandPull cmd = newCommand();
		cmd.execute("PULL (SELECT C.ID AS CUSTOMER_ID, O.ID AS ORDER_ID, O.TOTAL FROM CUSTOMER C "
				+ "LEFT JOIN ORDERS O ON O.CUSTOMER_ID = C.ID) TO TARGETDB.CUST_ORDERS AS H2 MODE APPEND KEY(ORDER_ID)");

		Assertions.assertTrue(console.getOutput().contains("NOTE: this PULL source uses an outer join"),
				"expected the unconditional outer-join caution note, got:\n" + console.getOutput());
		Assertions.assertEquals(2, countRows("CUST_ORDERS"), "both Alice's order and Bob's unmatched (NULL) row should still be pulled");
	}

	@Test
	void rejectsAComputedKeyColumnWithoutForce() {
		CommandPull cmd = newCommand();
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute(
				"PULL (SELECT COALESCE(O.ID, -1) AS ORDER_KEY FROM CUSTOMER C LEFT JOIN ORDERS O ON O.CUSTOMER_ID = C.ID) "
						+ "TO TARGETDB.CUST_ORDERS AS H2 MODE APPEND KEY(ORDER_KEY)"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("FORCE"), "got: " + ex.getLocalizedMessage());
	}

	@Test
	void acceptsAComputedKeyColumnWithForceAndPrintsAWarning() throws BroadSQLException, SQLException {
		CommandPull cmd = newCommand();
		cmd.execute("PULL (SELECT COALESCE(O.ID, -1) AS ORDER_KEY FROM CUSTOMER C LEFT JOIN ORDERS O ON O.CUSTOMER_ID = C.ID) "
				+ "TO TARGETDB.CUST_ORDERS AS H2 MODE APPEND KEY(ORDER_KEY) FORCE");

		Assertions.assertTrue(console.getOutput().contains("WARNING"), "got:\n" + console.getOutput());
		Assertions.assertEquals(2, countRows("CUST_ORDERS"));
	}

	@Test
	void rejectsAJoinWithTwoColumnsSharingTheSameLabel() {
		CommandPull cmd = newCommand();
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute(
				"PULL (SELECT C.ID, O.ID FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID) "
						+ "TO TARGETDB.CUST_ORDERS AS H2 MODE APPEND KEY(ID)"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("two columns both named 'ID'"), "got: " + ex.getLocalizedMessage());
	}

	@Test
	void stillRejectsACommaJoinedSourceAtTheCommandLevel() {
		CommandPull cmd = newCommand();
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute(
				"PULL (SELECT * FROM CUSTOMER, ORDERS) TO TARGETDB.CUST_ORDERS AS H2 MODE APPEND KEY(ID)"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("comma-joined"), "got: " + ex.getLocalizedMessage());
	}
}
