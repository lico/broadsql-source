package com.upandcoding.broadsql.dao.extractors;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFSheet;
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
 * Covers the presentation improvements added to {@link QueryExtractorToExcel2007} (AutoFilter, frozen
 * header, estimated column widths) and the NULL-vs-binary-placeholder correctness fix - see
 * docs/TECHNICAL_CHANGE.md. Reopens the produced workbook with POI and asserts directly against it,
 * per CLAUDE.md's "verify empirically" convention, rather than only reasoning about the code.
 */
class TestQueryExtractorToExcel2007 {

	private DatabaseConnection db;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(500))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice'), (2, '" + "X".repeat(200) + "')",
				// VARBINARY, not BLOB: QueryExtractorToExcel2007's binary-placeholder branch handles
				// BINARY/VARBINARY/LONGVARBINARY/OTHER/DATALINK - BLOB falls into the generic text case
				// instead (see class Javadoc), so a BLOB column would not exercise the fix under test here.
				"CREATE TABLE HASBLOB (ID INT PRIMARY KEY, DATA VARBINARY(16))",
				"INSERT INTO HASBLOB VALUES (1, NULL), (2, X'0102')");
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

	@Test
	void appliesAutoFilterOverTheFullHeaderAndDataRange(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("out.xlsx").toString();
		new QueryExtractorToExcel2007().extractToExcel2007(db.getPlatform(), null, "SELECT * FROM CUSTOMER", query("SELECT * FROM CUSTOMER"), filePath);

		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			XSSFSheet dataSheet = wb.getSheetAt(0);
			Assertions.assertNotNull(dataSheet.getCTWorksheet().getAutoFilter(), "expected an AutoFilter on the data sheet");
			String ref = dataSheet.getCTWorksheet().getAutoFilter().getRef();
			Assertions.assertEquals("A1:B3", ref, "AutoFilter must cover header row (1) plus both data rows (2,3)");

			XSSFSheet querySheet = wb.getSheet("Query");
			Assertions.assertNull(querySheet.getCTWorksheet().getAutoFilter(), "the 'Query' recap sheet must not get an AutoFilter");
		}
	}

	@Test
	void freezesTheHeaderRow(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("out.xlsx").toString();
		new QueryExtractorToExcel2007().extractToExcel2007(db.getPlatform(), null, "SELECT * FROM CUSTOMER", query("SELECT * FROM CUSTOMER"), filePath);

		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Sheet dataSheet = wb.getSheetAt(0);
			Assertions.assertNotNull(dataSheet.getPaneInformation(), "expected a freeze pane");
			Assertions.assertEquals(1, dataSheet.getPaneInformation().getHorizontalSplitTopRow(), "expected row 1 (0-based) to be the first visible/scrollable row");
			Assertions.assertTrue(dataSheet.getPaneInformation().isFreezePane());
		}
	}

	@Test
	void appliesAutoFilterEvenOnAnEmptyResultSet(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("out.xlsx").toString();
		new QueryExtractorToExcel2007().extractToExcel2007(db.getPlatform(), null, "SELECT * FROM CUSTOMER WHERE 1=0", query("SELECT * FROM CUSTOMER WHERE 1=0"), filePath);

		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			XSSFSheet dataSheet = wb.getSheetAt(0);
			Assertions.assertEquals("A1:B1", dataSheet.getCTWorksheet().getAutoFilter().getRef(), "header-only range for an empty result set");
		}
	}

	@Test
	void capsColumnWidthForLongTextButNotForTheShortHeader(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("out.xlsx").toString();
		new QueryExtractorToExcel2007().extractToExcel2007(db.getPlatform(), null, "SELECT * FROM CUSTOMER", query("SELECT * FROM CUSTOMER"), filePath);

		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Sheet dataSheet = wb.getSheetAt(0);
			int idWidth = dataSheet.getColumnWidth(0); // ID: short header, short values
			int nameWidth = dataSheet.getColumnWidth(1); // NAME: contains a 200-char value

			Assertions.assertTrue(idWidth < nameWidth, "ID column must stay narrow, NAME must widen for its long value");
			// (60 sampled chars + 2 padding) * 256 is the hard cap - never wide enough to show all 200 chars.
			Assertions.assertTrue(nameWidth <= (60 + 2) * 256, "NAME column width must be capped, not sized to the full 200-char value");
		}
	}

	@Test
	void nullBinaryValueProducesABlankCellNotThePlaceholder(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("out.xlsx").toString();
		String sql = "SELECT * FROM HASBLOB ORDER BY ID";
		new QueryExtractorToExcel2007().extractToExcel2007(db.getPlatform(), null, sql, query(sql), filePath);

		try (FileInputStream in = new FileInputStream(filePath); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Sheet dataSheet = wb.getSheetAt(0);
			// Row 1 (index 1, after the header) = ID 1, DATA NULL: the cell exists (created for column
			// alignment, like every other cell) but must carry no value - CellType.BLANK, not the
			// placeholder string.
			Cell nullDataCell = dataSheet.getRow(1).getCell(1);
			Assertions.assertEquals(CellType.BLANK, nullDataCell.getCellType(),
					"a NULL binary value must be a genuinely blank cell, not the placeholder string");
			// Row 2 (index 2) = ID 2, DATA non-NULL.
			Assertions.assertEquals("Binary content, not exported to Excel", dataSheet.getRow(2).getCell(1).getStringCellValue(),
					"a non-NULL binary value must still get the placeholder text");
		}
	}
}
