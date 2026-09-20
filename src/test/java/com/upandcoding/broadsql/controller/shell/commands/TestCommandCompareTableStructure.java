package com.upandcoding.broadsql.controller.shell.commands;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.compare.CommandCompareTableStructure;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Covers {@code COMPARE TABLE STRUCTURE <table> WITH <connection>}: the source side is the current,
 * already-connected {@link DatabaseConnection} ({@code db}); the target side is a second, separate
 * in-memory H2 database, seeded directly through its own JDBC URL (mirroring
 * {@code TestCommandPull}'s assertion pattern) before the command opens its own connection to it.
 */
class TestCommandCompareTableStructure {

	private DatabaseConnection db;
	private DatabaseDefinition targetDef;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory();
		targetDef = TestDatabaseConnections.newInMemoryTarget("TARGETDB");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private void seedTarget(String... ddl) throws SQLException {
		try (Connection conn = DriverManager.getConnection(targetDef.getUrl());
				Statement stmt = conn.createStatement()) {
			for (String sql : ddl) {
				stmt.execute(sql);
			}
		}
	}

	private CommandCompareTableStructure command() {
		CommandCompareTableStructure cmd = CommandTestSupport.create(CommandCompareTableStructure.class, db, console);
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("B", targetDef);
		vault.setDatabaseConnections(connections);
		cmd.setDatabaseConnectionsVault(vault);
		return cmd;
	}

	@Test
	void reportsAnIdenticalStructure() throws BroadSQLException, SQLException {
		db.executeUpdateQuery("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		seedTarget("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");

		command().execute("COMPARE TABLE STRUCTURE CUSTOMER WITH B");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("identical structure"),
				"expected the identical-structure message, got:\n" + output);
	}

	@Test
	void reportsColumnsOnlyOnEachSideAndDifferingDefinitions() throws BroadSQLException, SQLException {
		db.executeUpdateQuery("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50), SOURCE_ONLY VARCHAR(10))");
		seedTarget("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(100), TARGET_ONLY VARCHAR(10))");

		command().execute("COMPARE TABLE STRUCTURE CUSTOMER WITH B");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Columns only on this connection"), "got:\n" + output);
		Assertions.assertTrue(output.contains("SOURCE_ONLY"), "got:\n" + output);
		Assertions.assertTrue(output.contains("Columns only on 'B'"), "got:\n" + output);
		Assertions.assertTrue(output.contains("TARGET_ONLY"), "got:\n" + output);
		Assertions.assertTrue(output.contains("Columns with a different definition"), "got:\n" + output);
		Assertions.assertTrue(output.contains("NAME"), "got:\n" + output);
		Assertions.assertTrue(output.contains("(50)"), "got:\n" + output);
		Assertions.assertTrue(output.contains("(100)"), "got:\n" + output);
		Assertions.assertTrue(output.contains("Structure is identical for 1 column(s)."),
				"expected only ID to match exactly, got:\n" + output);
	}

	@Test
	void reportsAnErrorWhenTheConnectionIsNotDefined() throws BroadSQLException {
		db.executeUpdateQuery("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY)");
		CommandCompareTableStructure cmd = CommandTestSupport.create(CommandCompareTableStructure.class, db, console);
		cmd.setDatabaseConnectionsVault(new DatabaseDefinitionsVault());

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> cmd.execute("COMPARE TABLE STRUCTURE CUSTOMER WITH B"));

		Assertions.assertTrue(ex.getMessage().contains("not defined"), "got: " + ex.getMessage());
	}

	@Test
	void reportsAnErrorWhenTheTableDoesNotExistOnTheCurrentConnection() throws SQLException {
		seedTarget("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY)");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> command().execute("COMPARE TABLE STRUCTURE CUSTOMER WITH B"));

		Assertions.assertTrue(ex.getMessage().contains("does not exist on the current connection"), "got: " + ex.getMessage());
	}

	@Test
	void reportsAnErrorWhenTheTableDoesNotExistOnTheTargetConnection() throws BroadSQLException {
		db.executeUpdateQuery("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY)");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> command().execute("COMPARE TABLE STRUCTURE CUSTOMER WITH B"));

		Assertions.assertTrue(ex.getMessage().contains("does not exist on connection 'B'"), "got: " + ex.getMessage());
	}

	@Test
	void reportsAnErrorWhenTheWithClauseIsMissing() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> command().execute("COMPARE TABLE STRUCTURE CUSTOMER"));

		Assertions.assertTrue(ex.getMessage().contains("WITH"), "got: " + ex.getMessage());
	}
}
