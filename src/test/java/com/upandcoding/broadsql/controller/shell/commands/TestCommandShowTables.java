package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowTables;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * Harness spike for {@code docs/TESTS_STRATEGY.md} - the first command ported onto
 * {@link CommandTestSupport}/{@link TestDatabaseConnections} (a hermetic, per-test in-memory H2
 * database and a captured console) instead of the retired Spring-context harness.
 */
class TestCommandShowTables {

	private DatabaseConnection db;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"CREATE TABLE ORDERS (ID INT PRIMARY KEY, CUSTOMER_ID INT)");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void listsAllTablesInCurrentSchemaWithNoArgument() throws BroadSQLException {
		CommandShowTables cmd = CommandTestSupport.create(CommandShowTables.class, db, console);

		cmd.execute("SHOW TABLES");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("CUSTOMER"), "expected CUSTOMER in output, got:\n" + output);
		Assertions.assertTrue(output.contains("ORDERS"), "expected ORDERS in output, got:\n" + output);
		Assertions.assertTrue(output.contains("2 rows fetched."), "expected a 2-row count, got:\n" + output);
	}

	@Test
	void filtersByContainsMatchOnTableName() throws BroadSQLException {
		CommandShowTables cmd = CommandTestSupport.create(CommandShowTables.class, db, console);

		cmd.execute("SHOW TABLES CUSTOM");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("CUSTOMER"), "expected CUSTOMER in output, got:\n" + output);
		Assertions.assertFalse(output.contains("ORDERS"), "did not expect ORDERS in output, got:\n" + output);
		Assertions.assertTrue(output.contains("1 rows fetched."), "expected a 1-row count, got:\n" + output);
	}

	@Test
	void returnsNoRowsWhenNoTableMatchesThePattern() throws BroadSQLException {
		CommandShowTables cmd = CommandTestSupport.create(CommandShowTables.class, db, console);

		cmd.execute("SHOW TABLES DOESNOTEXIST");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("0 rows fetched."), "expected a 0-row count, got:\n" + output);
	}

	// ---- SHOW TABLES lists real tables only: views belong to SHOW VIEWS ----

	@Test
	void listsTablesButNotViews() throws BroadSQLException {
		db.executeUpdateQuery("CREATE TABLE SALES (ID INT PRIMARY KEY, AMOUNT INT)");
		db.executeUpdateQuery("CREATE VIEW V_SALES AS SELECT * FROM SALES");
		CommandShowTables cmd = CommandTestSupport.create(CommandShowTables.class, db, console);

		cmd.execute("SHOW TABLES SALES");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("SALES"), output);
		Assertions.assertFalse(output.contains("V_SALES"), "a view is not a table:\n" + output);
		Assertions.assertTrue(output.contains("1 rows fetched."), output);
	}

	@Test
	void listsATemporaryTableAndNeitherASynonymNorASequence() throws BroadSQLException {
		// a GLOBAL temporary table: H2 does not report its LOCAL temporary tables through JDBC metadata at all
		db.executeUpdateQuery("CREATE GLOBAL TEMPORARY TABLE TMP_WORK (ID INT)");
		db.executeUpdateQuery("CREATE SYNONYM CUST_SYN FOR CUSTOMER");
		db.executeUpdateQuery("CREATE SEQUENCE CUST_SEQ");
		CommandShowTables cmd = CommandTestSupport.create(CommandShowTables.class, db, console);

		cmd.execute("SHOW TABLES");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("TMP_WORK"), "a temporary table is a table:\n" + output);
		Assertions.assertFalse(output.contains("CUST_SYN"), "a synonym is not listed:\n" + output);
		Assertions.assertFalse(output.contains("CUST_SEQ"), "a sequence is not listed:\n" + output);
		Assertions.assertTrue(output.contains("3 rows fetched."), output);
	}
}
