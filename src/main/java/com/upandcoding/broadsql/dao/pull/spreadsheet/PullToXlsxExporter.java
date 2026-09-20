package com.upandcoding.broadsql.dao.pull.spreadsheet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.CommandInterruptedException;
import com.upandcoding.broadsql.controller.shell.commands.CommandCancellation;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;

/**
 * Engine behind {@code PULL ... TO <name>.<tab> AS XLSX} - see docs/PULL_TO_SPREADSHEET.md. Writes a
 * query's current results into one tab of {@code <name>.xlsx}: creates the file if it doesn't exist yet,
 * or loads it whole, erases-and-replaces the named tab (leaving every other tab untouched), and upserts
 * that tab's row in the shared "QUERIES" info tab (see {@link PullSpreadsheetInfoTab}).
 *
 * <p>Standalone by design, sharing no code with {@code PullToH2Exporter}/{@code H2ColumnTypeMapper}
 * (nothing about a spreadsheet tab resembles a database table's DDL/JDBC-connection lifecycle) nor with
 * {@code QueryExtractorToExcel2007} (used by {@code EXPORT}/{@code DUMP}, deliberately left untouched -
 * see docs/PULL_TO_SPREADSHEET.md, "Relationship to EXPORT/DUMP") - though the cell-by-cell type mapping
 * below mirrors that class's, since both are translating the same JDBC {@link Types} into POI cells.
 *
 * <p>Uses a plain, in-memory {@link XSSFWorkbook} rather than the streaming {@code SXSSFWorkbook}
 * {@code QueryExtractorToExcel2007} uses for a fresh export - "preserve every other tab" (an explicit
 * product decision, see docs/PULL_TO_SPREADSHEET.md, "Foreign files") requires the whole existing
 * workbook in memory to begin with, which rules out a write-only streaming workbook. Memory use scales
 * with the size of the existing file, not just the tab being written.
 *
 * <p>Every column type is validated ({@link PullSpreadsheetTypeMapper}) against the query's own
 * {@link ResultSetMetaData} before the target file is opened or modified at all, so an unsupported
 * column type never leaves the file half-rewritten. The write itself goes to a temporary file, moved
 * over the target only once it has been written and closed successfully - so a failure partway through
 * (an I/O error, the process being killed) never leaves a half-written file in place of a good one.
 */
public class PullToXlsxExporter {

	private static final DateTimeFormatter INFO_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/**
	 * @param filePath    the {@code .xlsx} file's full path - loaded whole if it already exists, created
	 *                    otherwise
	 * @param tabName     the tab to erase and replace, already validated by
	 *                    {@link com.upandcoding.broadsql.dao.pull.PullCommandParser} - rejected again here,
	 *                    independently, if it names the reserved info tab (see
	 *                    {@link PullSpreadsheetInfoTab#SHEET_NAME}), so this method is safe to call
	 *                    directly without relying on the parser's own check
	 * @param sourceQuery the SQL query that produced {@code sourceResults}, recorded verbatim in the info
	 *                    tab's row for this tab
	 * @param sourceConnectionId the source database connection's identifier (e.g. its registered CDF
	 *                            connection name), recorded in the info tab's row for this tab; may be
	 *                            blank if unknown
	 * @param sourceResults the query's result set, positioned before the first row; consumed to
	 *                      completion (or cancellation) by this method, but not closed - the caller owns it
	 * @return the number of data rows written
	 */
	public int writeTab(String filePath, String tabName, String sourceQuery, String sourceConnectionId, ResultSet sourceResults)
			throws BroadSQLException, SQLException {
		if (PullSpreadsheetInfoTab.SHEET_NAME.equalsIgnoreCase(tabName)) {
			throw new BroadSQLException("PULL aborted: '" + PullSpreadsheetInfoTab.SHEET_NAME + "' is reserved for the per-file "
					+ "query log tab and cannot be used as a data tab name (see docs/PULL_TO_SPREADSHEET.md, \"Info tab\").");
		}

		ResultSetMetaData metaData = sourceResults.getMetaData();
		int columnCount = metaData.getColumnCount();
		int[] columnTypes = new int[columnCount];
		for (int i = 1; i <= columnCount; i++) {
			columnTypes[i - 1] = metaData.getColumnType(i);
			PullSpreadsheetTypeMapper.validateSupported(columnTypes[i - 1], metaData.getColumnLabel(i), metaData.getColumnTypeName(i));
		}

		File targetFile = new File(filePath);
		XSSFWorkbook workbook;
		try {
			if (targetFile.isFile()) {
				try (FileInputStream in = new FileInputStream(targetFile)) {
					workbook = new XSSFWorkbook(in);
				}
			} else {
				workbook = new XSSFWorkbook();
			}
		} catch (IOException e) {
			throw new BroadSQLException("Unable to open existing file '" + filePath + "' as an Excel workbook: " + e.getLocalizedMessage(), e);
		}

		try {
			removeSheetIfPresent(workbook, tabName);
			Sheet dataSheet = workbook.createSheet(tabName);
			int rowsWritten = writeDataSheet(workbook, dataSheet, metaData, columnTypes, sourceResults);

			upsertInfoRow(workbook, tabName, sourceQuery, sourceConnectionId, rowsWritten);

			writeAtomically(workbook, targetFile);
			return rowsWritten;

		} catch (RuntimeException re) {
			// Normalizes unexpected POI failures into the exception type the rest of the app expects to
			// catch and display cleanly - CommandInterruptedException is a checked BroadSQLException, not
			// a RuntimeException, so CTRL+C cancellation still propagates through untouched.
			throw new BroadSQLException(re);
		} finally {
			try {
				workbook.close();
			} catch (IOException ignored) {
				// nothing meaningful to do with a close failure on a workbook we're done with
			}
		}
	}

	private void removeSheetIfPresent(XSSFWorkbook workbook, String tabName) {
		int index = workbook.getSheetIndex(tabName);
		if (index >= 0) {
			workbook.removeSheetAt(index);
		}
	}

	private int writeDataSheet(XSSFWorkbook workbook, Sheet dataSheet, ResultSetMetaData metaData, int[] columnTypes, ResultSet results)
			throws SQLException, BroadSQLException {

		int columnCount = columnTypes.length;

		CellStyle headerStyle = workbook.createCellStyle();
		Font font = workbook.createFont();
		font.setBold(true);
		headerStyle.setFont(font);
		headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
		headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

		Row headerRow = dataSheet.createRow(0);
		for (int i = 0; i < columnCount; i++) {
			Cell cell = headerRow.createCell(i);
			cell.setCellStyle(headerStyle);
			cell.setCellValue(metaData.getColumnLabel(i + 1));
		}

		CreationHelper createHelper = workbook.getCreationHelper();
		CellStyle cellStyleDate = workbook.createCellStyle();
		cellStyleDate.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-MM-dd"));
		CellStyle cellStyleTime = workbook.createCellStyle();
		cellStyleTime.setDataFormat(createHelper.createDataFormat().getFormat("hh:mm:ss"));
		CellStyle cellStyleDateTime = workbook.createCellStyle();
		cellStyleDateTime.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-MM-dd hh:mm:ss"));

		int rowId = 0;
		final int maxDataRowIndex = SpreadsheetVersion.EXCEL2007.getLastRowIndex();
		while (results.next()) {
			if (CommandCancellation.isRequested()) {
				throw new CommandInterruptedException("Command interrupted");
			}
			if (rowId >= maxDataRowIndex) {
				throw new BroadSQLException("Excel sheet row limit reached (" + (maxDataRowIndex + 1) + " data rows per sheet) "
						+ "while writing tab '" + dataSheet.getSheetName() + "' - consider AS ODS, or a plain EXPORT/DUMP to text/CSV, for result sets this large.");
			}

			rowId++;
			Row row = dataSheet.createRow(rowId);
			for (int i = 0; i < columnCount; i++) {
				Cell cell = row.createCell(i);
				writeCellValue(cell, results, i + 1, columnTypes[i], cellStyleDate, cellStyleTime, cellStyleDateTime);
			}
		}
		return rowId;
	}

	/**
	 * Mirrors {@code QueryExtractorToExcel2007}'s type switch (same JDBC types in, same POI cell types
	 * out), minus the placeholder-text branch for binary/unsupported types - those are rejected up front
	 * by {@link PullSpreadsheetTypeMapper} instead, so this switch only ever sees a supported type.
	 */
	private void writeCellValue(Cell cell, ResultSet results, int columnIndex, int jdbcType,
			CellStyle cellStyleDate, CellStyle cellStyleTime, CellStyle cellStyleDateTime) throws SQLException {
		switch (jdbcType) {
			case Types.DATE: {
				Date value = results.getDate(columnIndex);
				if (!results.wasNull()) {
					cell.setCellStyle(cellStyleDate);
					cell.setCellValue(value);
				}
				break;
			}
			case Types.TIME:
			case Types.TIME_WITH_TIMEZONE: {
				Time value = results.getTime(columnIndex);
				if (!results.wasNull()) {
					cell.setCellStyle(cellStyleTime);
					cell.setCellValue(value);
				}
				break;
			}
			case Types.TIMESTAMP:
			case Types.TIMESTAMP_WITH_TIMEZONE:
			case OracleJdbcTypes.TIMESTAMPTZ:
			case OracleJdbcTypes.TIMESTAMPLTZ: {
				Timestamp value = results.getTimestamp(columnIndex);
				if (!results.wasNull()) {
					cell.setCellStyle(cellStyleDateTime);
					cell.setCellValue(value);
				}
				break;
			}
			case Types.TINYINT:
			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.BIGINT: {
				long longValue = results.getLong(columnIndex);
				if (!results.wasNull()) {
					double asDouble = longValue;
					if ((long) asDouble == longValue) {
						cell.setCellValue(asDouble);
					} else {
						cell.setCellValue(Long.toString(longValue));
					}
				}
				break;
			}
			case Types.DECIMAL:
			case Types.NUMERIC: {
				BigDecimal value = results.getBigDecimal(columnIndex);
				if (!results.wasNull()) {
					double asDouble = value.doubleValue();
					if (BigDecimal.valueOf(asDouble).compareTo(value) == 0) {
						cell.setCellValue(asDouble);
					} else {
						cell.setCellValue(value.toPlainString());
					}
				}
				break;
			}
			case Types.DOUBLE:
			case Types.REAL: {
				double value = results.getDouble(columnIndex);
				if (!results.wasNull()) {
					cell.setCellValue(value);
				}
				break;
			}
			case Types.FLOAT: {
				float value = results.getFloat(columnIndex);
				if (!results.wasNull()) {
					cell.setCellValue(value);
				}
				break;
			}
			case Types.BOOLEAN:
			case Types.BIT: {
				boolean value = results.getBoolean(columnIndex);
				if (!results.wasNull()) {
					cell.setCellValue(value);
				}
				break;
			}
			default: {
				String value = results.getString(columnIndex);
				if (value != null) {
					if (value.length() > 32767) {
						value = value.substring(0, 32767);
					}
					cell.setCellValue(value);
				}
				break;
			}
		}
	}

	/**
	 * Finds the info tab (creating it if absent), validates it if it already existed
	 * ({@link PullSpreadsheetInfoTab#validateHeaderRow}), and replaces the row for {@code tabName} if one
	 * already exists there, or appends a new one otherwise.
	 */
	private void upsertInfoRow(XSSFWorkbook workbook, String tabName, String sourceQuery, String sourceConnectionId, int rowsWritten) throws BroadSQLException {
		Sheet infoSheet = workbook.getSheet(PullSpreadsheetInfoTab.SHEET_NAME);
		if (infoSheet == null) {
			infoSheet = workbook.createSheet(PullSpreadsheetInfoTab.SHEET_NAME);
			Row headerRow = infoSheet.createRow(0);
			for (int i = 0; i < PullSpreadsheetInfoTab.HEADERS.length; i++) {
				headerRow.createCell(i).setCellValue(PullSpreadsheetInfoTab.HEADERS[i]);
			}
		} else {
			PullSpreadsheetInfoTab.validateHeaderRow(readRowAsStrings(infoSheet.getRow(0), PullSpreadsheetInfoTab.HEADERS.length));
		}

		int targetRowIndex = -1;
		for (int r = 1; r <= infoSheet.getLastRowNum(); r++) {
			Row row = infoSheet.getRow(r);
			if (row != null) {
				Cell tabCell = row.getCell(0);
				if (tabCell != null && tabName.equals(tabCell.getStringCellValue())) {
					targetRowIndex = r;
					break;
				}
			}
		}
		if (targetRowIndex < 0) {
			targetRowIndex = infoSheet.getLastRowNum() + 1;
		}

		Row row = infoSheet.getRow(targetRowIndex);
		if (row == null) {
			row = infoSheet.createRow(targetRowIndex);
		}
		getOrCreateCell(row, 0).setCellValue(tabName);
		getOrCreateCell(row, 1).setCellValue(sourceQuery);
		getOrCreateCell(row, 2).setCellValue(LocalDateTime.now().format(INFO_DATE_FORMAT));
		getOrCreateCell(row, 3).setCellValue(sourceConnectionId == null ? "" : sourceConnectionId);
		getOrCreateCell(row, 4).setCellValue(rowsWritten);
	}

	private Cell getOrCreateCell(Row row, int column) {
		Cell cell = row.getCell(column);
		return cell != null ? cell : row.createCell(column);
	}

	private String[] readRowAsStrings(Row row, int columnCount) {
		if (row == null) {
			return new String[0];
		}
		String[] values = new String[columnCount];
		for (int i = 0; i < columnCount; i++) {
			Cell cell = row.getCell(i);
			values[i] = cell == null ? "" : cell.toString();
		}
		return values;
	}

	/**
	 * Writes {@code workbook} to a temporary file next to {@code targetFile}, then moves it over
	 * {@code targetFile} only once the write has completed and the file handle is closed - so a failure
	 * partway through the write never leaves {@code targetFile} half-written (docs/PULL_TO_SPREADSHEET.md,
	 * "Foreign files").
	 */
	private void writeAtomically(XSSFWorkbook workbook, File targetFile) throws BroadSQLException {
		File parent = targetFile.getAbsoluteFile().getParentFile();
		File tempFile;
		try {
			tempFile = File.createTempFile(targetFile.getName() + ".", ".tmp", parent);
		} catch (IOException e) {
			throw new BroadSQLException("Unable to create a temporary file next to '" + targetFile.getAbsolutePath() + "': " + e.getLocalizedMessage(), e);
		}
		try {
			try (FileOutputStream fos = new FileOutputStream(tempFile)) {
				workbook.write(fos);
			}
			Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			tempFile.delete();
			throw new BroadSQLException("Unable to write '" + targetFile.getAbsolutePath() + "': " + e.getLocalizedMessage(), e);
		}
	}
}
