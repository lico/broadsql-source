package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.miachm.sods.SpreadSheet;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandDumpTable;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * SPRINT 1005C (#202): plain {@code MODE APPEND} (no {@code KEY}), end to end through the real
 * {@code DUMP}/{@code PULL} commands, {@code ExportPipeline} and writers, against real in-memory H2 databases and
 * real XLSX/ODS files. The one rule: an existing destination with the same column count, names and order as the
 * final result gets the rows appended; the SQL that produced the result is never analyzed and types are never
 * compared; a mismatch is refused before the destination changes.
 */
class TestCommandDumpPlainAppend {

	@TempDir
	Path tmp;

	private DatabaseConnection sourceDb;
	private DatabaseDefinition targetDef;
	private CapturingShellConsole console;
	private ConsoleSettings settings;

	@BeforeEach
	void setUp() throws BroadSQLException {
		sourceDb = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMERS (ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(50), REGION_ID INT)",
				"CREATE TABLE REGIONS (ID INT NOT NULL PRIMARY KEY, LABEL VARCHAR(20))",
				"CREATE TABLE ORDERS (ID INT NOT NULL PRIMARY KEY, CUSTOMER_ID INT NOT NULL, AMOUNT DECIMAL(10,2))",
				"INSERT INTO REGIONS VALUES (1, 'North'), (2, 'South')",
				"INSERT INTO CUSTOMERS VALUES (1, 'Alice', 1), (2, 'Bob', 2)",
				"INSERT INTO ORDERS VALUES (100, 1, 50.00), (101, 2, 75.50), (102, 1, NULL)");
		targetDef = TestDatabaseConnections.newInMemoryTarget("TARGETDB");
		console = new CapturingShellConsole();
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tmp.toString() + File.separator);
		LastQueryResultHolder.set(null);
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(sourceDb);
		LastQueryResultHolder.set(null);
	}

	private DatabaseDefinitionsVault vault() {
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("TARGETDB", targetDef);
		vault.setDatabaseConnections(connections);
		return vault;
	}

	private void dump(String line) throws BroadSQLException {
		console = new CapturingShellConsole();
		CommandDumpTable cmd = CommandTestSupport.create(CommandDumpTable.class, sourceDb, console, settings);
		cmd.setDatabaseConnectionsVault(vault());
		cmd.execute(line);
	}

	private void pull(String line) throws BroadSQLException {
		console = new CapturingShellConsole();
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, sourceDb, console, settings);
		cmd.setDatabaseConnectionsVault(vault());
		cmd.execute(line);
	}

	private void target(String... sql) throws SQLException {
		try (Connection conn = DriverManager.getConnection(targetDef.getUrl()); Statement stmt = conn.createStatement()) {
			for (String statement : sql) {
				stmt.execute(statement);
			}
		}
	}

	/** Every row of {@code table}, as {@code a|b|c} strings, ordered by the numeric first column, then by text. */
	private List<String> rows(String table) throws SQLException {
		List<String> rows = new ArrayList<>();
		try (Connection conn = DriverManager.getConnection(targetDef.getUrl());
				Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT * FROM \"" + table + "\" ORDER BY 1")) {
			int columns = rs.getMetaData().getColumnCount();
			while (rs.next()) {
				StringBuilder row = new StringBuilder();
				for (int i = 1; i <= columns; i++) {
					row.append(i > 1 ? "|" : "").append(rs.getString(i));
				}
				rows.add(row.toString());
			}
		}
		return sortById(rows);
	}

	/** An existing ORDER_REPORT table with one row, the destination most tests append to. */
	private void existingOrderReport() throws SQLException {
		target("CREATE TABLE \"ORDER_REPORT\" (\"ID\" INT, \"CUSTOMER\" VARCHAR(50), \"AMOUNT\" DECIMAL(10,2))",
				"INSERT INTO \"ORDER_REPORT\" VALUES (1, 'Existing', 1.00)");
	}

	private void assertRefusedAndUnchanged(String line, String expectedMessagePart) throws SQLException {
		List<String> before = rows("ORDER_REPORT");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> dump(line));
		Assertions.assertTrue(ex.getMessage().contains(expectedMessagePart), "got: " + ex.getMessage());
		Assertions.assertEquals(before, rows("ORDER_REPORT"), "a refused APPEND must leave the destination unchanged");
	}

	// =================================================================================================
	// H2: the SQL is irrelevant
	// =================================================================================================

	@Test
	void aSimpleSelectAppendsToAnExistingH2Table() throws Exception {
		existingOrderReport();

		dump("DUMP (SELECT ID, NAME AS CUSTOMER, REGION_ID AS AMOUNT FROM CUSTOMERS) TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND");

		Assertions.assertTrue(console.getOutput().contains("2 row(s) exported to TARGETDB.ORDER_REPORT (rows appended)."), console.getOutput());
		Assertions.assertEquals(List.of("1|Alice|1.00", "1|Existing|1.00", "2|Bob|2.00"), rows("ORDER_REPORT"));
	}

	@Test
	void aJoinQueryAppendsUnderTheSameRule() throws Exception {
		existingOrderReport();

		dump("DUMP (SELECT o.ID AS ID, c.NAME AS CUSTOMER, o.AMOUNT AS AMOUNT FROM ORDERS o "
				+ "JOIN CUSTOMERS c ON c.ID = o.CUSTOMER_ID) TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND");

		Assertions.assertEquals(4, rows("ORDER_REPORT").size(), console.getOutput());
		Assertions.assertTrue(rows("ORDER_REPORT").contains("101|Bob|75.50"));
	}

	@Test
	void aMultiTableJoinWithAnOuterJoinAndAGroupByAppends() throws Exception {
		existingOrderReport();

		dump("DUMP (SELECT c.ID AS ID, r.LABEL || ':' || c.NAME AS CUSTOMER, SUM(o.AMOUNT) AS AMOUNT FROM CUSTOMERS c "
				+ "JOIN REGIONS r ON r.ID = c.REGION_ID LEFT JOIN ORDERS o ON o.CUSTOMER_ID = c.ID "
				+ "GROUP BY c.ID, r.LABEL, c.NAME ORDER BY c.ID) TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND");

		Assertions.assertEquals(List.of("1|Existing|1.00", "1|North:Alice|50.00", "2|South:Bob|75.50"), rows("ORDER_REPORT"));
	}

	@Test
	void aCteASubqueryAndAUnionAppend() throws Exception {
		existingOrderReport();

		dump("DUMP (WITH BIG AS (SELECT * FROM ORDERS WHERE AMOUNT > 60) SELECT ID, 'cte' AS CUSTOMER, AMOUNT FROM BIG) "
				+ "TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND");
		dump("DUMP (SELECT * FROM (SELECT ID, NAME AS CUSTOMER, 0 AS AMOUNT FROM CUSTOMERS) X WHERE X.ID = 1) "
				+ "TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND");
		dump("DUMP (SELECT 7 AS ID, 'u1' AS CUSTOMER, 1 AS AMOUNT UNION ALL SELECT 8, 'u2', 2) TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND");

		Assertions.assertEquals(List.of("1|Alice|0.00", "1|Existing|1.00", "7|u1|1.00", "8|u2|2.00", "101|cte|75.50"), rows("ORDER_REPORT"));
	}

	private static List<String> sortById(List<String> rows) {
		List<String> sorted = new ArrayList<>(rows);
		sorted.sort((a, b) -> {
			int byId = Integer.compare(Integer.parseInt(a.split("\\|")[0]), Integer.parseInt(b.split("\\|")[0]));
			return byId != 0 ? byId : a.compareTo(b);
		});
		return sorted;
	}

	@Test
	void legacyPullAppendsTheSameWay() throws Exception {
		existingOrderReport();

		pull("PULL (SELECT o.ID AS ID, c.NAME AS CUSTOMER, o.AMOUNT AS AMOUNT FROM ORDERS o JOIN CUSTOMERS c ON c.ID = o.CUSTOMER_ID) "
				+ "TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND");

		Assertions.assertTrue(console.getOutput().contains("3 row(s) pulled into TARGETDB.ORDER_REPORT (rows appended)."), console.getOutput());
		Assertions.assertEquals(4, rows("ORDER_REPORT").size());
	}

	@Test
	void columnNamesAreComparedCaseInsensitively() throws Exception {
		existingOrderReport();

		dump("DUMP (SELECT 5 AS id, 'x' AS Customer, 2 AS amount) TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND");

		Assertions.assertTrue(rows("ORDER_REPORT").contains("5|x|2.00"));
	}

	// =================================================================================================
	// H2: the structural rule
	// =================================================================================================

	@Test
	void aDifferentColumnCountIsRefusedBeforeAnyWrite() throws Exception {
		existingOrderReport();

		assertRefusedAndUnchanged("DUMP (SELECT ID, NAME AS CUSTOMER, 1 AS AMOUNT, 2 AS EXTRA FROM CUSTOMERS) TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND",
				"APPEND refused: destination has 3 columns but query returns 4.");
		assertRefusedAndUnchanged("DUMP (SELECT ID, NAME AS CUSTOMER FROM CUSTOMERS) TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND",
				"APPEND refused: destination has 3 columns but query returns 2.");
	}

	@Test
	void aDifferentColumnNameIsRefusedBeforeAnyWrite() throws Exception {
		existingOrderReport();

		assertRefusedAndUnchanged("DUMP (SELECT ID, NAME AS CUSTOMER_NAME, 1 AS AMOUNT FROM CUSTOMERS) TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND",
				"APPEND refused: column 2 differs.\nQuery: CUSTOMER_NAME\nDestination: CUSTOMER");
	}

	@Test
	void theSameNamesInAnotherOrderAreRefused() throws Exception {
		existingOrderReport();

		assertRefusedAndUnchanged("DUMP (SELECT NAME AS CUSTOMER, ID, 1 AS AMOUNT FROM CUSTOMERS) TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND",
				"APPEND refused: column 1 differs.");
	}

	@Test
	void differentTypesWithMatchingNamesAreNotRefused() throws Exception {
		// Destination: VARCHAR / INT / VARCHAR. Query: INT / VARCHAR digits / DECIMAL. Nothing compares the types.
		target("CREATE TABLE \"MIXED\" (\"CODE\" VARCHAR(20), \"QTY\" INT, \"PRICE\" VARCHAR(30))");

		dump("DUMP (SELECT ID AS CODE, CAST(REGION_ID AS VARCHAR(5)) AS QTY, CAST(ID * 1.5 AS DECIMAL(5,2)) AS PRICE FROM CUSTOMERS) "
				+ "TO TARGETDB.MIXED AS H2 MODE APPEND");

		Assertions.assertEquals(List.of("1|1|1.50", "2|2|3.00"), rows("MIXED"), console.getOutput());
	}

	@Test
	void nullValuesAreAppendedAsNull() throws Exception {
		existingOrderReport();

		dump("DUMP (SELECT ID, CAST(NULL AS VARCHAR(10)) AS CUSTOMER, AMOUNT FROM ORDERS WHERE AMOUNT IS NULL) TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND");

		Assertions.assertTrue(rows("ORDER_REPORT").contains("102|null|null"), rows("ORDER_REPORT").toString());
	}

	@Test
	void zeroRowsSucceedsAndAddsNothing() throws Exception {
		existingOrderReport();

		dump("DUMP (SELECT ID, NAME AS CUSTOMER, 1 AS AMOUNT FROM CUSTOMERS WHERE 1 = 0) TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND");

		Assertions.assertTrue(console.getOutput().contains("0 row(s) exported to TARGETDB.ORDER_REPORT (rows appended)."), console.getOutput());
		Assertions.assertEquals(List.of("1|Existing|1.00"), rows("ORDER_REPORT"));
	}

	@Test
	void zeroRowsWithAMismatchIsStillRefused() throws Exception {
		existingOrderReport();

		assertRefusedAndUnchanged("DUMP (SELECT ID FROM CUSTOMERS WHERE 1 = 0) TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND",
				"APPEND refused: destination has 3 columns but query returns 1.");
	}

	@Test
	void aMissingTableIsCreatedThenAppendedTo() throws Exception {
		dump("DUMP (SELECT ID, NAME FROM CUSTOMERS WHERE ID = 1) TO TARGETDB.FRESH AS H2 MODE APPEND");
		Assertions.assertTrue(console.getOutput().contains("1 row(s) exported to TARGETDB.FRESH (table created)."), console.getOutput());

		dump("DUMP (SELECT ID, NAME FROM CUSTOMERS WHERE ID = 2) TO TARGETDB.FRESH AS H2 MODE APPEND");
		Assertions.assertEquals(List.of("1|Alice", "2|Bob"), rows("FRESH"));
	}

	@Test
	void aTableSourceAppendsWithoutAnyKey() throws Exception {
		dump("DUMP CUSTOMERS TO TARGETDB.COPY AS H2 MODE APPEND");
		dump("DUMP CUSTOMERS TO TARGETDB.COPY AS H2 MODE APPEND");

		Assertions.assertEquals(4, rows("COPY").size(), "plain APPEND never checks for duplicates");
	}

	@Test
	void theLastDisplayedResultAppendsToo() throws Exception {
		existingOrderReport();
		CommandInterpreter interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, sourceDb);
		sourceDb.setCmdLineConsole(console);
		CommandTestSupport.shareVault(interpreter, vault());
		interpreter.setQuery("SELECT ID, NAME AS CUSTOMER, 9 AS AMOUNT FROM CUSTOMERS ORDER BY ID");
		interpreter.executeCommand();

		interpreter.setQuery("DUMP / TO TARGETDB.ORDER_REPORT AS H2 MODE APPEND");
		interpreter.executeCommand();

		Assertions.assertEquals(List.of("1|Alice|9.00", "1|Existing|1.00", "2|Bob|9.00"), rows("ORDER_REPORT"), console.getOutput());
	}

	@Test
	void appendKeyStillFiltersOnItsKey() throws Exception {
		dump("DUMP ORDERS TO TARGETDB.KEYED AS H2 MODE APPEND KEY(ID)");
		sourceDb.executeUpdateQuery("INSERT INTO ORDERS VALUES (103, 2, 5.00)");
		dump("DUMP ORDERS TO TARGETDB.KEYED AS H2 MODE APPEND KEY(ID)");

		Assertions.assertTrue(console.getOutput().contains("(appended - delta since the last pull)"), console.getOutput());
		Assertions.assertEquals(4, rows("KEYED").size());
	}

	// =================================================================================================
	// Spreadsheets: the same rule, against the tab's header row
	// =================================================================================================

	private static final String JOIN = "(SELECT o.ID AS ID, c.NAME AS CUSTOMER, o.AMOUNT AS AMOUNT FROM ORDERS o JOIN CUSTOMERS c ON c.ID = o.CUSTOMER_ID)";

	private List<String> xlsxRows(String tab) throws Exception {
		List<String> rows = new ArrayList<>();
		DataFormatter formatter = new DataFormatter(Locale.ROOT);
		try (FileInputStream in = new FileInputStream(tmp.resolve("REPORT.xlsx").toFile()); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Sheet sheet = wb.getSheet(tab);
			for (int r = 0; r <= sheet.getLastRowNum(); r++) {
				Row row = sheet.getRow(r);
				List<String> cells = new ArrayList<>();
				for (int c = 0; c < 3; c++) {
					cells.add(row.getCell(c) == null ? "" : formatter.formatCellValue(row.getCell(c)));
				}
				rows.add(String.join("|", cells));
			}
		}
		return rows;
	}

	/** The Rows cell of the first QUERIES data row. */
	private String queriesRowCount() throws Exception {
		try (FileInputStream in = new FileInputStream(tmp.resolve("REPORT.xlsx").toFile()); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			return new DataFormatter(Locale.ROOT).formatCellValue(wb.getSheet("QUERIES").getRow(1).getCell(4));
		}
	}

	@Test
	void xlsxAppendAddsRowsBelowTheExistingTab() throws Exception {
		dump("DUMP (SELECT ID, NAME AS CUSTOMER, 0 AS AMOUNT FROM CUSTOMERS WHERE ID = 1) TO REPORT.ORDERS AS XLSX");

		dump("DUMP " + JOIN + " TO REPORT.ORDERS AS XLSX MODE APPEND");

		Assertions.assertTrue(console.getOutput().contains("3 row(s) exported to tab 'ORDERS' of "), console.getOutput());
		Assertions.assertEquals(List.of("ID|CUSTOMER|AMOUNT", "1|Alice|0", "100|Alice|50", "101|Bob|75.5", "102|Alice|"), xlsxRows("ORDERS"));
		Assertions.assertEquals("ORDERS|4", xlsxRows("QUERIES").get(1).split("\\|")[0] + "|" + queriesRowCount(), "QUERIES records the rows the worksheet holds");
	}

	@Test
	void xlsxAppendRefusesAMismatchAndLeavesTheFileUnchanged() throws Exception {
		dump("DUMP (SELECT ID, NAME AS CUSTOMER, 0 AS AMOUNT FROM CUSTOMERS) TO REPORT.ORDERS AS XLSX");
		Path file = tmp.resolve("REPORT.xlsx");
		byte[] before = Files.readAllBytes(file);

		BroadSQLException count = Assertions.assertThrows(BroadSQLException.class,
				() -> dump("DUMP (SELECT ID, NAME AS CUSTOMER FROM CUSTOMERS) TO REPORT.ORDERS AS XLSX MODE APPEND"));
		Assertions.assertTrue(count.getMessage().contains("destination has 3 columns but query returns 2"), count.getMessage());
		BroadSQLException order = Assertions.assertThrows(BroadSQLException.class,
				() -> dump("DUMP (SELECT NAME AS CUSTOMER, ID, 0 AS AMOUNT FROM CUSTOMERS) TO REPORT.ORDERS AS XLSX MODE APPEND"));
		Assertions.assertTrue(order.getMessage().contains("column 1 differs"), order.getMessage());

		Assertions.assertArrayEquals(before, Files.readAllBytes(file), "a refused APPEND must not rewrite the file");
	}

	@Test
	void xlsxAppendToAMissingTabCreatesItAndKeepsOtherTabs() throws Exception {
		dump("DUMP CUSTOMERS TO REPORT.CUSTOMERS AS XLSX");

		dump("DUMP " + JOIN + " TO REPORT.ORDERS AS XLSX MODE APPEND");

		Assertions.assertEquals(4, xlsxRows("ORDERS").size());
		Assertions.assertEquals(3, xlsxRows("CUSTOMERS").size(), "another tab is left untouched");
	}

	@Test
	void odsAppendFollowsTheSameRule() throws Exception {
		dump("DUMP (SELECT ID, NAME AS CUSTOMER, 0 AS AMOUNT FROM CUSTOMERS WHERE ID = 1) TO REPORT.ORDERS AS ODS");
		Path file = tmp.resolve("REPORT.ods");

		dump("DUMP " + JOIN + " TO REPORT.ORDERS AS ODS MODE APPEND");
		com.github.miachm.sods.Sheet sheet = new SpreadSheet(file.toFile()).getSheet("ORDERS");
		Assertions.assertEquals(5, sheet.getMaxRows(), "header + 1 existing + 3 appended");
		Assertions.assertEquals("Bob", String.valueOf(sheet.getRange(3, 1).getValue()));
		Assertions.assertNull(sheet.getRange(4, 2).getValue(), "a NULL is an empty cell");

		byte[] before = Files.readAllBytes(file);
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> dump("DUMP (SELECT ID, NAME AS CUSTOMER_NAME, 0 AS AMOUNT FROM CUSTOMERS) TO REPORT.ORDERS AS ODS MODE APPEND"));
		Assertions.assertTrue(ex.getMessage().contains("column 2 differs.\nQuery: CUSTOMER_NAME\nDestination: CUSTOMER"), ex.getMessage());
		Assertions.assertArrayEquals(before, Files.readAllBytes(file), "a refused APPEND must not rewrite the file");
	}

	@Test
	void spreadsheetAppendOfZeroRowsAddsNothing() throws Exception {
		dump("DUMP (SELECT ID, NAME AS CUSTOMER, 0 AS AMOUNT FROM CUSTOMERS) TO REPORT.ORDERS AS XLSX");

		dump("DUMP (SELECT ID, NAME AS CUSTOMER, 0 AS AMOUNT FROM CUSTOMERS WHERE 1 = 0) TO REPORT.ORDERS AS XLSX MODE APPEND");

		Assertions.assertTrue(console.getOutput().contains("0 row(s) exported"), console.getOutput());
		Assertions.assertEquals(3, xlsxRows("ORDERS").size());
	}
}
