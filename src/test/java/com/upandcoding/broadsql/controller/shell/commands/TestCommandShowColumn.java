package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowColumn;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandShowColumn {

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
	void findsColumnsAcrossTablesByContainsMatch() throws BroadSQLException {
		CommandShowColumn cmd = CommandTestSupport.create(CommandShowColumn.class, db, console);

		cmd.execute("SHOW COLUMN NAME");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("CUSTOMER"), "expected CUSTOMER.NAME in output, got:\n" + output);
		Assertions.assertFalse(output.contains("ORDERS"), "did not expect ORDERS in output, got:\n" + output);
	}

	@Test
	void rejectsAMissingColumnName() {
		CommandShowColumn cmd = CommandTestSupport.create(CommandShowColumn.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("SHOW COLUMN"));

		Assertions.assertTrue(console.getOutput().contains("Not enough arguments"),
				"expected the missing-argument error, got:\n" + console.getOutput());
	}

	/**
	 * Regression test for docs/TODO.md item 9 ("New command: find all tables containing a given
	 * column name"): {@code FIND COLUMN} is the new recommended keyword for this same command,
	 * {@code SHOW COLUMN} kept as a working legacy alias - not a separate command/behavior.
	 */
	@Test
	void findColumnIsAWorkingAliasForShowColumn() throws BroadSQLException {
		CommandShowColumn cmd = CommandTestSupport.create(CommandShowColumn.class, db, console);

		cmd.execute("FIND COLUMN NAME");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("CUSTOMER"), "expected CUSTOMER.NAME in output, got:\n" + output);
		Assertions.assertFalse(output.contains("ORDERS"), "did not expect ORDERS in output, got:\n" + output);
	}

	@Test
	void findColumnIsTheKeywordDisplayedByHelp() {
		CommandShowColumn cmd = CommandTestSupport.create(CommandShowColumn.class, db, console);

		Assertions.assertEquals("FIND COLUMN", cmd.getKeywords()[0],
				"FIND COLUMN should be the primary/displayed keyword now that it is the recommended form");
		Assertions.assertTrue(cmd.getSynonyms().contains("SHOW COLUMN"),
				"SHOW COLUMN should still be listed as a synonym, got: " + cmd.getSynonyms());
	}
}
