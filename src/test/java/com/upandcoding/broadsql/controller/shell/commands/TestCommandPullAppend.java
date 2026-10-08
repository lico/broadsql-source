package com.upandcoding.broadsql.controller.shell.commands;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

	// --- the delta filter keeps the source condition's meaning -------------------------------------------

	private List<Integer> targetIds(String table) throws SQLException {
		List<Integer> ids = new ArrayList<>();
		try (Connection targetConn = DriverManager.getConnection(targetDef.getUrl());
				Statement stmt = targetConn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT ID FROM \"" + table + "\" ORDER BY ID")) {
			while (rs.next()) {
				ids.add(rs.getInt(1));
			}
		}
		return ids;
	}

	private void pull(String sql) throws BroadSQLException {
		console = new CapturingShellConsole();
		newCommand().execute(sql);
	}

	/**
	 * Each source condition: the rows of the first pull, then of every pull after new rows arrived. The delta
	 * filter is AND-ed to the whole condition, so a pull never takes again a row already appended, whatever the
	 * condition contains (an {@code OR} used to bind only its last term to the filter).
	 */
	static Stream<Arguments> sourceConditions() {
		return Stream.of(
				Arguments.of("A = 1", List.of(1, 4), List.of(1, 4, 5, 7)),
				Arguments.of("A = 1 AND B = 2", List.of(4), List.of(4, 5)),
				Arguments.of("A = 1 OR B = 2", List.of(1, 2, 4), List.of(1, 2, 4, 5, 6, 7)),
				Arguments.of("(A = 1 OR B = 2) AND C = 3", List.of(1, 2), List.of(1, 2, 5, 6)));
	}

	@ParameterizedTest(name = "WHERE {0}")
	@MethodSource("sourceConditions")
	void anAppendPullReturnsExactlyTheNewMatchingRows(String condition, List<Integer> firstPull, List<Integer> afterNewRows)
			throws BroadSQLException, SQLException {
		sourceDb.executeUpdateQuery("CREATE TABLE SRC (ID INT PRIMARY KEY, A INT, B INT, C INT)");
		sourceDb.executeUpdateQuery("INSERT INTO SRC VALUES (1, 1, 0, 3), (2, 0, 2, 3), (3, 0, 0, 3), (4, 1, 2, 0)");
		String command = "PULL (SELECT ID FROM SRC WHERE " + condition + ") TO TARGETDB.T AS H2 MODE APPEND KEY(ID)";

		pull(command);
		Assertions.assertEquals(firstPull, targetIds("T"), console.getOutput());

		// the watermark is already the highest matching key: nothing is new, nothing is appended again
		pull(command);
		Assertions.assertTrue(console.getOutput().contains("0 row(s) pulled"), console.getOutput());
		Assertions.assertEquals(firstPull, targetIds("T"), "rows already appended were pulled again:\n" + console.getOutput());

		sourceDb.executeUpdateQuery("INSERT INTO SRC VALUES (5, 1, 2, 3), (6, 0, 2, 3), (7, 1, 0, 0), (8, 0, 0, 3)");
		pull(command);
		Assertions.assertEquals(afterNewRows, targetIds("T"), console.getOutput());
	}

	@Test
	void anOrConditionWithTheWatermarkAtTheMaximumKeyAppendsZeroRows() throws BroadSQLException, SQLException {
		sourceDb.executeUpdateQuery("CREATE TABLE SRC (ID INT PRIMARY KEY, A INT, B INT)");
		sourceDb.executeUpdateQuery("INSERT INTO SRC VALUES (1, 1, 0), (2, 0, 2), (3, 1, 2), (4, 0, 2)");
		String command = "PULL (SELECT ID FROM SRC WHERE A = 1 OR B = 2) TO TARGETDB.T AS H2 MODE APPEND KEY(ID)";
		pull(command);
		Assertions.assertEquals(List.of(1, 2, 3, 4), targetIds("T"));

		pull(command);

		Assertions.assertTrue(console.getOutput().contains("0 row(s) pulled"), console.getOutput());
		Assertions.assertEquals(List.of(1, 2, 3, 4), targetIds("T"), "no duplicate may be appended");
	}

	@Test
	void aJoinKeyNamedByItsSelectListAliasFiltersTheSecondPullOnTheAliasedColumn() throws BroadSQLException, SQLException {
		String command = "PULL (SELECT C.ID AS CUSTOMER_ID, O.ID AS ORDER_ID, O.TOTAL FROM CUSTOMER C "
				+ "JOIN ORDERS O ON O.CUSTOMER_ID = C.ID WHERE C.NAME = 'Alice' OR C.NAME = 'Bob') TO TARGETDB.CUST_ORDERS AS H2 MODE APPEND KEY(ORDER_ID)";
		pull(command);
		sourceDb.executeUpdateQuery("INSERT INTO ORDERS VALUES (101, 2, 20.0)");

		// WHERE cannot see the alias ORDER_ID: the delta filter uses O.ID, the column it names
		pull(command);

		Assertions.assertFalse(console.wasErrorReported(), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("1 row(s) pulled into TARGETDB.CUST_ORDERS (appended"), console.getOutput());
		Assertions.assertEquals(2, countRows("CUST_ORDERS"));
	}
}
