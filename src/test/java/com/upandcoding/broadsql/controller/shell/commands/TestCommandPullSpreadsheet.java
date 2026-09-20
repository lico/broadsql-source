package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;

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
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * Covers {@code CommandPull}'s {@code AS XLSX}/{@code AS ODS} destination end to end (see
 * docs/PULL_TO_SPREADSHEET.md) - the three cases the feature exists for: a brand-new file with a
 * single tab, adding a second tab to an existing file (no name collision), and re-pulling a tab whose
 * name already exists in the file (erase-and-replace, every other tab and the info tab's other rows
 * left untouched). Each case is exercised against both formats, since {@code PullToXlsxExporter} and
 * {@code PullToOdsExporter} are two independent implementations sharing no code (see that doc,
 * "Relationship to PULL ... AS H2").
 */
class TestCommandPullSpreadsheet {

	private DatabaseConnection db;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice'), (2, 'Bob')");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private CommandPull pullCommand(Path tempDir) {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		return CommandTestSupport.create(CommandPull.class, db, console, settings);
	}

	// ---------------------------------------------------------------- Case 1: new file, single tab

	@Test
	void createsANewXlsxFileWithASingleTab(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir);

		cmd.execute("PULL CUSTOMER TO REPORT.CUSTOMERS AS XLSX");

		File file = tempDir.resolve("REPORT.xlsx").toFile();
		Assertions.assertTrue(file.exists(), "expected REPORT.xlsx to be created, got console:\n" + console.getOutput());

		try (FileInputStream in = new FileInputStream(file); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Assertions.assertEquals(2, wb.getNumberOfSheets(), "expected the data tab plus the QUERIES info tab");
			Sheet data = wb.getSheet("CUSTOMERS");
			Assertions.assertNotNull(data, "expected a CUSTOMERS tab");
			Assertions.assertEquals(2, data.getLastRowNum(), "expected 2 data rows (header is row 0)");
			Sheet info = wb.getSheet("QUERIES");
			Assertions.assertNotNull(info, "expected a QUERIES info tab");
			Assertions.assertEquals(1, info.getLastRowNum(), "expected exactly one info row for the one tab pulled");
		}
	}

	@Test
	void createsANewOdsFileWithASingleTab(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir);

		cmd.execute("PULL CUSTOMER TO REPORT.CUSTOMERS AS ODS");

		File file = tempDir.resolve("REPORT.ods").toFile();
		Assertions.assertTrue(file.exists(), "expected REPORT.ods to be created, got console:\n" + console.getOutput());

		SpreadSheet spreadSheet = new SpreadSheet(file);
		Assertions.assertEquals(2, spreadSheet.getNumSheets(), "expected the data tab plus the QUERIES info tab");
		Assertions.assertNotNull(spreadSheet.getSheet("CUSTOMERS"), "expected a CUSTOMERS tab");
		Assertions.assertEquals(3, spreadSheet.getSheet("CUSTOMERS").getMaxRows(), "expected header + 2 data rows");
		Assertions.assertNotNull(spreadSheet.getSheet("QUERIES"), "expected a QUERIES info tab");
		Assertions.assertEquals(2, spreadSheet.getSheet("QUERIES").getMaxRows(), "expected header + 1 info row");
	}

	// ------------------------------------------------- Case 2: add a tab, no name collision

	@Test
	void addsASecondXlsxTabWithoutTouchingTheFirst(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir);

		cmd.execute("PULL CUSTOMER TO REPORT.CUSTOMERS AS XLSX");
		cmd.execute("PULL (SELECT ID, NAME FROM CUSTOMER WHERE ID = 1) TO REPORT.SINGLE AS XLSX");

		File file = tempDir.resolve("REPORT.xlsx").toFile();
		try (FileInputStream in = new FileInputStream(file); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Assertions.assertEquals(3, wb.getNumberOfSheets(), "expected CUSTOMERS, SINGLE, and QUERIES");
			Assertions.assertEquals(2, wb.getSheet("CUSTOMERS").getLastRowNum(), "CUSTOMERS must be untouched by adding SINGLE");
			Assertions.assertEquals(1, wb.getSheet("SINGLE").getLastRowNum(), "expected 1 data row in the new SINGLE tab");
			Assertions.assertEquals(2, wb.getSheet("QUERIES").getLastRowNum(), "expected one info row per data tab (2 tabs)");
		}
	}

	@Test
	void addsASecondOdsTabWithoutTouchingTheFirst(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir);

		cmd.execute("PULL CUSTOMER TO REPORT.CUSTOMERS AS ODS");
		cmd.execute("PULL (SELECT ID, NAME FROM CUSTOMER WHERE ID = 1) TO REPORT.SINGLE AS ODS");

		SpreadSheet spreadSheet = new SpreadSheet(tempDir.resolve("REPORT.ods").toFile());
		Assertions.assertEquals(3, spreadSheet.getNumSheets(), "expected CUSTOMERS, SINGLE, and QUERIES");
		Assertions.assertEquals(3, spreadSheet.getSheet("CUSTOMERS").getMaxRows(), "CUSTOMERS must be untouched by adding SINGLE");
		Assertions.assertEquals(2, spreadSheet.getSheet("SINGLE").getMaxRows(), "expected header + 1 data row in the new SINGLE tab");
		Assertions.assertEquals(3, spreadSheet.getSheet("QUERIES").getMaxRows(), "expected header + one info row per data tab (2 tabs)");
	}

	// --------------------------------------- Case 3: re-pull a tab whose name already exists

	@Test
	void replacesAnExistingXlsxTabLeavingOtherTabsUntouched(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir);

		cmd.execute("PULL (SELECT * FROM CUSTOMER WHERE ID = 1) TO REPORT.CUSTOMERS AS XLSX"); // 1 row
		cmd.execute("PULL CUSTOMER TO REPORT.OTHER AS XLSX"); // unrelated tab, 2 rows
		cmd.execute("PULL CUSTOMER TO REPORT.CUSTOMERS AS XLSX"); // erase and replace CUSTOMERS with 2 rows

		File file = tempDir.resolve("REPORT.xlsx").toFile();
		try (FileInputStream in = new FileInputStream(file); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Assertions.assertEquals(3, wb.getNumberOfSheets(), "expected CUSTOMERS, OTHER, and QUERIES - no duplicate tab created");
			Assertions.assertEquals(2, wb.getSheet("CUSTOMERS").getLastRowNum(), "CUSTOMERS must be replaced with the new 2-row result");
			Assertions.assertEquals(2, wb.getSheet("OTHER").getLastRowNum(), "OTHER must be untouched by replacing CUSTOMERS");
			Assertions.assertEquals(2, wb.getSheet("QUERIES").getLastRowNum(), "expected the info tab to still have 2 rows (upsert, not append) after replacing CUSTOMERS a second time");
		}
	}

	@Test
	void replacesAnExistingOdsTabLeavingOtherTabsUntouched(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir);

		cmd.execute("PULL (SELECT * FROM CUSTOMER WHERE ID = 1) TO REPORT.CUSTOMERS AS ODS"); // 1 row
		cmd.execute("PULL CUSTOMER TO REPORT.OTHER AS ODS"); // unrelated tab, 2 rows
		cmd.execute("PULL CUSTOMER TO REPORT.CUSTOMERS AS ODS"); // erase and replace CUSTOMERS with 2 rows

		SpreadSheet spreadSheet = new SpreadSheet(tempDir.resolve("REPORT.ods").toFile());
		Assertions.assertEquals(3, spreadSheet.getNumSheets(), "expected CUSTOMERS, OTHER, and QUERIES - no duplicate tab created");
		Assertions.assertEquals(3, spreadSheet.getSheet("CUSTOMERS").getMaxRows(), "CUSTOMERS must be replaced with the new header + 2 data rows");
		Assertions.assertEquals(3, spreadSheet.getSheet("OTHER").getMaxRows(), "OTHER must be untouched by replacing CUSTOMERS");
		Assertions.assertEquals(3, spreadSheet.getSheet("QUERIES").getMaxRows(), "expected the info tab to still have header + 2 rows (upsert, not append) after replacing CUSTOMERS a second time");
	}
}
