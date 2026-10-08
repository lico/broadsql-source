package com.upandcoding.broadsql.dao.extractors;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.shell.output.ConsoleUtils;
import com.upandcoding.broadsql.controller.shell.output.DisplayLayout;
import com.upandcoding.broadsql.controller.shell.output.TableBorders;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.CommandInterruptedException;
import com.upandcoding.broadsql.controller.shell.commands.CommandCancellation;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.JdbcTypedValueReader;
import com.upandcoding.broadsql.dao.LastCaptureSuppressor;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.pull.TemporalValues;


public class QueryExtractorToScreen implements IQueryExtractor {
	
	private static final Logger log = LoggerFactory.getLogger(QueryExtractorToScreen.class);

	private static final int DEFAULT_COLUMN_SIZE = 20;

	// Some JDBC drivers report a huge but not-quite-Integer.MAX_VALUE display size or precision for
	// an unbounded/large text column (CLOB, NVARCHAR(MAX), etc.) - the pre-existing check just below
	// only special-cases the exact Integer.MAX_VALUE sentinel. Padding to that width would try to
	// allocate a single String of that many characters (worst case, a NULL value padded with dashes -
	// see the leftPad call below), which crashes with OutOfMemoryError regardless of heap size.
	// Capping the display width is safe: StringUtils.rightPad/leftPad never truncate a value already
	// longer than the requested size, so real data is always shown in full - only the alignment
	// padding for shorter/NULL values in that column is capped.
	private static final int MAX_COLUMN_DISPLAY_SIZE = 500;

	/*
	 * Return the size of a column for display purpose. Is the max of column size and title size
	 * @param columnName(String) the column display title
	 * @param columnSize(int) the default column size
	 * @return int max of column size and length
	 */
	private static int getColumnSize(String columnName, int columnSize) {
		int size = 1;
		int sizeTitle = columnName.length();
		size = Math.max(columnSize, sizeTitle);
		return (size);
	}// getColumnSize

	/**
	 * The column's display width in the established (NORMAL) layout: the larger of its label and its
	 * declared display size, 24 for date/time types, capped at {@link #MAX_COLUMN_DISPLAY_SIZE},
	 * {@link #DEFAULT_COLUMN_SIZE} when the driver reports nothing usable. SPRINT 2409K: extracted from the
	 * former per-cell formatter so {@link DisplayLayout} can plan the whole table before any row is printed.
	 */
	static int naturalColumnWidth(ResultSetMetaData metaData, int posColumn) throws SQLException {
		String cName = metaData.getColumnLabel(posColumn + 1);
		int precision = metaData.getPrecision(posColumn + 1);
		int sizeField = metaData.getColumnDisplaySize(posColumn + 1);
		int colType = metaData.getColumnType(posColumn + 1);
		int size = getColumnSize(cName, sizeField);
		// Every column value reaches the screen through ResultSet.getString() regardless of type, so Oracle's
		// proprietary TIMESTAMP WITH (LOCAL) TIME ZONE columns display correctly; this only widens their column
		// to match the other date/time types.
		if (colType == Types.DATE || colType == Types.TIME || colType == Types.TIMESTAMP
				|| colType == OracleJdbcTypes.TIMESTAMPTZ || colType == OracleJdbcTypes.TIMESTAMPLTZ) {
			size = 24;
		}
		if (size <= 0 || size >= 2147483647) {
			size = precision;
		}
		if (size > MAX_COLUMN_DISPLAY_SIZE) {
			size = MAX_COLUMN_DISPLAY_SIZE;
		}
		return size > 0 && size < 2147483647 ? size : DEFAULT_COLUMN_SIZE;
	}

	/** One cell's text under {@code plan}: shortened only when the plan truncates (COMPACT); padding is {@link TableBorders}'. */
	static String fitCell(String value, DisplayLayout.Plan plan, int posColumn) {
		return plan.truncates() ? DisplayLayout.fit(value, plan.width(posColumn)) : value;
	}

	private void extractToScreen(DatabaseDefinition platform, ShellConsole cmdLineConsole, String query, ResultSet results, int maxRowsOnScreen, boolean listMode, char screenSep, Instant start) throws BroadSQLException, IOException, SQLException {

		int lstModeMaxSizeField = 3;

		try {
			if (results != null) {

				// Header row (via metadata)
				// ========================
				ResultSetMetaData metaData = results.getMetaData();
				// SPRINT 2409K: the table's geometry for the current terminal (DisplayLayout); display only
				int[] naturalWidths = new int[metaData.getColumnCount()];
				for (int i = 0; i < naturalWidths.length; i++) {
					naturalWidths[i] = naturalColumnWidth(metaData, i);
				}
				// one separator per column, plus the leading border of every line
				DisplayLayout.Plan plan = DisplayLayout.plan(naturalWidths, 1, 1);
				int[] widths = new int[naturalWidths.length];
				for (int i = 0; i < widths.length; i++) {
					widths[i] = plan.width(i);
				}
				// COMPACT falls back to the same vertical layout as list mode when the grid cannot fit
				boolean verticalLayout = listMode || plan.isVertical();
				List<String> columnLabels = new ArrayList<>();
				List<String> headerCells = new ArrayList<>();
				for (int i = 0; i < metaData.getColumnCount(); i++) {
					columnLabels.add(metaData.getColumnLabel(i + 1));
					int colSize = metaData.getColumnLabel(i + 1).length();
					if (colSize > lstModeMaxSizeField) {
						lstModeMaxSizeField = colSize;
					}
					headerCells.add(fitCell(metaData.getColumnLabel(i + 1), plan, i));
				} // for
				// The standard table format (TableBorders): bordered on both sides, with a closing separator
				String rowSep = TableBorders.separator(widths, plan.gap(), screenSep);
				// a vertical value is shortened only in COMPACT, to what fits after "COLUMN: "
				int verticalValueWidth = plan.truncates() ? Math.max(10, plan.availableColumns() - lstModeMaxSizeField - 2) : 0;
				if (!verticalLayout && cmdLineConsole != null) {
					cmdLineConsole.writeln(rowSep);
					cmdLineConsole.writeln(TableBorders.row(headerCells, widths, plan.gap(), screenSep));
					cmdLineConsole.writeln(rowSep);
				}

				// Data rows (actual ResultSet entries)
				// ====================================
				// log.debug("max rows: "+maxRowsOnScreen);
				int rowId = 0;
				// Computes total nr of rows in the result set
				boolean isTruncated = false;
				// Captures at most LastQueryResult.MAX_ROWS rows for <@last:column> (see
				// docs/TECHNICAL_CHANGE.md) as a side effect of the display iteration already
				// happening here - no extra ResultSet fetch. totalRowCount keeps counting every row
				// actually iterated (bounded separately by maxRowsOnScreen above) even once capture
				// itself stops, so LastQueryResult.exceedsLimit() can report the true count.
				List<String[]> capturedRows = new ArrayList<>();
				// SPRINT 2309T (#161): the same capture, typed, for DUMP / (see LastQueryResult).
				int columnCount = metaData.getColumnCount();
				LastQueryResult.ColumnInfo[] columnInfos = new LastQueryResult.ColumnInfo[columnCount];
				for (int i = 0; i < columnCount; i++) {
					columnInfos[i] = new LastQueryResult.ColumnInfo(metaData.getColumnLabel(i + 1), metaData.getColumnType(i + 1),
							metaData.getColumnTypeName(i + 1), metaData.getPrecision(i + 1), metaData.getScale(i + 1));
				}
				List<Object[]> capturedTypedRows = new ArrayList<>();
				int totalRowCount = 0;
				while (results.next()) {
					// CTRL+C support: abort the display without closing the connection
					// (see docs/TODO.md, "Ameliorations de la GUI")
					if (CommandCancellation.isRequested()) {
						throw new CommandInterruptedException("Command interrupted");
					}
					// If output to screen and current row above max display on screen, then break
					if (maxRowsOnScreen > 0 && rowId >= maxRowsOnScreen) {
						isTruncated = true;
						break;
					}

					rowId++;
					totalRowCount++;
					String[] rawValues = capturedRows.size() < LastQueryResult.MAX_ROWS ? new String[metaData.getColumnCount()] : null;
					// The typed copy (DUMP /) keeps every displayed row: its only bound is MaxRowsOnScreen, which
					// already bounds this loop. The 5000-row cap above applies to <@last:...> only.
					Object[] typedValues = new Object[columnCount];
					List<String> rowCells = verticalLayout ? null : new ArrayList<>(columnCount);

					// Processes current row from the ResultSet
					for (int i = 0; i < metaData.getColumnCount(); i++) {
						String cell = results.getString(i + 1);
						if (rawValues != null) {
							// results.getString() already returns null exactly for SQL NULL (JDBC spec) -
							// captured before the display-only "null" text normalization below, so a real
							// NULL and a literal "null" string value are never confused here.
							rawValues[i] = cell;
						}
						typedValues[i] = cell == null ? null : snapshotValue(results, i + 1, columnInfos[i], cell);
						if (cell == null || cell.equalsIgnoreCase("null")) {
							cell = "";
						}
						if (!verticalLayout) {
							// Tab mode
							rowCells.add(fitCell(cell, plan, i));
						} else {
							// List mode
							String header = metaData.getColumnLabel(i + 1);
							header = StringUtils.rightPad(header, lstModeMaxSizeField, " ");
							if (cmdLineConsole != null) {
								cmdLineConsole.writeln(header + ": " + (verticalValueWidth > 0 ? DisplayLayout.fit(cell, verticalValueWidth) : cell));
							}
						}
					}
					if (rawValues != null) {
						capturedRows.add(rawValues);
					}
					capturedTypedRows.add(typedValues);
					if (cmdLineConsole != null) {
						// a grid row, or the blank line between two vertical records
						cmdLineConsole.writeln(verticalLayout ? "" : TableBorders.row(rowCells, widths, plan.gap(), screenSep));
					}
				} // while
				if (!verticalLayout && cmdLineConsole != null && rowId > 0) {
					// footer; with no data row, the separator under the header already closes the table
					cmdLineConsole.writeln(rowSep);
				}
				if (!LastCaptureSuppressor.isSuppressed()) {
					LastQueryResultHolder.set(new LastQueryResult(columnLabels, capturedRows, totalRowCount,
							platform != null ? platform.getId() : null, columnInfos, capturedTypedRows, isTruncated));
				}
				if (cmdLineConsole != null) {
					//log.debug("Start: {}", start.getNano());
					Instant end = Instant.now();
					String elapsedTime = ConsoleUtils.getFormattedElapsedTime(start, end);
					String footer = "";
					if (isTruncated) {
						// 12.01.2015: not working for the moment, because no good method for computing
						// totalRowId
						// footer = "" + rowId + " rows fetched (out of " + totalRowId + " rows
						// returned).";
						footer = "" + rowId + " " + elapsedTime + " (results truncated to first " + rowId + " rows).";
					} else {
						footer = "" + rowId + " " + elapsedTime + ".";
					}
					cmdLineConsole.writeln("");
					cmdLineConsole.writeln(footer);
					cmdLineConsole.writeln("");
				}
			}
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		} finally {
			try {
				if (results != null) {
					results.close();
				}
			} catch (SQLException ie) {
				throw new BroadSQLException(ie);
			}
		}
	}

	/**
	 * The typed copy of one displayed cell for {@code DUMP /}, best effort: the typed copy is optional, so a value
	 * the driver displays ({@code getString()}) but whose typed getter rejects it ({@code NaN} or {@code Infinity}
	 * in a {@code NUMERIC}/{@code DECFLOAT} column, a zero date...) never makes the display fail. It is kept as an
	 * {@link LastQueryResult.UnavailableValue}, which {@code DUMP /} refuses to export, instead of a value of
	 * another type. No exception leaves this method, so the statement is never treated as failed (which would
	 * roll back pending work).
	 */
	static Object snapshotValue(ResultSet results, int columnIndex, LastQueryResult.ColumnInfo column, String text) {
		try {
			return JdbcTypedValueReader.typedValue(results, columnIndex, TemporalValues.effectiveType(column.jdbcType(), column.typeName()), text);
		} catch (SQLException | RuntimeException e) {
			log.debug("Typed snapshot of column {} failed: {}", column.label(), e.getLocalizedMessage());
			return new LastQueryResult.UnavailableValue(text, e.getLocalizedMessage());
		}
	}

	@Override
	public void extract(DatabaseDefinition platform, ShellConsole cmdLineConsole, String query, ResultSet results, int maxRowsOnScreen, boolean listMode, String fileName, char screenSep, char sep, boolean isAppendToFile, Instant start)
			throws BroadSQLException, IOException, SQLException {

		extractToScreen(platform, cmdLineConsole, query, results, maxRowsOnScreen, listMode, screenSep, start);

	}
}
