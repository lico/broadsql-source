package com.upandcoding.broadsql.dao.extractors;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.shell.output.ConsoleUtils;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.CommandInterruptedException;
import com.upandcoding.broadsql.controller.shell.commands.CommandCancellation;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;


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

	/*
	 * For display of a row on the screen, returns a column nicely formatted
	 * @param value(String) the column value
	 * @param metaData(ResultSetMetaData) formatting data related to the column
	 * @param posColumn(int) position of the column in the row
	 * @param separator(char) the caracter to use for separating values
	 * @return the nicely formatted column
	 */
	private static String getFormattedColumnForScreenDisplay(String value, ResultSetMetaData metaData, int posColumn, char separator) throws SQLException {
		String result = "-";
		if (value != null) {
			result = value;
		}

		// String cName = metaData.getColumnName(posColumn + 1);
		String cName = metaData.getColumnLabel(posColumn + 1);
		int precision = metaData.getPrecision(posColumn + 1);
		int sizeField = metaData.getColumnDisplaySize(posColumn + 1);
		int colType = metaData.getColumnType(posColumn + 1);
		// log.debug("sizeField="+sizeField);
		// log.debug("sizePrecision="+precision);
		int size = getColumnSize(cName, sizeField);
		// log.debug("size="+size);

		// Every column value reaches here through ResultSet.getString() regardless of type (see the
		// caller), so Oracle's proprietary TIMESTAMP WITH TIME ZONE/WITH LOCAL TIME ZONE columns
		// already display correctly with no code change - this only widens their column to match the
		// other date/time types instead of falling back to DEFAULT_COLUMN_SIZE below.
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
		if (size > 0 && size < 2147483647) {
			if (value != null) {
				result = StringUtils.rightPad(value, size, " ");
			} else {
				result = StringUtils.leftPad("", size, "-");
			}
			result = "" + result + "" + separator;
		} else {
			if (value != null) {
				result = StringUtils.rightPad(value, DEFAULT_COLUMN_SIZE, " ");
			} else {
				result = StringUtils.leftPad("", DEFAULT_COLUMN_SIZE, "-");
			}
			result = "" + result + "" + separator;
		}
		return (result);
	}// getFormattedColumnForScreenDisplay

	private void extractToScreen(ShellConsole cmdLineConsole, String query, ResultSet results, int maxRowsOnScreen, boolean listMode, char screenSep, Instant start) throws BroadSQLException, IOException, SQLException {

		int lstModeMaxSizeField = 3;

		try {
			if (results != null) {

				// Header row (via metadata)
				// ========================
				ResultSetMetaData metaData = results.getMetaData();
				String rowSep = "";
				String rowHead = "";
				for (int i = 0; i < metaData.getColumnCount(); i++) {
					int colSize = metaData.getColumnLabel(i + 1).length();
					if (colSize > lstModeMaxSizeField) {
						lstModeMaxSizeField = colSize;
					}
					rowSep = rowSep + getFormattedColumnForScreenDisplay(null, metaData, i, screenSep);
					String padded = getFormattedColumnForScreenDisplay(metaData.getColumnLabel(i + 1), metaData, i, screenSep);
					rowHead = rowHead + padded;
				} // for
				if (!listMode && cmdLineConsole != null) {
					cmdLineConsole.writeln(rowSep);
					cmdLineConsole.writeln(rowHead);
					cmdLineConsole.writeln(rowSep);
				}

				// Data rows (actual ResultSet entries)
				// ====================================
				// log.debug("max rows: "+maxRowsOnScreen);
				int rowId = 0;
				// Computes total nr of rows in the result set
				boolean isTruncated = false;
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

					// Processes current row from the ResultSet
					for (int i = 0; i < metaData.getColumnCount(); i++) {
						String cell = results.getString(i + 1);
						if (cell == null || cell.equalsIgnoreCase("null")) {
							cell = "";
						}
						// String padded = getFormattedColumnForScreenDisplay(cell, metaData, i,
						// screenSep);
						String padded = getFormattedColumnForScreenDisplay(cell, metaData, i, screenSep);
						if (!listMode) {
							// Tab mode
							if (cmdLineConsole != null) {
								cmdLineConsole.write(padded);
							}
						} else {
							// List mode
							String header = metaData.getColumnLabel(i + 1);
							header = StringUtils.rightPad(header, lstModeMaxSizeField, " ");
							if (cmdLineConsole != null) {
								cmdLineConsole.writeln(header + ": " + cell);
							}
						}
					}
					if (cmdLineConsole != null) {
						cmdLineConsole.writeln("");
					}
				} // while
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

	@Override
	public void extract(DatabaseDefinition platform, ShellConsole cmdLineConsole, String query, ResultSet results, int maxRowsOnScreen, boolean listMode, String fileName, char screenSep, char sep, boolean isAppendToFile, Instant start)
			throws BroadSQLException, IOException, SQLException {
		
		extractToScreen(cmdLineConsole, query, results, maxRowsOnScreen, listMode, screenSep, start);

	}
}
