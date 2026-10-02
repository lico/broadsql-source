package com.upandcoding.broadsql.dao.pull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Covers {@code BROADSQL_PULL_AUDIT}, the provenance table {@code PULL ... AS H2} maintains inside every
 * destination database (see docs/EXPORT_TO_H2.md, "H2 export provenance") - against a real H2 database,
 * not mocked, per this project's "verify empirically" convention. {@link PullToH2Exporter#overwrite}/
 * {@link PullToH2Exporter#append} are exercised directly (same as {@link TestPullToH2Exporter}); the
 * audit table itself is then inspected with plain SQL through a second connection to the same in-memory
 * database ({@code DB_CLOSE_DELAY=-1} keeps it alive after the exporter's own connection closes).
 */
class TestPullAuditTable {

	private Connection source;
	private PullToH2Exporter exporter;

	private Connection openSource() throws Exception {
		Class.forName("org.h2.Driver");
		String name = "auditsrc_" + UUID.randomUUID().toString().replace("-", "");
		Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
		try (Statement st = connection.createStatement()) {
			st.execute("CREATE TABLE ORDERS (ID INT NOT NULL PRIMARY KEY, TOTAL DECIMAL(10,2))");
			st.execute("INSERT INTO ORDERS VALUES (1, 50.00), (2, 75.00)");
		}
		return connection;
	}

	private ResultSet query(Connection connection, String sql) throws SQLException {
		return connection.createStatement().executeQuery(sql);
	}

	@Test
	void firstPullCreatesTheBusinessTableAndTheAuditTableWithOneRow() throws Exception {
		source = openSource();
		exporter = new PullToH2Exporter();
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("AUDIT1");

		try (ResultSet rs = query(source, "SELECT * FROM ORDERS")) {
			int rowsWritten = exporter.overwrite(target, "ORDERS_EXTRACT", rs, "SELECT * FROM ORDERS", "SRC1");
			Assertions.assertEquals(2, rowsWritten);
		}

		try (Connection check = DriverManager.getConnection(target.getUrl())) {
			try (ResultSet rs = query(check, "SELECT * FROM ORDERS_EXTRACT")) {
				int count = 0;
				while (rs.next()) {
					count++;
				}
				Assertions.assertEquals(2, count, "the business table must hold the 2 pulled rows");
			}
			try (ResultSet rs = query(check, "SELECT COUNT(*) FROM BROADSQL_PULL_AUDIT")) {
				rs.next();
				Assertions.assertEquals(1, rs.getInt(1), "expected exactly one audit row after the first PULL");
			}
		}
	}

	@Test
	void secondPullToTheSameTargetAppendsASecondAuditRowWithoutTouchingTheFirst() throws Exception {
		source = openSource();
		exporter = new PullToH2Exporter();
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("AUDIT2");

		try (ResultSet rs = query(source, "SELECT * FROM ORDERS")) {
			exporter.overwrite(target, "ORDERS_EXTRACT", rs, "SELECT * FROM ORDERS", "SRC1");
		}
		Timestamp firstExecutedAt;
		try (Connection check = DriverManager.getConnection(target.getUrl());
				ResultSet rs = query(check, "SELECT EXECUTED_AT FROM BROADSQL_PULL_AUDIT ORDER BY ID")) {
			rs.next();
			firstExecutedAt = rs.getTimestamp(1);
		}

		try (ResultSet rs = query(source, "SELECT * FROM ORDERS")) {
			exporter.overwrite(target, "ORDERS_EXTRACT", rs, "SELECT * FROM ORDERS", "SRC1");
		}

		try (Connection check = DriverManager.getConnection(target.getUrl())) {
			try (ResultSet rs = query(check, "SELECT COUNT(*) FROM BROADSQL_PULL_AUDIT")) {
				rs.next();
				Assertions.assertEquals(2, rs.getInt(1), "two successful PULL executions must produce two audit rows, not one upserted row");
			}
			try (ResultSet rs = query(check, "SELECT EXECUTED_AT FROM BROADSQL_PULL_AUDIT ORDER BY ID")) {
				rs.next();
				Assertions.assertEquals(firstExecutedAt, rs.getTimestamp(1), "the first audit row must remain unchanged (append-only)");
			}
		}
	}

	@Test
	void independentTargetTablesProduceIndependentAuditRows() throws Exception {
		source = openSource();
		exporter = new PullToH2Exporter();
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("AUDIT3");

		try (ResultSet rs = query(source, "SELECT * FROM ORDERS")) {
			exporter.overwrite(target, "EXTRACT_A", rs, "SELECT * FROM ORDERS", "SRC1");
		}
		try (ResultSet rs = query(source, "SELECT * FROM ORDERS WHERE ID = 1")) {
			exporter.overwrite(target, "EXTRACT_B", rs, "SELECT * FROM ORDERS WHERE ID = 1", "SRC1");
		}

		try (Connection check = DriverManager.getConnection(target.getUrl())) {
			try (ResultSet rs = query(check, "SELECT COUNT(*) FROM BROADSQL_PULL_AUDIT WHERE TARGET_TABLE = 'EXTRACT_A'")) {
				rs.next();
				Assertions.assertEquals(1, rs.getInt(1));
			}
			try (ResultSet rs = query(check, "SELECT COUNT(*) FROM BROADSQL_PULL_AUDIT WHERE TARGET_TABLE = 'EXTRACT_B'")) {
				rs.next();
				Assertions.assertEquals(1, rs.getInt(1));
			}
		}
	}

	@Test
	void auditRowRecordsTheExpectedMetadataValues() throws Exception {
		source = openSource();
		exporter = new PullToH2Exporter();
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("AUDIT4");

		Timestamp before = new Timestamp(System.currentTimeMillis() - 2000);
		try (ResultSet rs = query(source, "SELECT * FROM ORDERS")) {
			exporter.overwrite(target, "ORDERS_EXTRACT", rs, "SELECT * FROM ORDERS", "MY_SOURCE_CONN");
		}
		Timestamp after = new Timestamp(System.currentTimeMillis() + 2000);

		try (Connection check = DriverManager.getConnection(target.getUrl());
				ResultSet rs = query(check, "SELECT TARGET_TABLE, QUERY_TEXT, SOURCE_CONNECTION, ROW_COUNT, PULL_MODE, EXECUTED_AT FROM BROADSQL_PULL_AUDIT")) {
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals("ORDERS_EXTRACT", rs.getString("TARGET_TABLE"));
			Assertions.assertEquals("SELECT * FROM ORDERS", rs.getString("QUERY_TEXT"));
			Assertions.assertEquals("MY_SOURCE_CONN", rs.getString("SOURCE_CONNECTION"));
			Assertions.assertEquals(2L, rs.getLong("ROW_COUNT"));
			Assertions.assertEquals("OVERWRITE", rs.getString("PULL_MODE"));
			Timestamp executedAt = rs.getTimestamp("EXECUTED_AT");
			Assertions.assertNotNull(executedAt);
			Assertions.assertTrue(executedAt.after(before) && executedAt.before(after), "expected EXECUTED_AT to be around the actual run time, got: " + executedAt);
		}
	}

	@Test
	void appendModeIsRecordedAsThePullMode() throws Exception {
		source = openSource();
		exporter = new PullToH2Exporter();
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("AUDIT5");

		try (ResultSet rs = query(source, "SELECT * FROM ORDERS")) {
			exporter.append(target, "ORDERS_EXTRACT", "ID", rs, false, "SELECT * FROM ORDERS", "SRC1");
		}

		try (Connection check = DriverManager.getConnection(target.getUrl());
				ResultSet rs = query(check, "SELECT PULL_MODE FROM BROADSQL_PULL_AUDIT")) {
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals("APPEND", rs.getString(1));
		}
	}

	@Test
	void aFailedPullNeverWritesASuccessfulAuditRecord() throws Exception {
		source = openSource();
		exporter = new PullToH2Exporter();
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("AUDIT6");

		try (ResultSet rs = query(source, "SELECT ID, ID FROM ORDERS")) {
			Assertions.assertThrows(BroadSQLException.class,
					() -> exporter.overwrite(target, "ORDERS_EXTRACT", rs, "SELECT ID, ID FROM ORDERS", "SRC1"));
		}

		try (Connection check = DriverManager.getConnection(target.getUrl());
				ResultSet rs = query(check, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'BROADSQL_PULL_AUDIT'")) {
			rs.next();
			Assertions.assertEquals(0, rs.getInt(1), "a PULL that never reaches the database must not create the audit table either");
		}
	}

	@Test
	void reservedNameIsRejectedByTheExporterItselfEvenIfTheParserIsBypassed() throws Exception {
		source = openSource();
		exporter = new PullToH2Exporter();
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("AUDIT7");

		try (ResultSet rs = query(source, "SELECT * FROM ORDERS")) {
			BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
					() -> exporter.overwrite(target, "BROADSQL_PULL_AUDIT", rs, "SELECT * FROM ORDERS", "SRC1"));
			Assertions.assertTrue(ex.getMessage().contains("reserved"), "got: " + ex.getMessage());
		}
	}

	@Test
	void reservedNameIsRejectedAtParseTimeRegardlessOfCase() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO WORKCOPY.broadsql_pull_audit AS H2", null));
		Assertions.assertTrue(ex.getMessage().contains("reserved"), "got: " + ex.getMessage());
	}

	@Test
	void legacyDatabaseWithoutTheAuditTableGetsItAddedAutomaticallyOnTheNextPullWithoutLosingExistingContent() throws Exception {
		source = openSource();
		exporter = new PullToH2Exporter();
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("AUDIT8");

		// Simulate an H2 file created before this feature existed: a business table with data, no
		// BROADSQL_PULL_AUDIT table at all - built directly with SQL, not through the exporter.
		try (Connection legacy = DriverManager.getConnection(target.getUrl());
				Statement st = legacy.createStatement()) {
			st.execute("CREATE TABLE PRE_EXISTING (ID INT, NAME VARCHAR(50))");
			st.execute("INSERT INTO PRE_EXISTING VALUES (1, 'kept')");
		}

		try (ResultSet rs = query(source, "SELECT * FROM ORDERS")) {
			exporter.overwrite(target, "ORDERS_EXTRACT", rs, "SELECT * FROM ORDERS", "SRC1");
		}

		try (Connection check = DriverManager.getConnection(target.getUrl())) {
			try (ResultSet rs = query(check, "SELECT * FROM PRE_EXISTING")) {
				Assertions.assertTrue(rs.next());
				Assertions.assertEquals("kept", rs.getString("NAME"), "pre-existing content must survive untouched");
			}
			try (ResultSet rs = query(check, "SELECT COUNT(*) FROM BROADSQL_PULL_AUDIT")) {
				rs.next();
				Assertions.assertEquals(1, rs.getInt(1), "the audit table must be created automatically and get its first row");
			}
		}
	}

	@Test
	void aPreExistingIncompatibleBroadsqlPullAuditTableIsNotDestroyedAndTheOperationFailsSafely() throws Exception {
		source = openSource();
		exporter = new PullToH2Exporter();
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("AUDIT9");

		// A user's own, unrelated table that happens to collide with the reserved name.
		try (Connection legacy = DriverManager.getConnection(target.getUrl());
				Statement st = legacy.createStatement()) {
			st.execute("CREATE TABLE BROADSQL_PULL_AUDIT (SOME_COLUMN VARCHAR(20))");
			st.execute("INSERT INTO BROADSQL_PULL_AUDIT VALUES ('user data')");
		}

		try (ResultSet rs = query(source, "SELECT * FROM ORDERS")) {
			BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
					() -> exporter.overwrite(target, "ORDERS_EXTRACT", rs, "SELECT * FROM ORDERS", "SRC1"));
			Assertions.assertTrue(ex.getMessage().contains("BROADSQL_PULL_AUDIT"), "got: " + ex.getMessage());
		}

		try (Connection check = DriverManager.getConnection(target.getUrl())) {
			try (ResultSet rs = query(check, "SELECT SOME_COLUMN FROM BROADSQL_PULL_AUDIT")) {
				Assertions.assertTrue(rs.next());
				Assertions.assertEquals("user data", rs.getString(1), "the foreign table must be left completely untouched");
			}
			// H2's CREATE TABLE auto-commits immediately regardless of the connection's autocommit setting
			// (see PullToH2Exporter's class Javadoc) - so ORDERS_EXTRACT was already created by the time
			// the audit-table collision is detected and cannot be un-created by the rollback that follows.
			// The already-documented failure guarantee still holds: the rollback discards the batched
			// INSERT rows, so the table exists but is empty, never half-populated.
			try (ResultSet rs = query(check, "SELECT COUNT(*) FROM ORDERS_EXTRACT")) {
				rs.next();
				Assertions.assertEquals(0, rs.getInt(1), "ORDERS_EXTRACT must exist (DDL auto-commits) but stay empty (rollback of the INSERTs)");
			}
		}
	}
}
