package com.upandcoding.broadsql.dao.extractors;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.github.miachm.sods.Color;
import com.github.miachm.sods.Sheet;
import com.github.miachm.sods.SpreadSheet;
import com.github.miachm.sods.Style;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.CommandInterruptedException;
import com.upandcoding.broadsql.controller.shell.commands.CommandCancellation;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Exports a query result to an OpenDocument Spreadsheet ({@code .ods}) file, using the SODS library
 * (see the comment on {@code sods.version} in {@code pom.xml} for why SODS over Apache ODF Toolkit).
 * Mirrors {@link QueryExtractorToExcel2007}'s reliability behavior (NULL handling, a header row,
 * a row-count safety cap) adapted to SODS's API, which - unlike POI's streaming {@code SXSSFWorkbook}
 * - builds the whole sheet in memory before {@code save()} writes it out.
 */
public class QueryExtractorToODS implements IQueryExtractor {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

	// SODS holds the whole sheet in memory (no streaming writer, unlike POI's SXSSFWorkbook) - this
	// cap only exists to bound memory use on an unexpectedly huge EXPORT, matching the same practical
	// limit already enforced for .xlsx (SpreadsheetVersion.EXCEL2007's row cap), not an ODF spec limit.
	private static final int MAX_DATA_ROWS = 1_048_576;

	/**
	 * Writes {@code results} to {@code fileName} as an {@code .ods} spreadsheet.
	 *
	 * <p>Behaves like {@link QueryExtractorToExcel2007#extractToExcel2007(DatabaseDefinition, ShellConsole, String, ResultSet, String)}
	 * for {@code NULL} handling (blank cell), {@code DATE}/{@code TIMESTAMP} columns (a real, typed
	 * cell, time-of-day preserved), and the {@value #MAX_DATA_ROWS}-row safety cap (export stops
	 * cleanly past it, with a console warning) - see this class's Javadoc for why that cap exists here
	 * even though ODF has no equivalent hard limit. It differs in three ways dictated by the SODS
	 * API: {@code TIME} columns have no native cell type and are written as formatted text
	 * ({@link #TIME_FORMAT}); {@code BIGINT}/{@code DECIMAL}/{@code NUMERIC} values are always written
	 * with their exact decimal value (no {@code double} round-trip to guard against, unlike Excel);
	 * and a {@code NULL} binary column correctly produces a blank cell instead of the placeholder text
	 * used for a non-null one (see {@link QueryExtractorToExcel2007}'s Javadoc for the equivalent,
	 * uncorrected, limitation there). There is no second "Query" sheet, unlike the Excel extractor.
	 *
	 * @param platform      the source database; unused by this extractor (kept for parity with
	 *                      {@link IQueryExtractor}/{@link QueryExtractorToExcel2007})
	 * @param cmdLineConsole console to print the row-limit warning to, if the sheet's row cap is hit
	 *                       (may be {@code null}, in which case that warning is silently skipped)
	 * @param query         the SQL query that produced {@code results}, used only to derive the sheet
	 *                      name ({@link #getTableNameFromQuery(String)}, sanitized via
	 *                      {@link SpreadsheetSheetName})
	 * @param results       the result set to write; iterated to completion (or cancellation) by this
	 *                      method, which also closes it
	 * @param fileName      the path of the {@code .ods} file to create; if {@code null} (or
	 *                      {@code results} is {@code null}), this method does nothing
	 */
	public void extractToODS(DatabaseDefinition platform, ShellConsole cmdLineConsole, String query, ResultSet results, String fileName) throws BroadSQLException, IOException, SQLException {

		if (results == null || fileName == null) {
			return;
		}

		try {
			ResultSetMetaData metaData = results.getMetaData();
			int columnCount = metaData.getColumnCount();

			SpreadSheet spreadSheet = new SpreadSheet();
			Sheet sheet = new Sheet(SpreadsheetSheetName.sanitize(getTableNameFromQuery(query)), 1, columnCount);

			// Header row (via metadata)
			// ========================
			for (int i = 0; i < columnCount; i++) {
				sheet.getRange(0, i).setValue(metaData.getColumnLabel(i + 1));
			}
			Style headerStyle = new Style();
			headerStyle.setBold(true);
			headerStyle.setBackgroundColor(new Color(198, 217, 241));
			sheet.getRange(0, 0, 1, columnCount).setStyle(headerStyle);

			// Data rows (actual ResultSet entries)
			// ====================================
			int rowId = 0;
			boolean rowLimitReached = false;
			long rowsNotExported = 0;
			while (results.next()) {
				// CTRL+C support: abort the export without closing the connection
				// (see docs/TODO.md, "Ameliorations de la GUI")
				if (CommandCancellation.isRequested()) {
					throw new CommandInterruptedException("Command interrupted");
				}

				if (rowId >= MAX_DATA_ROWS) {
					rowLimitReached = true;
					rowsNotExported++;
					continue;
				}

				rowId++;
				sheet.appendRow();

				for (int i = 0; i < columnCount; i++) {

					int colType = metaData.getColumnType(i + 1);

					// Each branch reads the column with the JDBC getter matching its actual type, then
					// checks wasNull() - see QueryExtractorToExcel2007 for why (the primitive/date
					// getters return 0/false/a bogus date on a SQL NULL, not null).
					if (colType == Types.DATE) {
						java.sql.Date date = results.getDate(i + 1);
						if (!results.wasNull()) {
							sheet.getRange(rowId, i).setValue(date.toLocalDate());
						}

					} else if (colType == Types.TIME || colType == Types.TIME_WITH_TIMEZONE) {
						java.sql.Time time = results.getTime(i + 1);
						if (!results.wasNull()) {
							// SODS has no dedicated TIME value type (unlike DATE, which natively takes a
							// java.time.LocalDate/LocalDateTime) - format explicitly rather than falling
							// back to Time.toString()'s default rendering.
							sheet.getRange(rowId, i).setValue(time.toLocalTime().format(TIME_FORMAT));
						}

					} else if (colType == Types.TIMESTAMP || colType == Types.TIMESTAMP_WITH_TIMEZONE
							|| colType == OracleJdbcTypes.TIMESTAMPTZ || colType == OracleJdbcTypes.TIMESTAMPLTZ) {
						// getDate() truncates the time-of-day on a TIMESTAMP column - use
						// getTimestamp() so the actual time is preserved instead of showing midnight.
						java.sql.Timestamp timestamp = results.getTimestamp(i + 1);
						if (!results.wasNull()) {
							sheet.getRange(rowId, i).setValue(timestamp.toLocalDateTime());
						}

					} else if (colType == Types.SMALLINT || colType == Types.BIGINT || colType == Types.TINYINT || colType == Types.INTEGER) {
						long longValue = results.getLong(i + 1);
						if (!results.wasNull()) {
							// Unlike Excel (a double-backed cell format), SODS writes the exact decimal
							// text into the ODF XML - no precision loss to guard against here.
							sheet.getRange(rowId, i).setValue(Long.valueOf(longValue));
						}

					} else if (colType == Types.DECIMAL || colType == Types.NUMERIC) {
						BigDecimal decimalValue = results.getBigDecimal(i + 1);
						if (!results.wasNull()) {
							sheet.getRange(rowId, i).setValue(decimalValue);
						}

					} else if (colType == Types.DOUBLE || colType == Types.REAL) {
						double doubleValue = results.getDouble(i + 1);
						if (!results.wasNull()) {
							sheet.getRange(rowId, i).setValue(Double.valueOf(doubleValue));
						}

					} else if (colType == Types.FLOAT) {
						float floatValue = results.getFloat(i + 1);
						if (!results.wasNull()) {
							sheet.getRange(rowId, i).setValue(Double.valueOf(floatValue));
						}

					} else if (colType == Types.BOOLEAN) {
						boolean boolValue = results.getBoolean(i + 1);
						if (!results.wasNull()) {
							sheet.getRange(rowId, i).setValue(Boolean.valueOf(boolValue));
						}

					} else if (colType == Types.BINARY || colType == Types.OTHER || colType == Types.VARBINARY || colType == Types.LONGVARBINARY || colType == Types.DATALINK) {
						results.getBytes(i + 1);
						if (!results.wasNull()) {
							sheet.getRange(rowId, i).setValue("Binary content, not exported to ODS");
						}

					} else {
						String cellValueStr = results.getString(i + 1);
						if (cellValueStr != null) {
							sheet.getRange(rowId, i).setValue(cellValueStr);
						}
					}
				}
			}

			if (rowLimitReached && cmdLineConsole != null) {
				cmdLineConsole.warn("ODS export row limit reached (" + MAX_DATA_ROWS + " data rows per sheet): "
						+ rowId + " row(s) exported, " + rowsNotExported + " row(s) NOT exported. Consider a text/CSV export for result sets this large.");
			}

			spreadSheet.appendSheet(sheet);
			spreadSheet.save(new File(fileName));

		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		} catch (RuntimeException re) {
			// Normalizes unexpected SODS failures into the exception type the rest of the app expects
			// to catch and display cleanly - CommandInterruptedException is a checked BroadSQLException,
			// not a RuntimeException, so CTRL+C cancellation still propagates through untouched.
			throw new BroadSQLException(re);
		} finally {
			try {
				if (results != null) {
					results.close();
				}
			} catch (SQLException e) {
				throw new BroadSQLException(e);
			}
		}
	}

	/**
	 * {@link IQueryExtractor} entry point used by {@link com.upandcoding.broadsql.dao.DatabaseConnection}.
	 * Delegates to {@link #extractToODS(DatabaseDefinition, ShellConsole, String, ResultSet, String)}
	 * - {@code maxRowsOnScreen}, {@code listMode}, {@code screenSep}, {@code sep}, {@code isAppendToFile}
	 * and {@code start} are part of the shared {@link IQueryExtractor} contract but not used for an
	 * ODS export (append and separators are text-format concepts; ODS has no equivalent).
	 */
	@Override
	public void extract(DatabaseDefinition platform, ShellConsole cmdLineConsole, String query, ResultSet results, int maxRowsOnScreen, boolean listMode, String fileName, char screenSep, char sep, boolean isAppendToFile, Instant start)
			throws BroadSQLException, IOException, SQLException {
		extractToODS(platform, cmdLineConsole, query, results, fileName);
	}
}
