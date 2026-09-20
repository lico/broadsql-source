package com.upandcoding.broadsql.dao.extractors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Date;

import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.CommandInterruptedException;
import com.upandcoding.broadsql.controller.shell.commands.CommandCancellation;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Writes a query result to an Excel 2007+ ({@code .xlsx}) file, used for {@code EXPORT}/{@code DUMP}
 * when the target file has that extension (see {@link com.upandcoding.broadsql.dao.DatabaseConnection#setFileName(String)}).
 *
 * <p>A {@code NULL} value produces a blank cell rather than {@code 0}/{@code false}/a placeholder
 * date; {@code DATE}/{@code TIME}/{@code TIMESTAMP} columns get a real, typed cell with a matching
 * format (a {@code TIMESTAMP}'s time-of-day is preserved, not truncated to midnight);
 * {@code BIGINT}/{@code DECIMAL}/{@code NUMERIC} values are written as a real number when that
 * round-trips exactly through a {@code double}, otherwise as exact text, since a double cannot
 * represent arbitrary precision. The sheet name is derived from the query
 * ({@link #getTableNameFromQuery(String)}) and sanitized ({@link SpreadsheetSheetName}); a second
 * sheet named "Query" records the exact SQL text and when it ran. A sheet is capped at
 * {@link SpreadsheetVersion#EXCEL2007}'s row limit (1,048,576 data rows) - beyond that, the export
 * stops cleanly and a warning reports how many rows were and weren't written.
 *
 * <p>{@code BLOB}/{@code CLOB}/{@code NCLOB} are not handled explicitly and fall into the generic
 * text case. Binary columns ({@code BINARY}/{@code VARBINARY}/{@code LONGVARBINARY}/{@code OTHER}/
 * {@code DATALINK}) are written as placeholder text, even when the value is actually {@code NULL}
 * (known limitation - see {@link QueryExtractorToODS}, which does not have it).
 */
public class QueryExtractorToExcel2007 implements IQueryExtractor {

	/**
	 * Writes {@code results} to {@code fileName} as an {@code .xlsx} workbook. See the class Javadoc
	 * for the exact type-mapping and row-limit behavior.
	 *
	 * @param platform      the source database, used only for the timestamp note on the "Query" sheet
	 * @param cmdLineConsole console to print the row-limit warning to, if the sheet's row cap is hit
	 *                       (may be {@code null}, in which case that warning is silently skipped)
	 * @param query         the SQL query that produced {@code results}, used to derive the sheet name
	 *                      and recorded verbatim on the "Query" sheet
	 * @param results       the result set to write; iterated to completion (or cancellation) by this
	 *                      method, which also closes it
	 * @param fileName      the path of the {@code .xlsx} file to create; if {@code null}, this method
	 *                      does nothing
	 */
	public void extractToExcel2007(DatabaseDefinition platform, ShellConsole cmdLineConsole, String query, ResultSet results, String fileName) throws BroadSQLException, IOException, SQLException {

		int XLS_ROWS_BUFFER = 1000; // for XLSX export, keep 1000 rows in memory, exceeding rows will be flushed to
									// disk
		// Export to text, csv file
		FileOutputStream fos = null;
		// Export to XLS: SXSSFWorkbook
		SXSSFWorkbook workbook = null;
		// Export to XLS: HSSFSheet firstSheet = null;
		Sheet firstSheet = null;
		Sheet querySheet = null;
		// Export to XLS: HSSFRow row = null;
		Row excelRow = null;
		// Export to XLS: HSSFCellStyle cellStyle1 = null;
		CellStyle cellStyle1 = null;

		if (results != null) {
			try {
				// Init the output file
				// ********************
				if (fileName != null) {
					// Init XL 2007 file
					workbook = new SXSSFWorkbook(XLS_ROWS_BUFFER);
					fos = new FileOutputStream(new File(fileName));
					firstSheet = workbook.createSheet(SpreadsheetSheetName.sanitize(getTableNameFromQuery(query)));
					// Write query in a new query sheet
					querySheet = workbook.createSheet("Query");
					Row rw = querySheet.createRow(0);
					Cell cl = rw.createCell(0);
					Date now = new Date();
					cl.setCellValue("Query executed on " + platform.getDbName() + " on " + DateFormatUtils.format(now, "dd MMM yyyy 'at' HH:mm:ss z"));
					rw = querySheet.createRow(1);
					cl = rw.createCell(0);
					cl.setCellValue(query);

					cellStyle1 = workbook.createCellStyle();
					Font font = workbook.createFont();
					// font.setBoldweight(HSSFFont.setBold(true));
					font.setBold(true);
					cellStyle1.setFont(font);
					cellStyle1.setFillForegroundColor(HSSFColor.HSSFColorPredefined.LIGHT_CORNFLOWER_BLUE.getIndex());
					cellStyle1.setFillPattern(FillPatternType.SOLID_FOREGROUND);

					// Header row (via metadata)
					// ========================
					ResultSetMetaData metaData = results.getMetaData();
					excelRow = firstSheet.createRow(0);
					for (int i = 0; i < metaData.getColumnCount(); i++) {
						Cell cell = excelRow.createCell(i);
						cell.setCellStyle(cellStyle1);
						cell.setCellValue(metaData.getColumnLabel(i + 1));
					}

					CreationHelper createHelper = workbook.getCreationHelper();
					CellStyle cellStyleDate = workbook.createCellStyle();
					cellStyleDate.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-MM-dd"));
					CellStyle cellStyleTime = workbook.createCellStyle();
					cellStyleTime.setDataFormat(createHelper.createDataFormat().getFormat("hh:mm:ss"));
					CellStyle cellStyleDateTime = workbook.createCellStyle();
					cellStyleDateTime.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-MM-dd hh:mm:ss"));

					// Data rows (actual ResultSet entries)
					// ====================================
					// log.debug("max rows: "+maxRowsOnScreen);
					int rowId = 0;
					// Last row index a sheet can hold (row 0 is the header, so this many data rows fit).
					final int maxDataRowIndex = SpreadsheetVersion.EXCEL2007.getLastRowIndex();
					boolean rowLimitReached = false;
					long rowsNotExported = 0;
					// Computes total nr of rows in the result set
					while (results.next()) {
						// CTRL+C support: abort the export without closing the connection
						// (see docs/TODO.md, "Ameliorations de la GUI")
						if (CommandCancellation.isRequested()) {
							throw new CommandInterruptedException("Command interrupted");
						}

						if (rowId >= maxDataRowIndex) {
							// Excel sheet row limit reached: stop writing, but keep consuming the
							// ResultSet (no cell writes) just to report how many rows were left out,
							// instead of leaving the caller to guess or crashing on an out-of-range row.
							rowLimitReached = true;
							rowsNotExported++;
							continue;
						}

						rowId++;
						// For Excel file, add a new row
						excelRow = firstSheet.createRow(rowId);

						// Processes current row from the ResultSet
						for (int i = 0; i < metaData.getColumnCount(); i++) {

							Cell excelCell = excelRow.createCell(i);
							int colType = metaData.getColumnType(i + 1);

							// Each branch reads the column with the JDBC getter matching its actual type
							// (not always getString()/getDate()), then checks wasNull(): the primitive/date
							// getters below return 0/false/a getDate()-shaped value on a SQL NULL, so
							// without this check a NULL was silently exported as 0/false/a bogus date
							// instead of a blank cell.
							if (colType == Types.DATE) {
								Date date = results.getDate(i + 1);
								if (!results.wasNull()) {
									excelCell.setCellStyle(cellStyleDate);
									excelCell.setCellValue(date);
								}

							} else if (colType == Types.TIME || colType == Types.TIME_WITH_TIMEZONE) {
								Date time = results.getTime(i + 1);
								if (!results.wasNull()) {
									excelCell.setCellStyle(cellStyleTime);
									excelCell.setCellValue(time);
								}

							} else if (colType == Types.TIMESTAMP || colType == Types.TIMESTAMP_WITH_TIMEZONE
									|| colType == OracleJdbcTypes.TIMESTAMPTZ || colType == OracleJdbcTypes.TIMESTAMPLTZ) {
								// getDate() truncates the time-of-day on a TIMESTAMP column - use
								// getTimestamp() so the actual time is preserved instead of showing midnight.
								Date timestamp = results.getTimestamp(i + 1);
								if (!results.wasNull()) {
									excelCell.setCellStyle(cellStyleDateTime);
									excelCell.setCellValue(timestamp);
								}

							} else if (colType == Types.SMALLINT || colType == Types.BIGINT || colType == Types.TINYINT || colType == Types.INTEGER) {
								long longValue = results.getLong(i + 1);
								if (!results.wasNull()) {
									// A double only represents integers exactly up to 2^53 - a BIGINT can
									// exceed that (e.g. a 64-bit snowflake ID). Only fall back to text when
									// the round-trip through double would actually change the value, so
									// ordinary small integers still get a real, sortable Excel number.
									double asDouble = longValue;
									if ((long) asDouble == longValue) {
										excelCell.setCellValue(asDouble);
									} else {
										excelCell.setCellValue(Long.toString(longValue));
									}
								}

							} else if (colType == Types.DECIMAL || colType == Types.NUMERIC) {
								BigDecimal decimalValue = results.getBigDecimal(i + 1);
								if (!results.wasNull()) {
									// Same idea as BIGINT above: DECIMAL/NUMERIC is arbitrary-precision SQL-side,
									// a double is not. Only fall back to text when representing the value as a
									// double would actually lose digits (BigDecimal.valueOf(double) reconstructs
									// the shortest decimal that round-trips to that double).
									double asDouble = decimalValue.doubleValue();
									if (BigDecimal.valueOf(asDouble).compareTo(decimalValue) == 0) {
										excelCell.setCellValue(asDouble);
									} else {
										excelCell.setCellValue(decimalValue.toPlainString());
									}
								}

							} else if (colType == Types.DOUBLE || colType == Types.REAL) {
								double doubleValue = results.getDouble(i + 1);
								if (!results.wasNull()) {
									excelCell.setCellValue(doubleValue);
								}

							} else if (colType == Types.FLOAT) {
								float floatValue = results.getFloat(i + 1);
								if (!results.wasNull()) {
									excelCell.setCellValue(floatValue);
								}

							} else if (colType == Types.BOOLEAN) {
								boolean boolValue = results.getBoolean(i + 1);
								if (!results.wasNull()) {
									excelCell.setCellValue(boolValue);
								}

							} else if (colType == Types.BINARY || colType == Types.OTHER || colType == Types.VARBINARY || colType == Types.LONGVARBINARY || colType == Types.DATALINK) {
								excelCell.setCellValue("Binary content, not exported to Excel");

							} else {
								String cellValueStr = results.getString(i + 1);
								if (cellValueStr != null) {
									if (cellValueStr.length() > 32767) {
										cellValueStr = cellValueStr.substring(0, 32767);
									}
									excelCell.setCellValue(cellValueStr);
								}
							}

						}
					}

					if (rowLimitReached && cmdLineConsole != null) {
						cmdLineConsole.warn("Excel sheet row limit reached (" + (maxDataRowIndex + 1) + " data rows per sheet): "
								+ rowId + " row(s) exported, " + rowsNotExported + " row(s) NOT exported. Consider a text/CSV export for result sets this large.");
					}
				}

				// Close out file
				workbook.write(fos);
				if (fos != null) {
					fos.flush();
					fos.close();
				}
			} catch (SQLException ie) {
				throw new BroadSQLException(ie);
			} catch (RuntimeException re) {
				// Normalizes unexpected POI failures (e.g. an invalid sheet name that slipped past
				// sanitizeSheetName(), or any other POI runtime error) into the exception type the
				// rest of the app expects to catch and display cleanly, instead of an unhandled
				// stack trace - CommandInterruptedException is a checked BroadSQLException, not a
				// RuntimeException, so CTRL+C cancellation still propagates through untouched.
				throw new BroadSQLException(re);
			} finally {
				// SXSSFWorkbook buffers rows to temporary disk files as they are flushed; dispose()
				// removes them. Must run whether the export completed, failed, or was cancelled mid-way
				// (see docs/TODO.md, "Ameliorations de la GUI"), otherwise those temp files leak.
				if (workbook != null) {
					workbook.dispose();
				}
				// Release the file handle even when interrupted mid-export, so a partial file can be
				// deleted right away by the caller (see docs/TODO.md, "Ameliorations de la GUI")
				if (fos != null) {
					try {
						fos.close();
					} catch (IOException ignore) {
						// nothing to do: the file is being abandoned anyway
					}
				}
				try {
					if (results != null) {
						results.close();
					}
				} catch (SQLException e) {
					throw new BroadSQLException(e);
				}
			}
		}
	}

	/**
	 * {@link IQueryExtractor} entry point used by {@link com.upandcoding.broadsql.dao.DatabaseConnection}.
	 * Delegates to {@link #extractToExcel2007(DatabaseDefinition, ShellConsole, String, ResultSet, String)}
	 * - {@code maxRowsOnScreen}, {@code listMode}, {@code screenSep}, {@code sep}, {@code isAppendToFile}
	 * and {@code start} are part of the shared {@link IQueryExtractor} contract but not used for an
	 * Excel export (append and separators are text-format concepts; Excel has no equivalent).
	 */
	@Override
	public void extract(DatabaseDefinition platform, ShellConsole cmdLineConsole, String query, ResultSet results, int maxRowsOnScreen, boolean listMode, String fileName, char screenSep, char sep, boolean isAppendToFile, Instant start)
			throws BroadSQLException, IOException, SQLException {
		extractToExcel2007(platform, cmdLineConsole, query, results, fileName);

	}
}
