package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.util.ArrayList;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT 2409K: locates every {@code <@...>} list-source macro of a statement, each with the exact
 * {@code >} that closes it. Replaces the previous text-search loop of {@code CommandUtils.substituteMacros}
 * ({@code substringBetween(query, "<@", ">")} then a replace-all of the trimmed token), which had three
 * delimiter defects: an opener inside a string literal or comment ({@code NOTE = '<@'}) captured the next,
 * unrelated {@code >} of the statement (e.g. {@code AMOUNT > 100}); an unclosed {@code <@} anywhere after
 * an ordinary {@code >} threw a {@link NullPointerException}; and a token with surrounding spaces
 * ({@code <@ ids.txt >}) never matched its own replace-all and looped forever.
 *
 * <p>Rules, applied in one left-to-right pass:
 * <ul>
 * <li>a {@code <@} opens a macro only in plain statement text: never inside a {@code '...'} string literal
 * (with {@code ''} escapes), a {@code "..."} quoted identifier, a {@code --} line comment or a
 * {@code /* ... *}{@code /} block comment;
 * <li>the macro closes at the first {@code >} after its own {@code <@}, on the same line: no list source
 * form (file path, {@code csv:}, {@code excel:}, {@code last:}, {@code clipboard}) contains a line break, so
 * an opener with no {@code >} before the end of its line is reported as unclosed instead of borrowing a
 * {@code >} from a later line;
 * <li>scanning resumes after that closing {@code >}, so every {@code >} before an opener, between two
 * macros, or after the last one is ordinary SQL and is never looked at as a delimiter.
 * </ul>
 */
public final class ListSourceMacroScanner {

	/** One {@code <@token>} occurrence: {@code [start, end)} covers {@code <@} through the closing {@code >}. */
	public static final class Macro {
		private final int start;
		private final int end;
		private final String token;

		Macro(int start, int end, String token) {
			this.start = start;
			this.end = end;
			this.token = token;
		}

		public int getStart() {
			return start;
		}

		public int getEnd() {
			return end;
		}

		/** The text between {@code <@} and {@code >}, trimmed. */
		public String getToken() {
			return token;
		}
	}

	private ListSourceMacroScanner() {
	}

	public static List<Macro> scan(String sql) throws BroadSQLException {
		List<Macro> macros = new ArrayList<>();
		if (sql == null) {
			return macros;
		}
		int n = sql.length();
		int i = 0;
		while (i < n) {
			char c = sql.charAt(i);
			char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
			if (c == '\'' || c == '"') {
				i = skipQuoted(sql, i, c);
			} else if (c == '-' && next == '-') {
				i = skipToEndOfLine(sql, i);
			} else if (c == '/' && next == '*') {
				int close = sql.indexOf("*/", i + 2);
				i = close < 0 ? n : close + 2;
			} else if (c == '<' && next == '@') {
				int close = closingDelimiter(sql, i + 2);
				if (close < 0) {
					throw new BroadSQLException("Unclosed list source '" + excerpt(sql, i) + "': a <@...> reference must end with '>' on the same line");
				}
				macros.add(new Macro(i, close + 1, sql.substring(i + 2, close).trim()));
				i = close + 1;
			} else {
				i++;
			}
		}
		return macros;
	}

	/** Index of the {@code >} closing a macro whose body starts at {@code from}, or -1 if the line ends first. */
	private static int closingDelimiter(String sql, int from) {
		for (int j = from; j < sql.length(); j++) {
			char c = sql.charAt(j);
			if (c == '>') {
				return j;
			}
			if (c == '\n' || c == '\r') {
				return -1;
			}
		}
		return -1;
	}

	private static int skipQuoted(String sql, int open, char quote) {
		int j = open + 1;
		while (j < sql.length()) {
			if (sql.charAt(j) == quote) {
				if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
					j += 2;
					continue;
				}
				return j + 1;
			}
			j++;
		}
		return sql.length();
	}

	private static int skipToEndOfLine(String sql, int from) {
		int j = from;
		while (j < sql.length() && sql.charAt(j) != '\n' && sql.charAt(j) != '\r') {
			j++;
		}
		return j;
	}

	private static String excerpt(String sql, int start) {
		int end = skipToEndOfLine(sql, start);
		String text = sql.substring(start, end).trim();
		return text.length() > 60 ? text.substring(0, 60) + "..." : text;
	}
}
