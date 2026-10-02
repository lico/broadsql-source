package com.upandcoding.broadsql.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Closing a connection with {@code Autocommit} off ({@code DISCONNECT}, {@code BYE}, switching connection, exit):
 * pending work is rolled back and reported, never committed, and closing always succeeds, on the bundled HSQLDB
 * (which has no H2-style transaction view) as on H2, and when a database's transaction probe fails.
 */
class TestDatabaseConnectionClose {

	private static DatabaseDefinition hsqldb(String url) {
		DatabaseDefinition def = new DatabaseDefinition("HSQL_" + UUID.randomUUID().toString().substring(0, 8));
		def.setDbDriver("org.hsqldb.jdbc.JDBCDriver");
		def.setDbType(SpringPropertiesConfig.DBTYPE_HSQL);
		def.setDbName("HSQL");
		def.setUrl(url);
		def.setUserName("SA");
		def.setUserPassword("");
		return def;
	}

	private static ConsoleSettings autocommitOff() {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setAutoCommit(false);
		return settings;
	}

	private static DatabaseConnection open(DatabaseDefinition def, CapturingShellConsole console) throws BroadSQLException {
		DatabaseConnection db = TestDatabaseConnections.connect(def, autocommitOff());
		db.setCmdLineConsole(console);
		return db;
	}

	private static int count(String url, String user, String table) throws SQLException {
		try (Connection conn = DriverManager.getConnection(url, user, ""); Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
			rs.next();
			return rs.getInt(1);
		}
	}

	private static String hsqldbUrl() {
		return "jdbc:hsqldb:mem:close_" + UUID.randomUUID().toString().replace("-", "");
	}

	@Test
	void disconnectingFromHsqldbWithAutocommitOffAndNoChangesClosesQuietly() throws Exception {
		String url = hsqldbUrl();
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(hsqldb(url), console);

		Assertions.assertDoesNotThrow(() -> db.close());

		Assertions.assertFalse(db.isConnected(), "the connection must be closed");
		Assertions.assertTrue(console.getOutput().contains("Disconnected from 'HSQL'"), console.getOutput());
		Assertions.assertFalse(console.getOutput().contains("Rolling back"), "nothing was pending:\n" + console.getOutput());
		Assertions.assertFalse(console.wasErrorReported(), console.getOutput());
	}

	@Test
	void disconnectingFromHsqldbWithAPendingWriteRollsItBackAndCloses() throws Exception {
		String url = hsqldbUrl();
		try (Connection seed = DriverManager.getConnection(url, "SA", ""); Statement st = seed.createStatement()) {
			st.execute("CREATE TABLE T (ID INT)");
		}
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(hsqldb(url), console);
		db.executeUpdateQuery("INSERT INTO T VALUES (1)");

		Assertions.assertDoesNotThrow(() -> db.close());

		Assertions.assertFalse(db.isConnected(), "the connection must be closed");
		Assertions.assertTrue(console.getOutput().contains("Uncommitted transactions aborted. Rolling back."), console.getOutput());
		Assertions.assertFalse(console.wasErrorReported(), console.getOutput());
		Assertions.assertEquals(0, count(url, "SA", "T"), "the pending insert must not have been committed");
	}

	@Test
	void switchingAwayFromHsqldbGoesThroughTheSameCloseAndDoesNotCommit() throws Exception {
		String url = hsqldbUrl();
		try (Connection seed = DriverManager.getConnection(url, "SA", ""); Statement st = seed.createStatement()) {
			st.execute("CREATE TABLE T (ID INT)");
		}
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(hsqldb(url), console);
		db.executeUpdateQuery("INSERT INTO T VALUES (1)");

		// what CONNECT and ENV do when leaving a connection: close the current one, then open the next
		db.close(false);
		db.setPlatform(TestDatabaseConnections.newInMemoryTarget("NEXT"));
		db.connect();

		Assertions.assertTrue(db.isConnected());
		Assertions.assertTrue(console.getOutput().contains("Rolling back"), console.getOutput());
		Assertions.assertEquals(0, count(url, "SA", "T"));
		TestDatabaseConnections.close(db);
	}

	@Test
	void disconnectingFromH2WithAPendingWriteStillUsesItsProbeAndRollsBack() throws Exception {
		DatabaseDefinition h2 = TestDatabaseConnections.newInMemoryTarget("H2CLOSE");
		try (Connection seed = DriverManager.getConnection(h2.getUrl()); Statement st = seed.createStatement()) {
			st.execute("CREATE TABLE T (ID INT)");
		}
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(h2, console);
		db.executeUpdateQuery("INSERT INTO T VALUES (1)");

		db.close();

		Assertions.assertTrue(console.getOutput().contains("Uncommitted transactions aborted. Rolling back."), console.getOutput());
		Assertions.assertEquals(0, count(h2.getUrl(), "", "T"));
	}

	@Test
	void aTransactionProbeThatFailsRollsBackWarnsAndStillCloses() throws Exception {
		// An H2 database declared as Oracle: the Oracle probe (DBMS_TRANSACTION) fails, as it does on an Oracle
		// database where that package is not granted
		DatabaseDefinition def = TestDatabaseConnections.newInMemoryTarget("PROBEFAIL");
		def.setDbType(SpringPropertiesConfig.DBTYPE_Oracle);
		try (Connection seed = DriverManager.getConnection(def.getUrl()); Statement st = seed.createStatement()) {
			st.execute("CREATE TABLE T (ID INT)");
		}
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(def, console);
		db.executeUpdateQuery("INSERT INTO T VALUES (1)");

		Assertions.assertDoesNotThrow(() -> db.close());

		Assertions.assertFalse(db.isConnected());
		Assertions.assertTrue(console.getOutput().contains("Could not determine whether uncommitted changes are pending. Rolling back."),
				console.getOutput());
		Assertions.assertEquals(0, count(def.getUrl(), "", "T"), "unknown pending work must be rolled back, never committed");
	}
}
