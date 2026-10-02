package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.export.TabularResultCapture;

/**
 * SPRINT 2309T (#161/#163/#165), end to end through a real {@link CommandInterpreter} and a real H2
 * database: {@code DUMP /}, {@code DUMP (<query>)}, {@code DUMP <table> AS ...}, {@code DUMP LIB}, their
 * composition with CSV/TEXT/JSON/XLSX, {@code PULL} as a real alias of {@code DUMP}, and the compatibility
 * of {@code DUMP <table>}, {@code LIB RUN}, {@code EXPORT} and {@code SET SEPARATOR}. Writer details
 * (quoting, escaping) are covered once here on the CSV/TEXT writer, not repeated for every source.
 */
class TestCommandDumpExport {

	private static final String CRLF = "\r\n";

	@TempDir
	Path tmp;

	private Path out;
	private Path library;
	private ConsoleSettings settings;
	private DatabaseConnection db;
	private CapturingShellConsole console;
	private CommandInterpreter interpreter;

	@BeforeEach
	void setUp() throws Exception {
		out = Files.createDirectories(tmp.resolve("out"));
		library = Files.createDirectories(tmp.resolve("scripts"));
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(out.toString() + File.separator);
		settings.setScriptsLibraryPath(library.toString());
		settings.setExtractDefaultFileName("results.xlsx");
		settings.setDefaultFileFormatRaw("CSV");
		settings.setCsvSeparatorRaw("SEMICOLON");
		db = TestDatabaseConnections.connectInMemory(settings,
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50), COUNTRY VARCHAR(2), AMOUNT DECIMAL(10,2))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice', 'FR', 10.50), (2, 'Bob', 'UK', 20.00), (3, 'Chloe', 'FR', NULL)");
		console = new CapturingShellConsole();
		db.setCmdLineConsole(console);
		interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, db);
		LastQueryResultHolder.set(null);
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
		LastQueryResultHolder.set(null);
	}

	private void run(String line) throws BroadSQLException {
		interpreter.setQuery(line);
		interpreter.executeCommand();
	}

	private String read(String fileName) throws IOException {
		File file = out.resolve(fileName).toFile();
		Assertions.assertTrue(file.exists(), "expected " + fileName + " to be written; console: " + console.getOutput());
		String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		return content.startsWith("﻿") ? content.substring(1) : content;
	}

	/** Joins lines the way the CSV/TEXT writers end them. */
	private static String rows(String... lines) {
		return String.join(CRLF, lines) + CRLF;
	}

	private static int lineCount(String content) {
		return content.split(CRLF, -1).length - 1;
	}

	private void lib(String name, String text) throws IOException {
		Path file = library.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}

	private String[] outputFiles() {
		String[] names = out.toFile().list();
		return names == null ? new String[0] : names;
	}

	private void setMaxRowsOnScreen(int max) {
		settings.setMaxRowsOnScreen(max);
		db.setMaxRowsOnScreen(max);
	}

	// =================================================================================================
	// #161 - DUMP /
	// =================================================================================================

	@Test
	void dumpSlashExportsThePreviousResultWithoutRerunningIt() throws Exception {
		run("SELECT ID, NAME FROM CUSTOMER WHERE COUNTRY = 'FR' ORDER BY ID");
		// Change the data after the SELECT: a re-run would see Zoe, the displayed result did not.
		db.executeUpdateQuery("INSERT INTO CUSTOMER VALUES (4, 'Zoe', 'FR', 1)");

		run("DUMP /");

		Assertions.assertEquals(rows("ID;NAME", "1;Alice", "3;Chloe"), read("results.csv"));
		Assertions.assertTrue(console.getOutput().contains("2 row(s) exported to"), console.getOutput());
	}

	@Test
	void dumpSlashUsesTheConfiguredDefaultFormatWhenAsIsOmitted() throws Exception {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(out.toString() + File.separator);
		settings.setExtractDefaultFileName("results.xlsx");
		settings.setDefaultFileFormatRaw("XLSX");
		interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, db);
		run("SELECT ID, NAME FROM CUSTOMER ORDER BY ID");

		run("DUMP /");

		try (XSSFWorkbook workbook = new XSSFWorkbook(new FileInputStream(out.resolve("results.xlsx").toFile()))) {
			Sheet data = workbook.getSheet("DATA");
			Assertions.assertNotNull(data, "default worksheet DATA");
			Assertions.assertEquals("Alice", data.getRow(1).getCell(1).getStringCellValue());
			Assertions.assertEquals(1.0, data.getRow(1).getCell(0).getNumericCellValue(), "a dumped number stays a number");
		}
	}

	@Test
	void dumpSlashWithExplicitDestinationAndFormats() throws Exception {
		run("SELECT ID, NAME, AMOUNT FROM CUSTOMER ORDER BY ID");

		run("DUMP / TO customers AS CSV");
		run("DUMP / TO customers AS TEXT");
		run("DUMP / TO customers AS JSON");
		run("DUMP / TO customers.FR AS XLSX");

		Assertions.assertEquals(rows("ID;NAME;AMOUNT", "1;Alice;10.50", "2;Bob;20.00", "3;Chloe;"), read("customers.csv"));
		Assertions.assertEquals(rows("ID\tNAME\tAMOUNT", "1\tAlice\t10.50", "2\tBob\t20.00", "3\tChloe\t"), read("customers.txt"));
		String json = read("customers.json");
		Assertions.assertTrue(json.startsWith("[\n") && json.trim().endsWith("]"), json);
		Assertions.assertTrue(json.contains("{ \"ID\": 1, \"NAME\": \"Alice\", \"AMOUNT\": 10.50 }"), json);
		Assertions.assertTrue(json.contains("\"AMOUNT\": null"), json);
		try (XSSFWorkbook workbook = new XSSFWorkbook(new FileInputStream(out.resolve("customers.xlsx").toFile()))) {
			Assertions.assertNotNull(workbook.getSheet("FR"), "explicit worksheet overrides DATA");
		}
	}

	@Test
	void dumpSlashWithoutAnyPreviousResultFailsAndWritesNothing() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP /"));
		Assertions.assertTrue(ex.getMessage().contains("No previous tabular result is available to dump"), ex.getMessage());
		Assertions.assertEquals(0, outputFiles().length, "no empty file may be created");
	}

	@Test
	void nonTabularCommandsDoNotReplaceTheResultDumpSlashExports() throws Exception {
		run("SELECT ID, NAME FROM CUSTOMER WHERE ID = 2");
		run("UPDATE CUSTOMER SET NAME = 'Robert' WHERE ID = 2");
		run("HELP");
		run("SET LIST OFF");

		run("DUMP / TO last AS CSV");

		Assertions.assertEquals(rows("ID;NAME", "2;Bob"), read("last.csv"));
	}

	@Test
	void aTruncatedDisplayIsRefusedRatherThanExportedPartially() throws Exception {
		setMaxRowsOnScreen(2);
		run("SELECT ID FROM CUSTOMER ORDER BY ID");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP / TO partial AS CSV"));
		Assertions.assertTrue(ex.getMessage().contains("MaxRowsOnScreen"), ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("DUMP (<query>)"), ex.getMessage());
		Assertions.assertEquals(0, outputFiles().length);
	}

	@Test
	void aCustomMaxRowsOnScreenIsTheOnlyLimit() throws Exception {
		setMaxRowsOnScreen(7);
		run("SELECT X FROM SYSTEM_RANGE(1, 7)");
		run("DUMP / TO seven AS CSV");
		Assertions.assertEquals(8, lineCount(read("seven.csv")), "header + 7 rows: a result exactly at the limit is complete");

		run("SELECT X FROM SYSTEM_RANGE(1, 8)");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP / TO eight AS CSV"));
		Assertions.assertTrue(ex.getMessage().contains("MaxRowsOnScreen limit (7 rows)"), ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("DUMP (<query>)"), ex.getMessage());
		Assertions.assertFalse(out.resolve("eight.csv").toFile().exists(), "no partial export");
	}

	@Test
	void thereIsNoHardCodedRowThresholdBeyondMaxRowsOnScreen() throws Exception {
		setMaxRowsOnScreen(10000);
		run("SELECT X FROM SYSTEM_RANGE(1, 6000)");
		run("DUMP / TO big AS CSV");
		Assertions.assertEquals(6001, lineCount(read("big.csv")), "6000 rows, more than the former 5000 threshold");

		setMaxRowsOnScreen(0); // 0 = display everything
		run("SELECT X FROM SYSTEM_RANGE(1, 5500)");
		run("DUMP / TO everything AS TEXT");
		Assertions.assertEquals(5501, lineCount(read("everything.txt")));
	}

	@Test
	void aSnapshotWithoutTypesIsNotExportable() {
		LastQueryResultHolder.set(new LastQueryResult(java.util.List.of("ID"), java.util.List.<String[]>of(new String[] { "1" }), 1, null));
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP / TO x AS CSV"));
		Assertions.assertTrue(ex.getMessage().contains("No previous tabular result"), ex.getMessage());
	}

	// =================================================================================================
	// #163 - PULL is a real alias of DUMP
	// =================================================================================================

	@Test
	void pullSlashExportsTheDisplayedResultWithoutRerunningIt() throws Exception {
		run("SELECT ID, NAME FROM CUSTOMER WHERE COUNTRY = 'FR' ORDER BY ID");
		db.executeUpdateQuery("INSERT INTO CUSTOMER VALUES (4, 'Zoe', 'FR', 1)");

		run("PULL / TO pulled AS CSV");
		run("DUMP / TO dumped AS CSV");

		Assertions.assertEquals(rows("ID;NAME", "1;Alice", "3;Chloe"), read("pulled.csv"), "Zoe was inserted after the SELECT: no re-run");
		Assertions.assertEquals(read("dumped.csv"), read("pulled.csv"));
	}

	@Test
	void pullSlashRefusesATruncatedOrMissingResultLikeDump() throws Exception {
		setMaxRowsOnScreen(2);
		run("SELECT ID FROM CUSTOMER ORDER BY ID");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("PULL / TO partial AS CSV"));
		Assertions.assertTrue(ex.getMessage().contains("MaxRowsOnScreen"), ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("PULL (<query>)"), ex.getMessage());
		Assertions.assertEquals(0, outputFiles().length);

		LastQueryResultHolder.set(null);
		BroadSQLException none = Assertions.assertThrows(BroadSQLException.class, () -> run("PULL /"));
		Assertions.assertTrue(none.getMessage().contains("No previous tabular result"), none.getMessage());
	}

	@Test
	void pullAcceptsTheSimplifiedFormsWithDefaultDestination() throws Exception {
		run("PULL CUSTOMER AS CSV");
		Assertions.assertTrue(read("CUSTOMER.csv").startsWith("ID;NAME;COUNTRY;AMOUNT" + CRLF));

		run("SELECT NAME FROM CUSTOMER WHERE ID = 1");
		run("PULL /");
		Assertions.assertEquals(rows("NAME", "Alice"), read("results.csv"), "no TO, no AS: DefaultExtFileName and DefaultFileFormat");

		lib("sales.sql", "SELECT ID FROM CUSTOMER WHERE ID = 2;");
		run("PULL LIB sales.sql AS TEXT");
		Assertions.assertEquals(rows("ID", "2"), read("sales.txt"));
	}

	@Test
	void representativeLegacyPullSyntaxStillWorks() throws Exception {
		run("PULL CUSTOMER TO legacy_csv AS CSV");
		run("PULL (SELECT ID, NAME FROM CUSTOMER WHERE COUNTRY = 'FR') TO legacy_txt AS TXT");
		run("PULL CUSTOMER TO REPORT.CUSTOMERS AS XLSX");
		run("PULL CUSTOMER TO legacy_json AS JSON");

		Assertions.assertTrue(read("legacy_csv.csv").startsWith("ID;NAME"));
		Assertions.assertEquals(rows("ID\tNAME", "1\tAlice", "3\tChloe"), read("legacy_txt.txt"));
		Assertions.assertTrue(read("legacy_json.json").contains("\"NAME\": \"Bob\""));
		try (XSSFWorkbook workbook = new XSSFWorkbook(new FileInputStream(out.resolve("REPORT.xlsx").toFile()))) {
			Assertions.assertNotNull(workbook.getSheet("CUSTOMERS"));
		}
		Assertions.assertTrue(console.getOutput().contains("row(s) pulled into"), "PULL keeps its wording");
	}

	@Test
	void pullAndDumpProduceIdenticalFiles() throws Exception {
		run("PULL (SELECT * FROM CUSTOMER ORDER BY ID) TO pulled AS CSV");
		run("DUMP (SELECT * FROM CUSTOMER ORDER BY ID) TO dumped AS CSV");
		run("PULL CUSTOMER TO pulledj AS JSON");
		run("DUMP CUSTOMER TO dumpedj AS JSON");

		Assertions.assertEquals(read("pulled.csv"), read("dumped.csv"));
		Assertions.assertEquals(read("pulledj.json"), read("dumpedj.json"));
	}

	// =================================================================================================
	// #163 - DUMP table / (query), legacy DUMP <table> through the pipeline
	// =================================================================================================

	@Test
	void dumpTableAndQueryAcrossTextFormats() throws Exception {
		run("DUMP CUSTOMER AS CSV");
		run("DUMP CUSTOMER AS TEXT");
		run("DUMP CUSTOMER AS JSON");
		run("DUMP (SELECT NAME FROM CUSTOMER WHERE COUNTRY = 'FR' ORDER BY ID) TO fr AS CSV");
		run("DUMP (SELECT NAME FROM CUSTOMER WHERE COUNTRY = 'FR' ORDER BY ID) TO fr AS TEXT");
		run("DUMP (SELECT NAME FROM CUSTOMER WHERE COUNTRY = 'FR' ORDER BY ID) TO fr AS JSON");

		Assertions.assertTrue(read("CUSTOMER.csv").startsWith("ID;NAME;COUNTRY;AMOUNT" + CRLF));
		Assertions.assertTrue(read("CUSTOMER.txt").startsWith("ID\tNAME\tCOUNTRY\tAMOUNT" + CRLF));
		Assertions.assertTrue(read("CUSTOMER.json").contains("\"COUNTRY\": \"UK\""));
		Assertions.assertEquals(rows("NAME", "Alice", "Chloe"), read("fr.csv"));
		Assertions.assertEquals(rows("NAME", "Alice", "Chloe"), read("fr.txt"));
		Assertions.assertTrue(read("fr.json").contains("{ \"NAME\": \"Chloe\" }"));
	}

	@Test
	void dumpQueryToWorksheet() throws Exception {
		run("DUMP (SELECT * FROM CUSTOMER) TO report.DATA AS XLSX");
		run("DUMP (SELECT * FROM CUSTOMER WHERE COUNTRY = 'UK') TO report AS XLSX");
		try (XSSFWorkbook workbook = new XSSFWorkbook(new FileInputStream(out.resolve("report.xlsx").toFile()))) {
			Assertions.assertEquals(1, workbook.getSheet("DATA").getLastRowNum(), "second dump replaced DATA with its single UK row");
		}
	}

	@Test
	void legacyDumpTableKeepsItsExternalBehaviorThroughThePipeline() throws Exception {
		run("DUMP CUSTOMER");
		Assertions.assertTrue(console.getOutput().contains("3 records extracted to " + out.resolve("CUSTOMER.csv")), console.getOutput());
		// Written by the pipeline's CSV writer: the configured CsvSeparator applies (SEMICOLON here).
		Assertions.assertTrue(read("CUSTOMER.csv").startsWith("ID;NAME;COUNTRY;AMOUNT" + CRLF), read("CUSTOMER.csv"));
	}

	@Test
	void legacyDumpTableToXlsxNamesTheWorksheetAfterTheTable() throws Exception {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(out.toString() + File.separator);
		settings.setDefaultFileFormatRaw("XLSX");
		interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, db);

		run("DUMP CUSTOMER");

		try (XSSFWorkbook workbook = new XSSFWorkbook(new FileInputStream(out.resolve("CUSTOMER.xlsx").toFile()))) {
			Assertions.assertNotNull(workbook.getSheet("CUSTOMER"), "worksheet named after the table, as before");
			Assertions.assertNotNull(workbook.getSheet("QUERIES"), "the pipeline's XLSX writer produced the file");
			Assertions.assertEquals(3, workbook.getSheet("CUSTOMER").getLastRowNum());
		}
	}

	@Test
	void legacyDumpTableBeyondMaxRowXlsxWritesTabSeparatedText() throws Exception {
		settings.setMaxRowXlsx(2);
		run("DUMP CUSTOMER");
		Assertions.assertTrue(read("CUSTOMER.txt").startsWith("ID\tNAME\tCOUNTRY\tAMOUNT" + CRLF));
		Assertions.assertTrue(console.getOutput().contains("3 records extracted to"), console.getOutput());
	}

	@Test
	void legacyDumpTableOfAMissingTableIsAnErrorAndWritesNothing() throws Exception {
		run("DUMP NOSUCHTABLE");
		// The message of the exact table resolver shared with DESCR and SHOW PK/FK/REFERENCES/INDEXES (the stray space
		// of the former "Table name 'NOSUCHTABLE ' does not exist" is gone)
		Assertions.assertTrue(console.getOutput().contains("Table name 'NOSUCHTABLE' does not exist"), console.getOutput());
		Assertions.assertEquals(0, outputFiles().length);
	}

	// =================================================================================================
	// #163 - DUMP LIB
	// =================================================================================================

	@Test
	void dumpLibExportsTheScriptsSingleSelect() throws Exception {
		lib("sales.sql", "SELECT ID, NAME FROM CUSTOMER ORDER BY ID;");

		run("DUMP LIB sales.sql");

		Assertions.assertEquals(rows("ID;NAME", "1;Alice", "2;Bob", "3;Chloe"), read("sales.csv"), "default format, named after the script");
	}

	@Test
	void dumpLibExportsTheFinalResultAfterPreparationStatements() throws Exception {
		lib("reports/sales.sql", "CREATE LOCAL TEMPORARY TABLE X (CUSTOMER VARCHAR(50), AMOUNT DECIMAL(10,2));\n"
				+ "INSERT INTO X SELECT NAME, AMOUNT FROM CUSTOMER WHERE AMOUNT IS NOT NULL;\n"
				+ "SELECT COUNT(*) AS N FROM CUSTOMER;\n"
				+ "SELECT CUSTOMER, SUM(AMOUNT) AS TOTAL FROM X GROUP BY CUSTOMER ORDER BY CUSTOMER;\n"
				+ "DROP TABLE X;");

		run("DUMP LIB reports/sales.sql TO sales AS CSV");

		Assertions.assertEquals(rows("CUSTOMER;TOTAL", "Alice;10.50", "Bob;20.00"), read("sales.csv"),
				"the last tabular result, even though a DROP follows it");
	}

	@Test
	void dumpLibAcrossFormatsAndWorksheets() throws Exception {
		lib("sales.sql", "SELECT NAME, AMOUNT FROM CUSTOMER WHERE ID = 1;");

		run("DUMP LIB sales.sql TO s AS TEXT");
		run("DUMP LIB sales.sql TO s AS JSON");
		run("DUMP LIB sales.sql TO s AS XLSX");
		run("DUMP LIB sales.sql TO t.MONTHLY AS XLSX");

		Assertions.assertEquals(rows("NAME\tAMOUNT", "Alice\t10.50"), read("s.txt"));
		Assertions.assertTrue(read("s.json").contains("{ \"NAME\": \"Alice\", \"AMOUNT\": 10.50 }"));
		try (XSSFWorkbook a = new XSSFWorkbook(new FileInputStream(out.resolve("s.xlsx").toFile()));
				XSSFWorkbook b = new XSSFWorkbook(new FileInputStream(out.resolve("t.xlsx").toFile()))) {
			Assertions.assertNotNull(a.getSheet("DATA"), "default worksheet");
			Assertions.assertNotNull(b.getSheet("MONTHLY"), "explicit worksheet");
			Assertions.assertEquals(10.5, a.getSheet("DATA").getRow(1).getCell(1).getNumericCellValue(), 0.0001);
		}
	}

	@Test
	void dumpLibWithoutATabularResultFailsAndWritesNothing() throws Exception {
		lib("prep.sql", "UPDATE CUSTOMER SET NAME = NAME;");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP LIB prep.sql AS CSV"));
		Assertions.assertTrue(ex.getMessage().contains("Library script 'prep.sql' produced no exportable tabular result"), ex.getMessage());
		Assertions.assertEquals(0, outputFiles().length);
	}

	@Test
	void dumpLibOfAMissingScriptFails() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP LIB nope.sql AS CSV"));
		Assertions.assertTrue(ex.getMessage().toLowerCase().contains("nope.sql"), ex.getMessage());
		Assertions.assertEquals(0, outputFiles().length);
	}

	@Test
	void dumpLibOfAFailingScriptFailsAndWritesNothing() throws Exception {
		lib("broken.sql", "SELECT ID FROM CUSTOMER;\nSELECT * FROM NO_SUCH_TABLE;");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP LIB broken.sql AS CSV"));
		// SPRINT 0110A: only a SUCCESS run exports (spec section 19.1)
		Assertions.assertTrue(ex.getMessage().contains("not exported: the script finished with status COMPLETED_WITH_ERRORS"), ex.getMessage());
		Assertions.assertEquals(0, outputFiles().length, "an earlier SELECT of a failing script is never exported");
	}

	@Test
	void dumpLibNeverExportsAnUnrelatedPreviousResultAndLeavesItInPlace() throws Exception {
		run("SELECT NAME FROM CUSTOMER WHERE ID = 2");
		LastQueryResult before = LastQueryResultHolder.get();
		lib("prep.sql", "UPDATE CUSTOMER SET NAME = NAME;");
		lib("sales.sql", "SELECT ID FROM CUSTOMER WHERE ID = 3;");

		Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP LIB prep.sql AS CSV"));
		Assertions.assertEquals(0, outputFiles().length, "the session's previous SELECT must not be exported by DUMP LIB");

		run("DUMP LIB sales.sql AS CSV");
		Assertions.assertEquals(rows("ID", "3"), read("sales.csv"));
		Assertions.assertSame(before, LastQueryResultHolder.get(), "DUMP LIB must not replace what DUMP / exports");
		Assertions.assertNull(TabularResultCapture.current(), "the capture scope is always closed");

		run("DUMP / TO still AS CSV");
		Assertions.assertEquals(rows("NAME", "Bob"), read("still.csv"));
	}

	@Test
	void libRunIsUnchangedDisplaysAndBecomesThePreviousResult() throws Exception {
		lib("sales.sql", "SELECT ID, NAME FROM CUSTOMER WHERE ID = 1;");

		run("LIB RUN sales.sql");

		Assertions.assertTrue(console.getOutput().contains("Alice"), "LIB RUN still displays its result");
		Assertions.assertFalse(console.getOutput().contains("captured for DUMP LIB"), console.getOutput());
		Assertions.assertEquals(0, outputFiles().length);
		run("DUMP / TO fromlib AS CSV");
		Assertions.assertEquals(rows("ID;NAME", "1;Alice"), read("fromlib.csv"));
	}

	// =================================================================================================
	// #165 - CSV / TEXT / JSON rules
	// =================================================================================================

	@Test
	void csvQuotesAndEscapesEveryDifficultValueInBothConventions() throws Exception {
		db.executeUpdateQuery("CREATE TABLE T (ID INT, V VARCHAR(50))");
		db.executeUpdateQuery("INSERT INTO T VALUES (1, 'a,b'), (2, 'a;b'), (3, 'say \"hi\"'), (4, 'two' || CHAR(10) || 'lines'), (5, ''), (6, NULL)");

		run("DUMP (SELECT * FROM T ORDER BY ID) TO semi AS CSV");
		settings.setCsvSeparatorRaw("COMMA");
		run("DUMP (SELECT * FROM T ORDER BY ID) TO comma AS CSV");

		Assertions.assertEquals(rows("ID;V", "1;a,b", "2;\"a;b\"", "3;\"say \"\"hi\"\"\"", "4;\"two\nlines\"", "5;", "6;"), read("semi.csv"));
		Assertions.assertEquals(rows("ID,V", "1,\"a,b\"", "2,a;b", "3,\"say \"\"hi\"\"\"", "4,\"two\nlines\"", "5,", "6,"), read("comma.csv"));
		run("DUMP (SELECT * FROM T ORDER BY ID) TO comma2 AS CSV");
		Assertions.assertEquals(read("comma.csv"), read("comma2.csv"), "deterministic");
	}

	@Test
	void anUnsupportedCsvSeparatorFailsTheCsvExportOnly() throws Exception {
		settings.setCsvSeparatorRaw("|");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP CUSTOMER AS CSV"));
		Assertions.assertTrue(ex.getMessage().contains("CsvSeparator"), ex.getMessage());
		Assertions.assertEquals(0, outputFiles().length);
		run("DUMP CUSTOMER AS TEXT");
		Assertions.assertTrue(read("CUSTOMER.txt").startsWith("ID\tNAME"));
	}

	@Test
	void textAndJsonIgnoreCsvSeparatorAndLegacySetSeparator() throws Exception {
		run("DUMP CUSTOMER TO before AS TEXT");
		run("DUMP CUSTOMER TO before AS JSON");
		settings.setCsvSeparatorRaw("SEMICOLON");
		run("SET SEPARATOR SEMICOLON");
		Assertions.assertEquals(';', settings.getDefaultSeparator(), "legacy SET SEPARATOR still runs");

		run("DUMP CUSTOMER TO after AS TEXT");
		run("DUMP CUSTOMER TO after AS JSON");

		Assertions.assertTrue(read("after.txt").startsWith("ID\tNAME\tCOUNTRY\tAMOUNT" + CRLF), "TEXT is always tab-separated");
		Assertions.assertEquals(read("before.txt"), read("after.txt"));
		Assertions.assertEquals(read("before.json"), read("after.json"));
	}

	@Test
	void csvIgnoresLegacySetSeparator() throws Exception {
		run("SET SEPARATOR PIPE");
		run("DUMP CUSTOMER AS CSV");
		Assertions.assertTrue(read("CUSTOMER.csv").startsWith("ID;NAME;COUNTRY;AMOUNT" + CRLF));
	}

	// =================================================================================================
	// Legacy EXPORT keeps running
	// =================================================================================================

	@Test
	void legacyExportWorkflowStillRuns() throws Exception {
		run("EXPORT " + out.resolve("legacy.txt"));
		run("SELECT ID, NAME FROM CUSTOMER WHERE ID = 1");
		run("EXPORT");

		String content = read("legacy.txt");
		Assertions.assertTrue(content.contains("Alice"), content);
	}

	// =================================================================================================
	// The typed snapshot behind DUMP / never breaks the display of a SELECT
	// =================================================================================================

	private static final String NON_FINITE = "SELECT CAST('NaN' AS DECFLOAT) AS N, CAST('Infinity' AS DECFLOAT) AS I, 7 AS OK";

	@Test
	void aSelectOfDisplayableNonFiniteNumbersSucceeds() throws Exception {
		run(NON_FINITE);

		Assertions.assertFalse(console.wasErrorReported(), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("NaN") && console.getOutput().contains("Infinity"), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("1 rows fetched"), console.getOutput());
		Assertions.assertNotNull(LastQueryResultHolder.get(), "the displayed result is still the previous result");
	}

	@Test
	void aSnapshotConversionFailureDoesNotRollBackPendingWork() throws Exception {
		ConsoleSettings manual = TestDatabaseConnections.defaultConsoleSettings();
		manual.setAutoCommit(false);
		manual.setExtractFolderName(out.toString() + File.separator);
		DatabaseConnection pendingDb = TestDatabaseConnections.connectInMemory(manual, "CREATE TABLE T (ID INT)");
		try {
			pendingDb.setCmdLineConsole(console);
			interpreter = CommandTestSupport.createFullCommandInterpreter(manual, console, pendingDb);
			run("INSERT INTO T VALUES (1)");

			run(NON_FINITE);

			Assertions.assertFalse(console.wasErrorReported(), console.getOutput());
			Assertions.assertEquals(1, pendingDb.getNumberOfRecords("T"), "the pending insert was rolled back by a display-only problem");
		} finally {
			pendingDb.rollback();
			TestDatabaseConnections.close(pendingDb);
		}
	}

	@Test
	void dumpSlashRefusesAValueThatWasDisplayedButCannotBeExportedWithItsType() throws Exception {
		run(NON_FINITE);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP / TO nonfinite AS CSV"));

		Assertions.assertTrue(ex.getMessage().contains("column 'N'") && ex.getMessage().contains("'NaN'") && ex.getMessage().contains("Nothing was exported"),
				ex.getMessage());
		Assertions.assertFalse(out.resolve("nonfinite.csv").toFile().exists(), "nothing may be written");
	}

	@Test
	void ordinaryNumbersAndDatesStillDumpFromTheSnapshot() throws Exception {
		run("SELECT CAST(12.50 AS DECIMAL(10,2)) AS AMOUNT, DATE '2024-01-15' AS D, TIME '10:00:00.123' AS T, "
				+ "TIMESTAMP '2024-01-15 10:00:00.123456' AS TS, TRUE AS B");

		run("DUMP / TO typed AS CSV");

		Assertions.assertEquals(rows("AMOUNT;D;T;TS;B", "12.50;2024-01-15;10:00:00.123;2024-01-15 10:00:00.123456;true"), read("typed.csv"));
	}
}
