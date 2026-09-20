package com.upandcoding.broadsql.dao.pull.spreadsheet;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.github.miachm.sods.Color;
import com.github.miachm.sods.Sheet;
import com.github.miachm.sods.SpreadSheet;
import com.github.miachm.sods.Style;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.CommandInterruptedException;
import com.upandcoding.broadsql.controller.shell.commands.CommandCancellation;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;

/**
 * Engine behind {@code PULL ... TO <name>.<tab> AS ODS} - see docs/PULL_TO_SPREADSHEET.md. Mirrors
 * {@link PullToXlsxExporter} exactly in structure and behavior (type validation up front, load-or-create,
 * erase-and-replace the named tab, upsert the shared "QUERIES" info tab, atomic write) but talks to SODS's
 * API instead of POI's - see that class's Javadoc for the shared design rationale, not repeated here.
 *
 * <p>Differs from {@link PullToXlsxExporter} only where the two libraries' APIs force it: SODS's own
 * sheet lookups (e.g. {@code SpreadSheet.getSheet(String)}) compare names with plain {@code equals}, not
 * case-insensitively like POI's {@code Workbook.getSheetIndex(String)} - so tab lookups here
 * ({@link #findSheetIgnoreCase}) do their own case-insensitive matching instead of relying on the
 * library; and SODS holds the whole sheet in memory with no streaming writer (matching
 * {@code QueryExtractorToODS}'s existing behavior for a fresh export), hence the same
 * {@value #MAX_DATA_ROWS}-row safety cap that class already enforces.
 */
public class PullToOdsExporter {

	// SODS holds the whole sheet in memory (no streaming writer) - matches the cap QueryExtractorToODS
	// already enforces for a fresh EXPORT/DUMP, not an ODF spec limit.
	private static final int MAX_DATA_ROWS = 1_048_576;

	private static final DateTimeFormatter INFO_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

	/**
	 * @param filePath    the {@code .ods} file's full path - loaded whole if it already exists, created
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
		SpreadSheet spreadSheet;
		try {
			spreadSheet = targetFile.isFile() ? new SpreadSheet(targetFile) : new SpreadSheet();
		} catch (IOException e) {
			throw new BroadSQLException("Unable to open existing file '" + filePath + "' as an ODS spreadsheet: " + e.getLocalizedMessage(), e);
		}

		try {
			removeSheetIfPresent(spreadSheet, tabName);
			Sheet dataSheet = new Sheet(tabName, 1, columnCount);
			int rowsWritten = writeDataSheet(dataSheet, metaData, columnTypes, sourceResults);
			spreadSheet.appendSheet(dataSheet);

			upsertInfoRow(spreadSheet, tabName, sourceQuery, sourceConnectionId, rowsWritten);

			writeAtomically(spreadSheet, targetFile);
			return rowsWritten;

		} catch (RuntimeException re) {
			// Normalizes unexpected SODS failures into the exception type the rest of the app expects to
			// catch and display cleanly - CommandInterruptedException is a checked BroadSQLException, not
			// a RuntimeException, so CTRL+C cancellation still propagates through untouched.
			throw new BroadSQLException(re);
		}
	}

	private Sheet findSheetIgnoreCase(SpreadSheet spreadSheet, String name) {
		for (Sheet sheet : spreadSheet.getSheets()) {
			if (sheet.getName().equalsIgnoreCase(name)) {
				return sheet;
			}
		}
		return null;
	}

	private void removeSheetIfPresent(SpreadSheet spreadSheet, String tabName) {
		Sheet existing = findSheetIgnoreCase(spreadSheet, tabName);
		if (existing != null) {
			spreadSheet.deleteSheet(existing);
		}
	}

	private int writeDataSheet(Sheet dataSheet, ResultSetMetaData metaData, int[] columnTypes, ResultSet results) throws SQLException, BroadSQLException {
		int columnCount = columnTypes.length;

		for (int i = 0; i < columnCount; i++) {
			dataSheet.getRange(0, i).setValue(metaData.getColumnLabel(i + 1));
		}
		Style headerStyle = new Style();
		headerStyle.setBold(true);
		headerStyle.setBackgroundColor(new Color(198, 217, 241));
		dataSheet.getRange(0, 0, 1, columnCount).setStyle(headerStyle);

		int rowId = 0;
		while (results.next()) {
			if (CommandCancellation.isRequested()) {
				throw new CommandInterruptedException("Command interrupted");
			}
			if (rowId >= MAX_DATA_ROWS) {
				throw new BroadSQLException("ODS row limit reached (" + MAX_DATA_ROWS + " data rows per sheet) while writing "
						+ "tab '" + dataSheet.getName() + "' - consider a plain EXPORT/DUMP to text/CSV for result sets this large.");
			}

			rowId++;
			dataSheet.appendRow();
			for (int i = 0; i < columnCount; i++) {
				writeCellValue(dataSheet, rowId, i, results, i + 1, columnTypes[i]);
			}
		}
		return rowId;
	}

	/** Mirrors {@link PullToXlsxExporter#writeCellValue} for the SODS API - see that method's Javadoc. */
	private void writeCellValue(Sheet sheet, int rowId, int columnIndex, ResultSet results, int resultSetIndex, int jdbcType) throws SQLException {
		switch (jdbcType) {
			case Types.DATE: {
				java.sql.Date value = results.getDate(resultSetIndex);
				if (!results.wasNull()) {
					sheet.getRange(rowId, columnIndex).setValue(value.toLocalDate());
				}
				break;
			}
			case Types.TIME:
			case Types.TIME_WITH_TIMEZONE: {
				java.sql.Time value = results.getTime(resultSetIndex);
				if (!results.wasNull()) {
					// SODS has no dedicated TIME value type, unlike DATE/TIMESTAMP - format explicitly.
					sheet.getRange(rowId, columnIndex).setValue(value.toLocalTime().format(TIME_FORMAT));
				}
				break;
			}
			case Types.TIMESTAMP:
			case Types.TIMESTAMP_WITH_TIMEZONE:
			case OracleJdbcTypes.TIMESTAMPTZ:
			case OracleJdbcTypes.TIMESTAMPLTZ: {
				java.sql.Timestamp value = results.getTimestamp(resultSetIndex);
				if (!results.wasNull()) {
					sheet.getRange(rowId, columnIndex).setValue(value.toLocalDateTime());
				}
				break;
			}
			case Types.TINYINT:
			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.BIGINT: {
				long value = results.getLong(resultSetIndex);
				if (!results.wasNull()) {
					sheet.getRange(rowId, columnIndex).setValue(Long.valueOf(value));
				}
				break;
			}
			case Types.DECIMAL:
			case Types.NUMERIC: {
				// SODS serializes a BigDecimal via its own exact toString() (OfficeValueType.FLOAT.write(),
				// confirmed from the SODS 1.6.7 source), never rounding through a double first - unlike
				// Excel, which must fall back to text for a value that wouldn't round-trip through double
				// (see PullToXlsxExporter.writeCellValue). Note this exactness is a write-time-only
				// guarantee: SODS's own reader (OfficeValueType.FLOAT.read()) always parses a numeric cell
				// back as a Double regardless of what wrote it, so reading a cell back through SODS itself
				// does not preserve arbitrary precision, even though the ODF XML on disk does.
				BigDecimal value = results.getBigDecimal(resultSetIndex);
				if (!results.wasNull()) {
					sheet.getRange(rowId, columnIndex).setValue(value);
				}
				break;
			}
			case Types.DOUBLE:
			case Types.REAL: {
				double value = results.getDouble(resultSetIndex);
				if (!results.wasNull()) {
					sheet.getRange(rowId, columnIndex).setValue(Double.valueOf(value));
				}
				break;
			}
			case Types.FLOAT: {
				float value = results.getFloat(resultSetIndex);
				if (!results.wasNull()) {
					sheet.getRange(rowId, columnIndex).setValue(Double.valueOf(value));
				}
				break;
			}
			case Types.BOOLEAN:
			case Types.BIT: {
				boolean value = results.getBoolean(resultSetIndex);
				if (!results.wasNull()) {
					sheet.getRange(rowId, columnIndex).setValue(Boolean.valueOf(value));
				}
				break;
			}
			default: {
				String value = results.getString(resultSetIndex);
				if (value != null) {
					sheet.getRange(rowId, columnIndex).setValue(value);
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
	private void upsertInfoRow(SpreadSheet spreadSheet, String tabName, String sourceQuery, String sourceConnectionId, int rowsWritten) throws BroadSQLException {
		Sheet infoSheet = findSheetIgnoreCase(spreadSheet, PullSpreadsheetInfoTab.SHEET_NAME);
		boolean isNew = infoSheet == null;
		if (isNew) {
			infoSheet = new Sheet(PullSpreadsheetInfoTab.SHEET_NAME, 1, PullSpreadsheetInfoTab.HEADERS.length);
			for (int i = 0; i < PullSpreadsheetInfoTab.HEADERS.length; i++) {
				infoSheet.getRange(0, i).setValue(PullSpreadsheetInfoTab.HEADERS[i]);
			}
		} else {
			PullSpreadsheetInfoTab.validateHeaderRow(readRowAsStrings(infoSheet, 0, PullSpreadsheetInfoTab.HEADERS.length));
		}

		int targetRow = -1;
		for (int r = 1; r < infoSheet.getMaxRows(); r++) {
			Object value = infoSheet.getRange(r, 0).getValue();
			if (value != null && tabName.equals(value.toString())) {
				targetRow = r;
				break;
			}
		}
		if (targetRow < 0) {
			infoSheet.appendRow();
			targetRow = infoSheet.getMaxRows() - 1;
		}

		infoSheet.getRange(targetRow, 0).setValue(tabName);
		infoSheet.getRange(targetRow, 1).setValue(sourceQuery);
		infoSheet.getRange(targetRow, 2).setValue(LocalDateTime.now().format(INFO_DATE_FORMAT));
		infoSheet.getRange(targetRow, 3).setValue(sourceConnectionId == null ? "" : sourceConnectionId);
		infoSheet.getRange(targetRow, 4).setValue(Integer.valueOf(rowsWritten));

		if (isNew) {
			spreadSheet.appendSheet(infoSheet);
		}
	}

	/**
	 * Bound-checked against {@code sheet}'s actual column count: a foreign sheet narrower than
	 * {@code columnCount} (e.g. a hand-built "QUERIES" sheet with a single free-text column) must produce a
	 * row that simply fails {@link PullSpreadsheetInfoTab#validateHeaderRow} - not SODS's own
	 * {@code IndexOutOfBoundsException} from a {@code getRange} call past the sheet's grid, which would
	 * otherwise surface as a raw, unfriendly error instead of the clean "not created by PULL" one.
	 */
	private String[] readRowAsStrings(Sheet sheet, int row, int columnCount) {
		String[] values = new String[columnCount];
		int actualColumns = sheet.getMaxColumns();
		for (int i = 0; i < columnCount; i++) {
			if (i >= actualColumns) {
				values[i] = "";
				continue;
			}
			Object value = sheet.getRange(row, i).getValue();
			values[i] = value == null ? "" : value.toString();
		}
		return values;
	}

	/**
	 * Writes {@code spreadSheet} to a temporary file next to {@code targetFile}, then moves it over
	 * {@code targetFile} only once the write has completed - see {@link PullToXlsxExporter#writeAtomically}
	 * for the same rationale.
	 */
	private void writeAtomically(SpreadSheet spreadSheet, File targetFile) throws BroadSQLException {
		File parent = targetFile.getAbsoluteFile().getParentFile();
		File tempFile;
		try {
			tempFile = File.createTempFile(targetFile.getName() + ".", ".tmp", parent);
		} catch (IOException e) {
			throw new BroadSQLException("Unable to create a temporary file next to '" + targetFile.getAbsolutePath() + "': " + e.getLocalizedMessage(), e);
		}
		try {
			spreadSheet.save(tempFile);
			Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			tempFile.delete();
			throw new BroadSQLException("Unable to write '" + targetFile.getAbsolutePath() + "': " + e.getLocalizedMessage(), e);
		}
	}
}
