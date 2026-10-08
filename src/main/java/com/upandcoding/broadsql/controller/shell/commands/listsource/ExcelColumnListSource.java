package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * {@code <@excel:<path>:<sheet>:<column>>}: reads one named column, on one named sheet, from an
 * {@code .xlsx} workbook - e.g. {@code <@excel:c:\temp\customers.xlsx:Customers:CUSTOMER_ID>}
 * (docs/TODO.md, "Spreadsheet/clipboard data as query input"). Reuses the Apache POI dependency
 * already shipped for export (no new dependency for this class, unlike {@link CsvColumnListSource});
 * v1 reads {@code .xlsx} only ({@link XSSFWorkbook}, matching {@code PullToXlsxExporter}), not the
 * legacy {@code .xls} binary format.
 *
 * <p><b>Sheet/column selection</b> is by name - sheet matched exactly, falling back to case-insensitive;
 * column header matched the same way - never by column letter, so inserting/reordering spreadsheet
 * columns doesn't silently change which values are used (this was an explicit design goal from the
 * feature brief, not incidental).
 *
 * <p><b>Cell types</b>: text cells are read as-is; numeric cells are rendered as a plain integer when
 * they have no fractional part, otherwise as an exact decimal (no scientific notation, no Excel display
 * formatting like thousands separators, since that formatting is not part of the underlying value); a
 * date-formatted numeric cell is rendered as its ISO local date/time; a formula cell uses its cached
 * result (the simplest predictable option - POI does not need to recalculate it, and BroadSQL does not
 * carry a formula-evaluation engine); an error-value cell fails clearly rather than being silently
 * treated as blank or as the literal error string; a genuinely blank cell is skipped, same convention as
 * every other list source skipping a blank line/cell.
 */
public class ExcelColumnListSource implements ListSource {

	private static final String USAGE = "<@excel:...> needs the form <@excel:<path>:<sheet>:<column>>, "
			+ "e.g. <@excel:c:\\temp\\customers.xlsx:Customers:CUSTOMER_ID>";

	private final String filePath;
	private final String sheetName;
	private final String columnName;

	ExcelColumnListSource(String filePath, String sheetName, String columnName) {
		this.filePath = filePath;
		this.sheetName = sheetName;
		this.columnName = columnName;
	}

	/**
	 * Splits {@code spec} (the token content after the {@code excel:} prefix) at its last two colons -
	 * neither a sheet name (Excel forbids {@code :} in one) nor, in practice, a column header contains a
	 * colon, while a Windows path may (its drive letter), so the split is anchored from the right:
	 * {@code c:\temp\customers.xlsx:Customers:CUSTOMER_ID} splits into path, sheet, column correctly
	 * regardless of how many colons the path itself contains.
	 */
	static ExcelColumnListSource parse(String spec) throws BroadSQLException {
		int lastColon = spec.lastIndexOf(':');
		if (lastColon <= 0 || lastColon == spec.length() - 1) {
			throw new BroadSQLException(USAGE);
		}
		String column = spec.substring(lastColon + 1).trim();
		String rest = spec.substring(0, lastColon);

		int secondLastColon = rest.lastIndexOf(':');
		if (secondLastColon <= 0 || secondLastColon == rest.length() - 1) {
			throw new BroadSQLException(USAGE);
		}
		String sheet = rest.substring(secondLastColon + 1).trim();
		String path = rest.substring(0, secondLastColon).trim();

		if (path.isEmpty() || sheet.isEmpty() || column.isEmpty()) {
			throw new BroadSQLException(USAGE);
		}
		return new ExcelColumnListSource(path, sheet, column);
	}

	@Override
	public List<String> values() throws BroadSQLException {
		File file = new File(filePath);
		if (!file.isFile()) {
			throw new BroadSQLException("File " + filePath + " not found");
		}

		List<String> values = new ArrayList<>();
		try (FileInputStream fis = new FileInputStream(file);
				XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

			Sheet sheet = resolveSheet(workbook);
			Row headerRow = sheet.getRow(sheet.getFirstRowNum());
			if (headerRow == null) {
				throw new BroadSQLException("Sheet '" + sheetName + "' in " + filePath + " has no header row");
			}
			int columnIndex = resolveColumnIndex(headerRow);

			for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
				Row row = sheet.getRow(r);
				if (row == null) {
					continue;
				}
				Cell cell = row.getCell(columnIndex);
				if (cell == null) {
					continue;
				}
				String value = cellToString(cell);
				if (value != null) {
					String trimmed = value.trim();
					if (!trimmed.isEmpty()) {
						values.add(trimmed);
					}
				}
			}
		} catch (EncryptedDocumentException e) {
			throw new BroadSQLException("Unable to open " + filePath + " as an Excel workbook: " + e.getLocalizedMessage());
		} catch (IOException e) {
			throw new BroadSQLException("Error reading Excel file " + filePath + ": " + e.getLocalizedMessage());
		} catch (RuntimeException re) {
			// Normalizes an unexpected POI runtime failure (a corrupt/non-XLSX file, in particular) into
			// the exception type the rest of the app expects to catch and display cleanly.
			throw new BroadSQLException("Unable to read " + filePath + " as an Excel workbook: " + re.getLocalizedMessage());
		}
		return values;
	}

	private Sheet resolveSheet(XSSFWorkbook workbook) throws BroadSQLException {
		Sheet sheet = workbook.getSheet(sheetName);
		if (sheet != null) {
			return sheet;
		}
		for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
			if (workbook.getSheetName(i).equalsIgnoreCase(sheetName)) {
				return workbook.getSheetAt(i);
			}
		}
		throw new BroadSQLException("Sheet '" + sheetName + "' not found in " + filePath);
	}

	private int resolveColumnIndex(Row headerRow) throws BroadSQLException {
		DataFormatter formatter = new DataFormatter();
		Integer found = null;
		Integer caseInsensitiveFound = null;
		List<String> seen = new ArrayList<>();
		for (Cell cell : headerRow) {
			String label = formatter.formatCellValue(cell).trim();
			seen.add(label);
			if (label.equals(columnName)) {
				if (found != null) {
					throw new BroadSQLException("Column header '" + columnName + "' appears more than once in sheet '"
							+ sheetName + "' of " + filePath + " - ambiguous.");
				}
				found = cell.getColumnIndex();
			} else if (caseInsensitiveFound == null && label.equalsIgnoreCase(columnName)) {
				caseInsensitiveFound = cell.getColumnIndex();
			}
		}
		if (found != null) {
			return found;
		}
		if (caseInsensitiveFound != null) {
			return caseInsensitiveFound;
		}
		throw new BroadSQLException("Column '" + columnName + "' not found in sheet '" + sheetName + "' of " + filePath
				+ " - available columns: " + String.join(", ", seen));
	}

	private String cellToString(Cell cell) throws BroadSQLException {
		CellType type = cell.getCellType();
		if (type == CellType.FORMULA) {
			type = cell.getCachedFormulaResultType();
		}
		switch (type) {
			case BLANK:
				return null;
			case STRING:
				return cell.getStringCellValue();
			case BOOLEAN:
				return Boolean.toString(cell.getBooleanCellValue());
			case NUMERIC:
				if (DateUtil.isCellDateFormatted(cell)) {
					return cell.getLocalDateTimeCellValue().toString();
				}
				double d = cell.getNumericCellValue();
				if (d == Math.rint(d) && !Double.isInfinite(d)) {
					return Long.toString((long) d);
				}
				return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
			case ERROR:
				throw new BroadSQLException("Cell " + cell.getAddress() + " in sheet '" + sheetName + "' of " + filePath
						+ " contains a formula/value error - cannot use it as a list value.");
			default:
				return null;
		}
	}
}
