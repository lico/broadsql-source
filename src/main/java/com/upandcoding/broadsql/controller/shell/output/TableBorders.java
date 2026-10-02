package com.upandcoding.broadsql.controller.shell.output;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * The one implementation of BroadSQL's horizontal table format (docs/02. guidelines/TABLE_OUTPUT_STANDARD.md):
 *
 * <pre>
 * |--------------|-------------|
 * |File          |Group        |
 * |--------------|-------------|
 * |QR1.sql       |MyWorld      |
 * |--------------|-------------|
 * </pre>
 *
 * Every line starts and ends with the column separator ({@code |}); separator lines fill each column with
 * {@code -}. Callers decide widths, truncation and which lines to print (top border, header, header/data
 * separator, rows, footer); this class only builds the lines, so no table can drift from the rule.
 * {@code gap} is the number of spaces (or, on separator lines, {@code -}) on each side of every column:
 * 1 in the WIDE display mode, 0 otherwise.
 */
public final class TableBorders {

	/** Column separator of every horizontal table. */
	public static final char COLUMN_SEPARATOR = '|';
	/** Fill character of separator lines. */
	public static final char FILL = '-';

	private TableBorders() {
	}

	/** A separator line ({@code |----|---|}): top border, header/data separator and footer. */
	public static String separator(int[] widths, int gap) {
		return separator(widths, gap, COLUMN_SEPARATOR);
	}

	/** As {@link #separator(int[], int)}, with the column separator configured for query results ({@code ScreenSeparator}). */
	public static String separator(int[] widths, int gap, char columnSeparator) {
		StringBuilder line = new StringBuilder().append(columnSeparator);
		for (int width : widths) {
			line.append(StringUtils.repeat(FILL, width + 2 * gap)).append(columnSeparator);
		}
		return line.toString();
	}

	/** A header or data line ({@code |ID  |NAME|}); each cell is padded to its width, never shortened here. */
	public static String row(List<String> cells, int[] widths, int gap) {
		return row(cells, widths, gap, COLUMN_SEPARATOR);
	}

	/** As {@link #row(List, int[], int)}, with the column separator configured for query results ({@code ScreenSeparator}). */
	public static String row(List<String> cells, int[] widths, int gap, char columnSeparator) {
		String space = StringUtils.repeat(' ', gap);
		StringBuilder line = new StringBuilder().append(columnSeparator);
		for (int i = 0; i < widths.length; i++) {
			String cell = i < cells.size() && cells.get(i) != null ? cells.get(i) : "";
			line.append(space).append(StringUtils.rightPad(cell, widths[i])).append(space).append(columnSeparator);
		}
		return line.toString();
	}
}
