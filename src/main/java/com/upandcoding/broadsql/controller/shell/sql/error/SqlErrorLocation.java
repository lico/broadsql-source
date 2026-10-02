package com.upandcoding.broadsql.controller.shell.sql.error;

/**
 * A position within the exact SQL text submitted to JDBC, reliably established by a
 * {@link SqlErrorLocationResolver} - never guessed. {@code line}/{@code column} are 1-based;
 * {@code offset} is the 0-based character (UTF-16 code unit) index into the submitted SQL string.
 */
public final class SqlErrorLocation {

	private final int offset;
	private final int line;
	private final int column;

	public SqlErrorLocation(int offset, int line, int column) {
		this.offset = offset;
		this.line = line;
		this.column = column;
	}

	public int getOffset() {
		return offset;
	}

	public int getLine() {
		return line;
	}

	public int getColumn() {
		return column;
	}

	/**
	 * Builds a location from a 0-based character offset into {@code text}, counting 1-based line and
	 * column - a CRLF pair counts as a single line break (the column resets right after the
	 * {@code \n}), matching docs/SPRINT_0912A_EXPAND_FORMAT_SQL_ERRORS.md, section 7.4 ("Both
	 * commands must work on multiline current SQL... tested with CRLF and LF input").
	 */
	public static SqlErrorLocation fromOffset(String text, int rawOffset) {
		int offset = Math.max(0, Math.min(rawOffset, text.length()));
		int line = 1;
		int column = 1;
		for (int i = 0; i < offset; i++) {
			char c = text.charAt(i);
			if (c == '\r') {
				if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
					continue; // part of a CRLF pair - the '\n' below performs the line break
				}
				line++;
				column = 1;
			} else if (c == '\n') {
				line++;
				column = 1;
			} else {
				column++;
			}
		}
		return new SqlErrorLocation(offset, line, column);
	}
}
