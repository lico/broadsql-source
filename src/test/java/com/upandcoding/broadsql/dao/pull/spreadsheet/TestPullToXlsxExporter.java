package com.upandcoding.broadsql.dao.pull.spreadsheet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * Direct tests of {@link PullToXlsxExporter#writeTab}, bypassing {@code CommandPull} - covers the
 * behaviors documented in docs/PULL_TO_SPREADSHEET.md that are easiest to assert precisely against a
 * hand-built {@link ResultSet}: the output format's per-type cell handling, the info tab's content
 * (query, date, source connection, row count), the {@code QUERIES} name-collision guard, the
 * foreign-file "QUERIES" sheet guard, and creating the info tab in a foreign file that has other tabs but
 * no info tab yet. The three headline cases (new file, add a tab, replace a tab) are also covered here
 * directly, in addition to {@code TestCommandPullSpreadsheet}'s end-to-end coverage through the actual
 * {@code PULL} command.
 */
class TestPullToXlsxExporter {

	private static final String CONNECTION_ID = "TESTDB";

	private DatabaseConnection db;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50), BALANCE DECIMAL(10,2), ACTIVE BOOLEAN, JOINED DATE)",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice', 100.50, TRUE, '2024-01-15'), (2, 'Bob', NULL, FALSE, '2024-02-20')",
				"CREATE TABLE HASBLOB (ID INT PRIMARY KEY, DATA BLOB)",
				"CREATE TABLE PRECISE (ID INT PRIMARY KEY, AMOUNT DECIMAL(30,10))",
				"INSERT INTO PRECISE VALUES (1, 123456789012345.123456789)");
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private ResultSet query(String sql) throws SQLException {
		Connection conn = db.getDirectConnection();
		Statement stmt = conn.createStatement();
		return stmt.executeQuery(sql);
	}

	private int writeTab(String filePath, String tabName, String sql) throws Exception {
		return new PullToXlsxExporter().writeTab(filePath, tabName, sql, CONNECTION_ID, query(sql));
	}

	// ---------------------------------------------------------------------------- Headline cases

	@Test
	void writesANewTabIntoANewFile(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.xlsx").toString();

		int rowsWritten = writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER");

		Assertions.assertEquals(2, rowsWritten);
		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Assertions.assertEquals(2, wb.getNumberOfSheets(), "expected the data tab plus the QUERIES info tab");
			Assertions.assertNotNull(wb.getSheet("CUSTOMERS"));
			Assertions.assertNotNull(wb.getSheet("QUERIES"));
		}
	}

	@Test
	void addsASecondTabWithoutTouchingTheFirst(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.xlsx").toString();

		writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER");
		writeTab(filePath, "SINGLE", "SELECT * FROM CUSTOMER WHERE ID = 1");

		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Assertions.assertEquals(3, wb.getNumberOfSheets(), "expected CUSTOMERS, SINGLE, and QUERIES");
			Assertions.assertEquals(2, wb.getSheet("CUSTOMERS").getLastRowNum(), "CUSTOMERS must be untouched by adding SINGLE");
			Assertions.assertEquals(1, wb.getSheet("SINGLE").getLastRowNum());
			Assertions.assertEquals(2, wb.getSheet("QUERIES").getLastRowNum(), "one info row per data tab");
		}
	}

	@Test
	void replacesAnExistingTabAndUpsertsTheInfoRow(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.xlsx").toString();

		writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER WHERE ID = 1");
		writeTab(filePath, "OTHER", "SELECT * FROM CUSTOMER");
		int rowsWritten = writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER");

		Assertions.assertEquals(2, rowsWritten);
		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Assertions.assertEquals(3, wb.getNumberOfSheets(), "no duplicate CUSTOMERS tab must be created");
			Assertions.assertEquals(2, wb.getSheet("CUSTOMERS").getLastRowNum(), "CUSTOMERS must reflect the replaced, larger result");
			Assertions.assertEquals(2, wb.getSheet("OTHER").getLastRowNum(), "OTHER must be untouched by replacing CUSTOMERS");
			Sheet info = wb.getSheet("QUERIES");
			Assertions.assertEquals(2, info.getLastRowNum(), "expected still exactly 2 info rows (upsert, not append)");
			Row customersRow = findInfoRow(info, "CUSTOMERS");
			Assertions.assertEquals(2, (int) customersRow.getCell(4).getNumericCellValue(),
					"expected the CUSTOMERS info row's Rows column to reflect the replacement");
		}
	}

	// -------------------------------------------------------------------- Info tab content (issue 1)

	@Test
	void infoTabRecordsTheQueryDateConnectionAndRowCountForEachTab(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.xlsx").toString();

		writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER");

		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Sheet info = wb.getSheet("QUERIES");
			Row header = info.getRow(0);
			Assertions.assertEquals("Tab", header.getCell(0).getStringCellValue());
			Assertions.assertEquals("Query", header.getCell(1).getStringCellValue());
			Assertions.assertEquals("Date", header.getCell(2).getStringCellValue());
			Assertions.assertEquals("Connection", header.getCell(3).getStringCellValue());
			Assertions.assertEquals("Rows", header.getCell(4).getStringCellValue());

			Row dataRow = findInfoRow(info, "CUSTOMERS");
			Assertions.assertEquals("CUSTOMERS", dataRow.getCell(0).getStringCellValue());
			Assertions.assertEquals("SELECT * FROM CUSTOMER", dataRow.getCell(1).getStringCellValue());
			Assertions.assertFalse(dataRow.getCell(2).getStringCellValue().isBlank(), "expected a non-blank date");
			Assertions.assertEquals(CONNECTION_ID, dataRow.getCell(3).getStringCellValue(),
					"expected the source database connection to be recorded in the info tab");
			Assertions.assertEquals(2, (int) dataRow.getCell(4).getNumericCellValue());
		}
	}

	// ---------------------------------------------------------- Nobody can erase the info tab (issue 2)

	@Test
	void rejectsQueriesAsADataTabNameEvenWhenCalledDirectly(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.xlsx").toString();
		writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER"); // establish a real info tab first

		ResultSet rs = query("SELECT * FROM CUSTOMER WHERE ID = 1");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> new PullToXlsxExporter().writeTab(filePath, "QUERIES", "SELECT * FROM CUSTOMER WHERE ID = 1", CONNECTION_ID, rs));
		Assertions.assertTrue(ex.getMessage().contains("reserved"), "got: " + ex.getMessage());

		// the real info tab (and everything else) must be exactly as it was before the rejected attempt
		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Assertions.assertEquals(2, wb.getNumberOfSheets());
			Assertions.assertEquals(1, wb.getSheet("QUERIES").getLastRowNum(), "the info tab must still have exactly its one real row");
		}
	}

	@Test
	void rejectsQueriesAsADataTabNameCaseInsensitively(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.xlsx").toString();
		ResultSet rs = query("SELECT * FROM CUSTOMER WHERE ID = 1");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> new PullToXlsxExporter().writeTab(filePath, "qUERIES", "SELECT * FROM CUSTOMER WHERE ID = 1", CONNECTION_ID, rs));
		Assertions.assertTrue(ex.getMessage().contains("reserved"), "got: " + ex.getMessage());
		Assertions.assertFalse(new File(filePath).isFile(), "the file must not even be created");
	}

	// ------------------------------------------------------- Foreign file with no info tab yet

	@Test
	void createsTheInfoTabInAForeignFileThatHasOtherTabsButNoInfoTabYet(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("FOREIGN.xlsx").toString();
		try (XSSFWorkbook wb = new XSSFWorkbook()) {
			Sheet unrelated = wb.createSheet("Unrelated");
			unrelated.createRow(0).createCell(0).setCellValue("hand-built content");
			try (FileOutputStream fos = new FileOutputStream(filePath)) {
				wb.write(fos);
			}
		}

		int rowsWritten = writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER");

		Assertions.assertEquals(2, rowsWritten);
		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Assertions.assertEquals(3, wb.getNumberOfSheets(), "expected Unrelated, CUSTOMERS, and a freshly created QUERIES tab");
			Assertions.assertEquals("hand-built content", wb.getSheet("Unrelated").getRow(0).getCell(0).getStringCellValue(),
					"the pre-existing Unrelated tab must be untouched");
			Sheet info = wb.getSheet("QUERIES");
			Assertions.assertNotNull(info, "expected the info tab to be created automatically");
			Assertions.assertEquals(1, info.getLastRowNum(), "expected exactly one info row, for the one tab just pulled");
		}
	}

	// ------------------------------------------------------------------------ Output format: XLSX

	@Test
	void writesEachSupportedColumnTypeWithTheCorrectValue(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.xlsx").toString();

		writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER WHERE ID = 1");

		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Row alice = wb.getSheet("CUSTOMERS").getRow(1);
			Assertions.assertEquals(1.0, alice.getCell(0).getNumericCellValue(), "ID (INT)");
			Assertions.assertEquals("Alice", alice.getCell(1).getStringCellValue(), "NAME (VARCHAR)");
			Assertions.assertEquals(100.50, alice.getCell(2).getNumericCellValue(), 0.001, "BALANCE (DECIMAL)");
			Assertions.assertTrue(alice.getCell(3).getBooleanCellValue(), "ACTIVE (BOOLEAN)");
			LocalDate joined = alice.getCell(4).getLocalDateTimeCellValue().toLocalDate();
			Assertions.assertEquals(LocalDate.of(2024, 1, 15), joined, "JOINED (DATE)");
		}
	}

	@Test
	void decimalThatDoesNotRoundTripThroughDoubleIsWrittenAsExactText(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.xlsx").toString();

		writeTab(filePath, "PRECISE", "SELECT * FROM PRECISE");

		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Cell amountCell = wb.getSheet("PRECISE").getRow(1).getCell(1);
			Assertions.assertEquals(CellType.STRING, amountCell.getCellType(),
					"a DECIMAL that would lose precision as a double must fall back to exact text in Excel");
			Assertions.assertEquals("123456789012345.1234567890", amountCell.getStringCellValue());
		}
	}

	@Test
	void nullDecimalProducesABlankCellNotZero(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.xlsx").toString();

		writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER ORDER BY ID");

		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Sheet data = wb.getSheet("CUSTOMERS");
			Row bobRow = data.getRow(2); // header=0, Alice=1, Bob=2
			Cell balanceCell = bobRow.getCell(2);
			Assertions.assertTrue(balanceCell == null || balanceCell.getCellType() == CellType.BLANK,
					"expected Bob's NULL balance to be a blank cell, not a numeric 0");
		}
	}

	@Test
	void rejectsABlobColumnBeforeTouchingTheFile(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.xlsx").toString();
		ResultSet rs = query("SELECT * FROM HASBLOB");

		Assertions.assertThrows(BroadSQLException.class,
				() -> new PullToXlsxExporter().writeTab(filePath, "T", "SELECT * FROM HASBLOB", CONNECTION_ID, rs));

		Assertions.assertFalse(new File(filePath).isFile(), "the file must not be created when a column type is rejected");
	}

	@Test
	void refusesAForeignQueriesSheetAndLeavesTheFileUntouched(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("FOREIGN.xlsx").toString();
		try (XSSFWorkbook wb = new XSSFWorkbook()) {
			Sheet foreignQueries = wb.createSheet("QUERIES");
			foreignQueries.createRow(0).createCell(0).setCellValue("SomeOtherColumn");
			wb.createSheet("Unrelated");
			try (FileOutputStream fos = new FileOutputStream(filePath)) {
				wb.write(fos);
			}
		}

		ResultSet rs = query("SELECT * FROM CUSTOMER WHERE ID = 1");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> new PullToXlsxExporter().writeTab(filePath, "NEWDATA", "SELECT * FROM CUSTOMER WHERE ID = 1", CONNECTION_ID, rs));
		Assertions.assertTrue(ex.getMessage().contains("not created by PULL"), "got: " + ex.getMessage());

		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Assertions.assertEquals(2, wb.getNumberOfSheets(), "the foreign file must be left exactly as it was");
			Assertions.assertNotNull(wb.getSheet("Unrelated"), "the unrelated tab must survive the refused PULL");
			Assertions.assertNull(wb.getSheet("NEWDATA"), "no new tab must have been written");
		}
	}

	private Row findInfoRow(Sheet info, String tabName) {
		for (int r = 1; r <= info.getLastRowNum(); r++) {
			Row row = info.getRow(r);
			if (row != null && row.getCell(0) != null && tabName.equals(row.getCell(0).getStringCellValue())) {
				return row;
			}
		}
		throw new AssertionError("no info row found for tab '" + tabName + "'");
	}
}
