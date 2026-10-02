package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandDescr;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandDesc {

	private DatabaseConnection db;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50) NOT NULL)");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void describesAnExistingTableByExactCase() throws BroadSQLException {
		CommandDescr cmd = CommandTestSupport.create(CommandDescr.class, db, console);

		cmd.execute("DESC CUSTOMER");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("ID"), "expected column ID in output, got:\n" + output);
		Assertions.assertTrue(output.contains("NAME"), "expected column NAME in output, got:\n" + output);
		Assertions.assertFalse(output.contains("not found"),
				"did not expect a fallback match message on an exact-case match, got:\n" + output);
	}

	@Test
	void reportsWhichCaseVariantWasActuallyMatchedOnAFallback() throws BroadSQLException {
		CommandDescr cmd = CommandTestSupport.create(CommandDescr.class, db, console);

		// Typed lower-case; the table was created unquoted, so H2 stores/matches it as CUSTOMER.
		cmd.execute("DESC customer");

		String output = console.getOutput();
		Assertions.assertTrue(
				output.contains("Table 'customer' not found. Found table 'CUSTOMER' instead."),
				"expected the fallback match message, got:\n" + output);
		Assertions.assertTrue(output.contains("ID"), "expected column ID in output, got:\n" + output);
	}

	@Test
	void reportsAnErrorForATableThatDoesNotExist() {
		CommandDescr cmd = CommandTestSupport.create(CommandDescr.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DESC DOESNOTEXIST"));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("ERROR"), "expected an error message, got:\n" + output);
		Assertions.assertTrue(output.contains("does not exist"), "expected a 'does not exist' message, got:\n" + output);
	}

	@Test
	void describesOnlyTheCurrentSchemaWhenNoSchemaIsGiven() throws BroadSQLException {
		// Reproduces docs/TODO.md #12 ("DESC TOTO on Oracle shows columns from every schema, not just
		// the current one"): a same-named table in another schema must not leak into the unqualified
		// DESC output, and the current (PUBLIC) schema's own columns must still be the ones shown.
		db.executeUpdateQuery("CREATE SCHEMA OTHERSCHEMA");
		db.executeUpdateQuery("CREATE TABLE OTHERSCHEMA.TOTO (OTHERCOL VARCHAR(10))");
		db.executeUpdateQuery("CREATE TABLE PUBLIC.TOTO (CURRENTCOL INT)");

		CommandDescr cmd = CommandTestSupport.create(CommandDescr.class, db, console);

		cmd.execute("DESC TOTO");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("CURRENTCOL"),
				"expected the current schema's column CURRENTCOL in output, got:\n" + output);
		Assertions.assertFalse(output.contains("OTHERCOL"),
				"did not expect the other schema's column OTHERCOL in output, got:\n" + output);
	}

	@Test
	void describesAnExplicitlyQualifiedTableInAnotherSchema() throws BroadSQLException {
		db.executeUpdateQuery("CREATE SCHEMA OTHERSCHEMA");
		db.executeUpdateQuery("CREATE TABLE OTHERSCHEMA.TOTO (OTHERCOL VARCHAR(10))");
		db.executeUpdateQuery("CREATE TABLE PUBLIC.TOTO (CURRENTCOL INT)");

		CommandDescr cmd = CommandTestSupport.create(CommandDescr.class, db, console);

		cmd.execute("DESC OTHERSCHEMA.TOTO");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("OTHERCOL"),
				"expected the explicitly-qualified schema's column OTHERCOL in output, got:\n" + output);
		Assertions.assertFalse(output.contains("CURRENTCOL"),
				"did not expect the current schema's column CURRENTCOL in output, got:\n" + output);
	}

	@Test
	void reportsAnErrorWhenNoTableNameIsGiven() {
		CommandDescr cmd = CommandTestSupport.create(CommandDescr.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DESC"));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains(BroadSQLErrorMessages.ERR_GAL_01),
				"expected the 'missing table name' error, got:\n" + output);
	}
	/** One DESCR data row as its cells, padding removed: {@code |Z   |T  |...|} becomes [Z, T, ...]. */
	private static java.util.List<String> row(String output, String column) {
		for (String line : output.split("\\R")) {
			if (line.startsWith("|" + column + " ") || line.startsWith("|" + column + "|")) {
				java.util.List<String> cells = new java.util.ArrayList<>();
				for (String cell : line.substring(1, line.length() - 1).split("\\|", -1)) {
					cells.add(cell.trim());
				}
				return cells;
			}
		}
		Assertions.fail("no row for column " + column + " in:\n" + output);
		return null;
	}

	@Test
	void listsColumnsInTableOrderWithSizePrecisionScaleNullabilityAndDefault() throws BroadSQLException {
		db.executeUpdateQuery("CREATE TABLE T (Z INTEGER NOT NULL, A VARCHAR(20) DEFAULT 'none', M DECIMAL(10,2) DEFAULT 0, N NUMERIC(8,3), C CHAR(5))");
		CommandDescr cmd = CommandTestSupport.create(CommandDescr.class, db, console);

		cmd.execute("DESCR T");

		String output = console.getOutput();
		java.util.List<String> order = new java.util.ArrayList<>();
		for (String line : output.split("\\R")) {
			for (String column : new String[] { "Z", "A", "M", "N", "C" }) {
				if (line.startsWith("|" + column + " ")) {
					order.add(column);
				}
			}
		}
		Assertions.assertEquals(java.util.List.of("Z", "A", "M", "N", "C"), order, "table order, not alphabetical:\n" + output);
		// Name | Table | Schema | Data Type | Nullable | Default | Size
		Assertions.assertEquals("NO", row(output, "Z").get(4), output);
		Assertions.assertEquals("YES", row(output, "A").get(4), output);
		Assertions.assertEquals("20", row(output, "A").get(6), "VARCHAR length:\n" + output);
		Assertions.assertEquals("5", row(output, "C").get(6), "CHAR length:\n" + output);
		Assertions.assertEquals("10,2", row(output, "M").get(6), "DECIMAL precision,scale:\n" + output);
		Assertions.assertEquals("8,3", row(output, "N").get(6), "NUMERIC precision,scale:\n" + output);
		Assertions.assertEquals("'none'", row(output, "A").get(5), output);
		Assertions.assertEquals("0", row(output, "M").get(5), output);
		Assertions.assertEquals("", row(output, "N").get(5), "no default:\n" + output);
	}
}
