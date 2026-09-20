package com.upandcoding.broadsql.dao.pull.spreadsheet;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.miachm.sods.Sheet;
import com.github.miachm.sods.SpreadSheet;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * Direct tests of {@link PullToOdsExporter#writeTab}, mirroring {@link TestPullToXlsxExporter} against
 * the SODS API instead of POI - see that class's Javadoc for the shared rationale, not repeated here.
 */
class TestPullToOdsExporter {

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
		return new PullToOdsExporter().writeTab(filePath, tabName, sql, CONNECTION_ID, query(sql));
	}

	// ---------------------------------------------------------------------------- Headline cases

	@Test
	void writesANewTabIntoANewFile(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.ods").toString();

		int rowsWritten = writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER");

		Assertions.assertEquals(2, rowsWritten);
		SpreadSheet spreadSheet = new SpreadSheet(new File(filePath));
		Assertions.assertEquals(2, spreadSheet.getNumSheets(), "expected the data tab plus the QUERIES info tab");
		Assertions.assertNotNull(spreadSheet.getSheet("CUSTOMERS"));
		Assertions.assertNotNull(spreadSheet.getSheet("QUERIES"));
	}

	@Test
	void addsASecondTabWithoutTouchingTheFirst(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.ods").toString();

		writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER");
		writeTab(filePath, "SINGLE", "SELECT * FROM CUSTOMER WHERE ID = 1");

		SpreadSheet spreadSheet = new SpreadSheet(new File(filePath));
		Assertions.assertEquals(3, spreadSheet.getNumSheets(), "expected CUSTOMERS, SINGLE, and QUERIES");
		Assertions.assertEquals(3, spreadSheet.getSheet("CUSTOMERS").getMaxRows(), "CUSTOMERS must be untouched by adding SINGLE");
		Assertions.assertEquals(2, spreadSheet.getSheet("SINGLE").getMaxRows());
		Assertions.assertEquals(3, spreadSheet.getSheet("QUERIES").getMaxRows(), "header + one info row per data tab");
	}

	@Test
	void replacesAnExistingTabAndUpsertsTheInfoRow(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.ods").toString();

		writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER WHERE ID = 1");
		writeTab(filePath, "OTHER", "SELECT * FROM CUSTOMER");
		int rowsWritten = writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER");

		Assertions.assertEquals(2, rowsWritten);
		SpreadSheet spreadSheet = new SpreadSheet(new File(filePath));
		Assertions.assertEquals(3, spreadSheet.getNumSheets(), "no duplicate CUSTOMERS tab must be created");
		Assertions.assertEquals(3, spreadSheet.getSheet("CUSTOMERS").getMaxRows(), "CUSTOMERS must reflect the replaced, larger result");
		Assertions.assertEquals(3, spreadSheet.getSheet("OTHER").getMaxRows(), "OTHER must be untouched by replacing CUSTOMERS");
		Sheet info = spreadSheet.getSheet("QUERIES");
		Assertions.assertEquals(3, info.getMaxRows(), "expected still exactly header + 2 info rows (upsert, not append)");
		int customersRow = findInfoRow(info, "CUSTOMERS");
		Assertions.assertEquals(2, ((Number) info.getRange(customersRow, 4).getValue()).intValue(),
				"expected the CUSTOMERS info row's Rows column to reflect the replacement");
	}

	// -------------------------------------------------------------------- Info tab content (issue 1)

	@Test
	void infoTabRecordsTheQueryDateConnectionAndRowCountForEachTab(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.ods").toString();

		writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER");

		SpreadSheet spreadSheet = new SpreadSheet(new File(filePath));
		Sheet info = spreadSheet.getSheet("QUERIES");
		Assertions.assertEquals("Tab", info.getRange(0, 0).getValue());
		Assertions.assertEquals("Query", info.getRange(0, 1).getValue());
		Assertions.assertEquals("Date", info.getRange(0, 2).getValue());
		Assertions.assertEquals("Connection", info.getRange(0, 3).getValue());
		Assertions.assertEquals("Rows", info.getRange(0, 4).getValue());

		int dataRow = findInfoRow(info, "CUSTOMERS");
		Assertions.assertEquals("CUSTOMERS", info.getRange(dataRow, 0).getValue());
		Assertions.assertEquals("SELECT * FROM CUSTOMER", info.getRange(dataRow, 1).getValue());
		Object date = info.getRange(dataRow, 2).getValue();
		Assertions.assertTrue(date != null && !date.toString().isBlank(), "expected a non-blank date");
		Assertions.assertEquals(CONNECTION_ID, info.getRange(dataRow, 3).getValue(),
				"expected the source database connection to be recorded in the info tab");
		Assertions.assertEquals(2, ((Number) info.getRange(dataRow, 4).getValue()).intValue());
	}

	// ---------------------------------------------------------- Nobody can erase the info tab (issue 2)

	@Test
	void rejectsQueriesAsADataTabNameEvenWhenCalledDirectly(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.ods").toString();
		writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER"); // establish a real info tab first

		ResultSet rs = query("SELECT * FROM CUSTOMER WHERE ID = 1");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> new PullToOdsExporter().writeTab(filePath, "QUERIES", "SELECT * FROM CUSTOMER WHERE ID = 1", CONNECTION_ID, rs));
		Assertions.assertTrue(ex.getMessage().contains("reserved"), "got: " + ex.getMessage());

		SpreadSheet spreadSheet = new SpreadSheet(new File(filePath));
		Assertions.assertEquals(2, spreadSheet.getNumSheets());
		Assertions.assertEquals(2, spreadSheet.getSheet("QUERIES").getMaxRows(), "the info tab must still have exactly its one real row (+header)");
	}

	@Test
	void rejectsQueriesAsADataTabNameCaseInsensitively(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.ods").toString();
		ResultSet rs = query("SELECT * FROM CUSTOMER WHERE ID = 1");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> new PullToOdsExporter().writeTab(filePath, "qUERIES", "SELECT * FROM CUSTOMER WHERE ID = 1", CONNECTION_ID, rs));
		Assertions.assertTrue(ex.getMessage().contains("reserved"), "got: " + ex.getMessage());
		Assertions.assertFalse(new File(filePath).isFile(), "the file must not even be created");
	}

	// ------------------------------------------------------- Foreign file with no info tab yet

	@Test
	void createsTheInfoTabInAForeignFileThatHasOtherTabsButNoInfoTabYet(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("FOREIGN.ods").toString();
		SpreadSheet foreignSpreadSheet = new SpreadSheet();
		Sheet unrelated = new Sheet("Unrelated", 1, 1);
		unrelated.getRange(0, 0).setValue("hand-built content");
		foreignSpreadSheet.appendSheet(unrelated);
		foreignSpreadSheet.save(new File(filePath));

		int rowsWritten = writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER");

		Assertions.assertEquals(2, rowsWritten);
		SpreadSheet spreadSheet = new SpreadSheet(new File(filePath));
		Assertions.assertEquals(3, spreadSheet.getNumSheets(), "expected Unrelated, CUSTOMERS, and a freshly created QUERIES tab");
		Assertions.assertEquals("hand-built content", spreadSheet.getSheet("Unrelated").getRange(0, 0).getValue(),
				"the pre-existing Unrelated tab must be untouched");
		Sheet info = spreadSheet.getSheet("QUERIES");
		Assertions.assertNotNull(info, "expected the info tab to be created automatically");
		Assertions.assertEquals(2, info.getMaxRows(), "expected header + exactly one info row, for the one tab just pulled");
	}

	// -------------------------------------------------------------------------- Output format: ODS

	@Test
	void writesEachSupportedColumnTypeWithTheCorrectValue(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.ods").toString();

		writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER WHERE ID = 1");

		SpreadSheet spreadSheet = new SpreadSheet(new File(filePath));
		Sheet data = spreadSheet.getSheet("CUSTOMERS");
		Assertions.assertEquals(1L, ((Number) data.getRange(1, 0).getValue()).longValue(), "ID (INT)");
		Assertions.assertEquals("Alice", data.getRange(1, 1).getValue(), "NAME (VARCHAR)");
		// SODS's own reader always deserializes a numeric cell as a Double (confirmed from its source,
		// OfficeValueType.FLOAT.read()), regardless of what Java type originally wrote it - so this
		// round-trips as a Number here, not a BigDecimal (see the dedicated precision test below for what
		// actually reaches the ODF XML on write, which is where the exactness claim in
		// PullToOdsExporter's Javadoc actually applies).
		Assertions.assertEquals(100.50, ((Number) data.getRange(1, 2).getValue()).doubleValue(), 0.001, "BALANCE (DECIMAL)");
		Assertions.assertEquals(Boolean.TRUE, data.getRange(1, 3).getValue(), "ACTIVE (BOOLEAN)");
		Assertions.assertEquals(LocalDate.of(2024, 1, 15), data.getRange(1, 4).getValue(), "JOINED (DATE)");
	}

	/**
	 * SODS's own reader collapses every numeric cell back to a {@code Double} (see the comment on
	 * {@link #writesEachSupportedColumnTypeWithTheCorrectValue}), so this test does not rely on it to
	 * verify precision - it inspects the {@code .ods} file's own {@code content.xml} directly, which is
	 * the actual claim in {@code PullToOdsExporter}'s Javadoc: a {@code BigDecimal} is serialized via its
	 * own exact {@code toString()}, never rounded through a {@code double} first, unlike Excel's
	 * fallback-to-text behavior for a value that wouldn't round-trip through {@code double}.
	 */
	@Test
	void decimalIsWrittenToTheFileWithItsExactValueNoDoubleRoundTrip(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.ods").toString();

		writeTab(filePath, "PRECISE", "SELECT * FROM PRECISE");

		String contentXml = readZipEntryAsString(filePath, "content.xml");
		Assertions.assertTrue(contentXml.contains("office:value=\"123456789012345.1234567890\""),
				"expected the exact decimal text in the ODF XML, got:\n" + contentXml);
	}

	private String readZipEntryAsString(String zipFilePath, String entryName) throws Exception {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFilePath)) {
			java.util.zip.ZipEntry entry = zip.getEntry(entryName);
			Assertions.assertNotNull(entry, "expected a '" + entryName + "' entry in " + zipFilePath);
			try (java.io.InputStream in = zip.getInputStream(entry)) {
				return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			}
		}
	}

	@Test
	void nullDecimalProducesABlankCellNotZero(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.ods").toString();

		writeTab(filePath, "CUSTOMERS", "SELECT * FROM CUSTOMER ORDER BY ID");

		SpreadSheet spreadSheet = new SpreadSheet(new File(filePath));
		Sheet data = spreadSheet.getSheet("CUSTOMERS");
		Object balance = data.getRange(2, 2).getValue(); // header=0, Alice=1, Bob=2
		Assertions.assertNull(balance, "expected Bob's NULL balance to be a blank cell, not a numeric 0");
	}

	@Test
	void rejectsABlobColumnBeforeTouchingTheFile(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.ods").toString();
		ResultSet rs = query("SELECT * FROM HASBLOB");

		Assertions.assertThrows(BroadSQLException.class,
				() -> new PullToOdsExporter().writeTab(filePath, "T", "SELECT * FROM HASBLOB", CONNECTION_ID, rs));

		Assertions.assertFalse(new File(filePath).isFile(), "the file must not be created when a column type is rejected");
	}

	@Test
	void refusesAForeignQueriesSheetAndLeavesTheFileUntouched(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("FOREIGN.ods").toString();
		SpreadSheet foreignSpreadSheet = new SpreadSheet();
		Sheet foreignQueries = new Sheet("QUERIES", 1, 1);
		foreignQueries.getRange(0, 0).setValue("SomeOtherColumn");
		foreignSpreadSheet.appendSheet(foreignQueries);
		foreignSpreadSheet.appendSheet(new Sheet("Unrelated", 1, 1));
		foreignSpreadSheet.save(new File(filePath));

		ResultSet rs = query("SELECT * FROM CUSTOMER WHERE ID = 1");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> new PullToOdsExporter().writeTab(filePath, "NEWDATA", "SELECT * FROM CUSTOMER WHERE ID = 1", CONNECTION_ID, rs));
		Assertions.assertTrue(ex.getMessage().contains("not created by PULL"), "got: " + ex.getMessage());

		SpreadSheet spreadSheet = new SpreadSheet(new File(filePath));
		Assertions.assertEquals(2, spreadSheet.getNumSheets(), "the foreign file must be left exactly as it was");
		Assertions.assertNotNull(spreadSheet.getSheet("Unrelated"), "the unrelated tab must survive the refused PULL");
		Assertions.assertNull(spreadSheet.getSheet("NEWDATA"), "no new tab must have been written");
	}

	private int findInfoRow(Sheet info, String tabName) {
		for (int r = 1; r < info.getMaxRows(); r++) {
			Object value = info.getRange(r, 0).getValue();
			if (tabName.equals(value)) {
				return r;
			}
		}
		throw new AssertionError("no info row found for tab '" + tabName + "'");
	}
}
