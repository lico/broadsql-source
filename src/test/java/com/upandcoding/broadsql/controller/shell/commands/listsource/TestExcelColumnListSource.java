package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

class TestExcelColumnListSource {

	private String writeWorkbook(Path tempDir, String sheetName, String[] header, Object[][] rows) throws Exception {
		String filePath = tempDir.resolve("customers.xlsx").toString();
		try (XSSFWorkbook wb = new XSSFWorkbook()) {
			Sheet sheet = wb.createSheet(sheetName);
			Row headerRow = sheet.createRow(0);
			for (int i = 0; i < header.length; i++) {
				headerRow.createCell(i).setCellValue(header[i]);
			}
			for (int r = 0; r < rows.length; r++) {
				Row row = sheet.createRow(r + 1);
				for (int c = 0; c < rows[r].length; c++) {
					Object value = rows[r][c];
					Cell cell = row.createCell(c);
					if (value == null) {
						// leave blank
					} else if (value instanceof String s) {
						cell.setCellValue(s);
					} else if (value instanceof Double d) {
						cell.setCellValue(d);
					} else if (value instanceof LocalDate d) {
						CreationHelper helper = wb.getCreationHelper();
						CellStyle dateStyle = wb.createCellStyle();
						dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-MM-dd"));
						cell.setCellStyle(dateStyle);
						cell.setCellValue(d);
					} else if (value instanceof FormulaSpec f) {
						cell.setCellFormula(f.formula);
						cell.setCellValue(f.cachedValue);
					} else if (value instanceof ErrorSpec) {
						cell.setCellErrorValue(FormulaError.VALUE.getCode());
					}
				}
			}
			try (FileOutputStream fos = new FileOutputStream(filePath)) {
				wb.write(fos);
			}
		}
		return filePath;
	}

	private record FormulaSpec(String formula, double cachedValue) {
	}

	private static final class ErrorSpec {
	}

	@Test
	void readsANamedColumnBySheetAndHeader(@TempDir Path tempDir) throws Exception {
		String filePath = writeWorkbook(tempDir, "Customers", new String[] { "CUSTOMER_ID", "NAME" },
				new Object[][] { { "C123", "John" }, { "C456", "Maria" } });

		List<String> values = ExcelColumnListSource.parse(filePath + ":Customers:CUSTOMER_ID").values();

		Assertions.assertEquals(List.of("C123", "C456"), values);
	}

	@Test
	void matchesSheetAndColumnCaseInsensitivelyWhenNoExactMatch(@TempDir Path tempDir) throws Exception {
		String filePath = writeWorkbook(tempDir, "Customers", new String[] { "customer_id" }, new Object[][] { { "C1" } });

		List<String> values = ExcelColumnListSource.parse(filePath + ":CUSTOMERS:CUSTOMER_ID").values();

		Assertions.assertEquals(List.of("C1"), values);
	}

	@Test
	void skipsBlankCells(@TempDir Path tempDir) throws Exception {
		String filePath = writeWorkbook(tempDir, "Sheet1", new String[] { "ID" }, new Object[][] { { "A" }, { null }, { "B" } });

		Assertions.assertEquals(List.of("A", "B"), ExcelColumnListSource.parse(filePath + ":Sheet1:ID").values());
	}

	@Test
	void rendersWholeNumbersWithoutADecimalPointOrThousandsSeparator(@TempDir Path tempDir) throws Exception {
		String filePath = writeWorkbook(tempDir, "Sheet1", new String[] { "ID" }, new Object[][] { { 1234.0 }, { 5.0 } });

		Assertions.assertEquals(List.of("1234", "5"), ExcelColumnListSource.parse(filePath + ":Sheet1:ID").values());
	}

	@Test
	void rendersADateFormattedCellAsIsoLocalDate(@TempDir Path tempDir) throws Exception {
		String filePath = writeWorkbook(tempDir, "Sheet1", new String[] { "JOINED" }, new Object[][] { { LocalDate.of(2026, 3, 5) } });

		List<String> values = ExcelColumnListSource.parse(filePath + ":Sheet1:JOINED").values();

		Assertions.assertEquals(1, values.size());
		Assertions.assertTrue(values.get(0).startsWith("2026-03-05"), "got: " + values.get(0));
	}

	@Test
	void usesTheCachedFormulaResultRatherThanEvaluating(@TempDir Path tempDir) throws Exception {
		String filePath = writeWorkbook(tempDir, "Sheet1", new String[] { "ID" }, new Object[][] { { new FormulaSpec("1+1", 2.0) } });

		Assertions.assertEquals(List.of("2"), ExcelColumnListSource.parse(filePath + ":Sheet1:ID").values());
	}

	@Test
	void formulaErrorCellFailsClearlyRatherThanBeingSilentlyIncluded(@TempDir Path tempDir) throws Exception {
		String filePath = writeWorkbook(tempDir, "Sheet1", new String[] { "ID" }, new Object[][] { { new ErrorSpec() } });

		Assertions.assertThrows(BroadSQLException.class, () -> ExcelColumnListSource.parse(filePath + ":Sheet1:ID").values());
	}

	@Test
	void duplicateHeaderIsRejectedAsAmbiguous(@TempDir Path tempDir) throws Exception {
		String filePath = writeWorkbook(tempDir, "Sheet1", new String[] { "ID", "ID" }, new Object[][] { { "A", "B" } });

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ExcelColumnListSource.parse(filePath + ":Sheet1:ID").values());
		Assertions.assertTrue(ex.getMessage().toLowerCase().contains("ambiguous"), "got: " + ex.getMessage());
	}

	@Test
	void missingColumnFailsClearly(@TempDir Path tempDir) throws Exception {
		String filePath = writeWorkbook(tempDir, "Sheet1", new String[] { "ID" }, new Object[][] { { "A" } });

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ExcelColumnListSource.parse(filePath + ":Sheet1:MISSING").values());
		Assertions.assertTrue(ex.getMessage().contains("MISSING"), "got: " + ex.getMessage());
	}

	@Test
	void missingSheetFailsClearly(@TempDir Path tempDir) throws Exception {
		String filePath = writeWorkbook(tempDir, "Sheet1", new String[] { "ID" }, new Object[][] { { "A" } });

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ExcelColumnListSource.parse(filePath + ":OtherSheet:ID").values());
		Assertions.assertTrue(ex.getMessage().contains("OtherSheet"), "got: " + ex.getMessage());
	}

	@Test
	void missingFileFailsClearly() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ExcelColumnListSource.parse("does-not-exist-xyz.xlsx:Sheet1:ID").values());
		Assertions.assertTrue(ex.getMessage().contains("not found"), "got: " + ex.getMessage());
	}

	@Test
	void parsesAWindowsPathWithADriveLetterColonCorrectly() {
		// The path's own drive-letter colon must not be mistaken for the path/sheet/column separators.
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ExcelColumnListSource.parse("c:\\temp\\customers.xlsx:Customers:CUSTOMER_ID").values());
		Assertions.assertTrue(ex.getMessage().contains("c:\\temp\\customers.xlsx"),
				"expected the full path (with its drive letter) preserved, got: " + ex.getMessage());
	}
}
