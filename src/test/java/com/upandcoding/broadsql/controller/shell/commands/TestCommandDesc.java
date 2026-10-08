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

	// ---- DESC <table> ALPHA: same columns and information, rows sorted by name ignoring case ----

	private static final String CLIENT_DDL = "CREATE TABLE CLIENT (ID INT PRIMARY KEY, CREATED_AT TIMESTAMP, "
			+ "LAST_NAME VARCHAR(40) NOT NULL, FIRST_NAME VARCHAR(30), STATUS CHAR(1) DEFAULT 'A', UPDATED_AT TIMESTAMP)";

	/** The first cell of every data row, in display order (the header row {@code Name} excluded). */
	private static java.util.List<String> columnOrder(String output) {
		java.util.List<String> names = new java.util.ArrayList<>();
		boolean header = true;
		for (String line : output.split("\\R")) {
			if (line.startsWith("|") && !line.startsWith("|-")) {
				if (header) {
					header = false;
				} else {
					names.add(line.substring(1, line.indexOf('|', 1)).trim());
				}
			}
		}
		return names;
	}

	private String describe(String query) throws BroadSQLException {
		CapturingShellConsole out = new CapturingShellConsole();
		CommandTestSupport.create(CommandDescr.class, db, out).execute(query);
		return out.getOutput();
	}

	/** Every line of {@code output}, sorted: equal for two outputs holding the same rows in any order. */
	private static java.util.List<String> sortedLines(String output) {
		java.util.List<String> lines = new java.util.ArrayList<>(java.util.List.of(output.split("\\R")));
		java.util.Collections.sort(lines);
		return lines;
	}

	@Test
	void withoutAlphaKeepsTheNaturalColumnOrder() throws BroadSQLException {
		db.executeUpdateQuery(CLIENT_DDL);

		String output = describe("DESC CLIENT");

		Assertions.assertEquals(java.util.List.of("ID", "CREATED_AT", "LAST_NAME", "FIRST_NAME", "STATUS", "UPDATED_AT"),
				columnOrder(output), output);
	}

	@Test
	void alphaSortsTheColumnsByName() throws BroadSQLException {
		db.executeUpdateQuery(CLIENT_DDL);

		String output = describe("DESC CLIENT ALPHA");

		Assertions.assertEquals(java.util.List.of("CREATED_AT", "FIRST_NAME", "ID", "LAST_NAME", "STATUS", "UPDATED_AT"),
				columnOrder(output), output);
	}

	@Test
	void alphaSortIgnoresCase() throws BroadSQLException {
		// A case-sensitive sort would give Alpha, Zeta, beta (upper case sorts before lower case)
		db.executeUpdateQuery("CREATE TABLE MIXED (\"Zeta\" INT, \"beta\" INT, \"Alpha\" INT)");

		Assertions.assertEquals(java.util.List.of("Alpha", "beta", "Zeta"), columnOrder(describe("DESC MIXED ALPHA")));
		Assertions.assertEquals(java.util.List.of("Zeta", "beta", "Alpha"), columnOrder(describe("DESC MIXED")));
	}

	@Test
	void alphaShowsExactlyTheSameInformationOnlyReordered() throws BroadSQLException {
		db.executeUpdateQuery("CREATE TABLE T (Z INTEGER NOT NULL, A VARCHAR(20) DEFAULT 'none', M DECIMAL(10,2) DEFAULT 0, N NUMERIC(8,3), C CHAR(5))");

		String natural = describe("DESCR T");
		String alpha = describe("DESCR T ALPHA");

		Assertions.assertEquals(java.util.List.of("A", "C", "M", "N", "Z"), columnOrder(alpha), alpha);
		Assertions.assertNotEquals(natural, alpha);
		Assertions.assertEquals(sortedLines(natural), sortedLines(alpha), "same lines, other order:\n" + natural + "\n" + alpha);
		for (String column : new String[] { "Z", "A", "M", "N", "C" }) {
			Assertions.assertEquals(row(natural, column), row(alpha, column), "row of " + column);
		}
	}

	@Test
	void alphaKeywordIsCaseInsensitiveAndNotSticky() throws BroadSQLException {
		db.executeUpdateQuery(CLIENT_DDL);
		CommandDescr cmd = CommandTestSupport.create(CommandDescr.class, db, console);

		cmd.execute("desc CLIENT alpha");
		Assertions.assertEquals("CREATED_AT", columnOrder(console.getOutput()).get(0), console.getOutput());

		// The same command instance, run again without ALPHA, is back to the natural order
		int firstRunLength = console.getOutput().length();
		cmd.execute("DESC CLIENT");
		String second = console.getOutput().substring(firstRunLength);
		Assertions.assertEquals("ID", columnOrder(second).get(0), second);
	}

	@Test
	void alphaWorksWithASchemaQualifiedName() throws BroadSQLException {
		db.executeUpdateQuery("CREATE SCHEMA OTHERSCHEMA");
		db.executeUpdateQuery("CREATE TABLE OTHERSCHEMA.TOTO (OTHER_B INT, OTHER_A VARCHAR(10))");
		db.executeUpdateQuery("CREATE TABLE PUBLIC.TOTO (CURRENTCOL INT)");

		String output = describe("DESC OTHERSCHEMA.TOTO ALPHA");

		Assertions.assertEquals(java.util.List.of("OTHER_A", "OTHER_B"), columnOrder(output), output);
	}

	@Test
	void alphaWorksWithAQuotedTableName() throws BroadSQLException {
		db.executeUpdateQuery("CREATE TABLE \"Order Items\" (\"qty\" INT, \"Name\" VARCHAR(20), \"amount\" DECIMAL(8,2))");

		Assertions.assertEquals(java.util.List.of("qty", "Name", "amount"), columnOrder(describe("DESC \"Order Items\"")));
		Assertions.assertEquals(java.util.List.of("amount", "Name", "qty"), columnOrder(describe("DESC \"Order Items\" ALPHA")));
	}

	@Test
	void alphaWorksOnAView() throws BroadSQLException {
		db.executeUpdateQuery(CLIENT_DDL);
		db.executeUpdateQuery("CREATE VIEW CLIENT_V AS SELECT STATUS, ID, LAST_NAME FROM CLIENT");

		Assertions.assertEquals(java.util.List.of("STATUS", "ID", "LAST_NAME"), columnOrder(describe("DESC CLIENT_V")));
		Assertions.assertEquals(java.util.List.of("ID", "LAST_NAME", "STATUS"), columnOrder(describe("DESC CLIENT_V ALPHA")));
	}

	@Test
	void aTableNamedAlphaIsStillDescribed() throws BroadSQLException {
		db.executeUpdateQuery("CREATE TABLE ALPHA (Y INT, X INT)");

		Assertions.assertEquals(java.util.List.of("Y", "X"), columnOrder(describe("DESC ALPHA")));
		Assertions.assertEquals(java.util.List.of("X", "Y"), columnOrder(describe("DESC ALPHA ALPHA")));
	}

	@Test
	void describeAndDescrAliasesAcceptAlphaIdentically() throws BroadSQLException {
		db.executeUpdateQuery(CLIENT_DDL);

		String desc = describe("DESC CLIENT ALPHA");
		Assertions.assertEquals(desc, describe("DESCRIBE CLIENT ALPHA"));
		Assertions.assertEquals(desc, describe("DESCR CLIENT ALPHA"));
	}

	@Test
	void describeAlphaRunsThroughTheInterpreterWithItsSemicolon() throws BroadSQLException {
		db.executeUpdateQuery(CLIENT_DDL);
		CommandInterpreter interpreter = CommandTestSupport.createFullCommandInterpreter(
				TestDatabaseConnections.defaultConsoleSettings(), console, db);

		interpreter.executeMultiStatementLine("DESCRIBE CLIENT ALPHA;");

		Assertions.assertEquals(java.util.List.of("CREATED_AT", "FIRST_NAME", "ID", "LAST_NAME", "STATUS", "UPDATED_AT"),
				columnOrder(console.getOutput()), console.getOutput());
	}

	@Test
	void rejectsAnythingElseAfterTheTableName() throws BroadSQLException {
		db.executeUpdateQuery(CLIENT_DDL);

		for (String query : new String[] { "DESC CLIENT BETA", "DESC CLIENT ALPHA EXTRA", "DESC CLIENT NATURAL", "DESCRIBE CLIENT ALPHA ALPHA" }) {
			String output = describe(query);
			Assertions.assertTrue(output.contains("ERROR"), query + ": expected an error, got:\n" + output);
			Assertions.assertTrue(output.contains("Usage: DESCR <tableName> [ALPHA]"), query + ":\n" + output);
			Assertions.assertTrue(columnOrder(output).isEmpty(), query + ": no table expected:\n" + output);
		}
	}
}
