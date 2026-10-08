package com.upandcoding.broadsql.controller.shell.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * A hand-written, dependency-free SQL lexer shared by {@link SqlWildcardExpander} and
 * {@link SqlFormatterService} (see SPRINT 0912A, docs/SPRINT_0912A_EXPAND_FORMAT_SQL_ERRORS.md,
 * section 2 - "do not begin implementation by introducing a new parser or formatting dependency").
 *
 * <p>This is deliberately <b>not</b> a SQL parser: it never builds a syntax tree and never
 * validates that the statement is well-formed SQL. It only draws reliable boundaries around the
 * handful of constructs both {@code EXPAND;} and {@code FORMAT;} must never look inside - string
 * literals, quoted identifiers, and line/block comments (hints included) - so that neither feature
 * can mistake a {@code *} inside {@code COUNT(*)}, an arithmetic expression, a literal or a comment
 * for a projection wildcard, and so that {@code FORMAT;} can reflow whitespace without ever
 * altering a single character of a string, comment or identifier's own text.
 *
 * <p>Tokenization fails closed: an unterminated string literal, quoted identifier or block comment
 * raises {@link SqlLexException} rather than guessing where it would have ended. Both callers treat
 * that as "leave the current SQL unchanged."
 */
public final class SqlTokenizer {

	private SqlTokenizer() {
	}

	/** Two-character operators recognized as a single {@link SqlTokenType#PUNCTUATION} token. */
	private static final String[] TWO_CHAR_OPERATORS = { "<=", ">=", "<>", "!=", "||", ":=" };

	public static List<SqlToken> tokenize(String sql) throws SqlLexException {
		List<SqlToken> tokens = new ArrayList<>();
		if (sql == null) {
			return tokens;
		}
		int len = sql.length();
		int i = 0;
		while (i < len) {
			char c = sql.charAt(i);

			if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f') {
				int start = i;
				while (i < len && isWhitespace(sql.charAt(i))) {
					i++;
				}
				tokens.add(new SqlToken(SqlTokenType.WHITESPACE, sql.substring(start, i), start, i));
				continue;
			}

			if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
				int start = i;
				i += 2;
				while (i < len && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
					i++;
				}
				tokens.add(new SqlToken(SqlTokenType.LINE_COMMENT, sql.substring(start, i), start, i));
				continue;
			}

			if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
				int start = i;
				i += 2;
				boolean closed = false;
				while (i + 1 < len) {
					if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
						i += 2;
						closed = true;
						break;
					}
					i++;
				}
				if (!closed) {
					throw new SqlLexException("Unterminated block comment", start);
				}
				tokens.add(new SqlToken(SqlTokenType.BLOCK_COMMENT, sql.substring(start, i), start, i));
				continue;
			}

			if (c == '\'') {
				int start = i;
				i = consumeQuoted(sql, i, '\'');
				tokens.add(new SqlToken(SqlTokenType.STRING_LITERAL, sql.substring(start, i), start, i));
				continue;
			}

			if (c == '"' || c == '`') {
				int start = i;
				i = consumeQuoted(sql, i, c);
				tokens.add(new SqlToken(SqlTokenType.QUOTED_IDENTIFIER, sql.substring(start, i), start, i));
				continue;
			}

			if (Character.isDigit(c)) {
				int start = i;
				i++;
				while (i < len && Character.isDigit(sql.charAt(i))) {
					i++;
				}
				if (i < len && sql.charAt(i) == '.' && i + 1 < len && Character.isDigit(sql.charAt(i + 1))) {
					i++;
					while (i < len && Character.isDigit(sql.charAt(i))) {
						i++;
					}
				}
				if (i < len && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E')) {
					int expStart = i;
					int j = i + 1;
					if (j < len && (sql.charAt(j) == '+' || sql.charAt(j) == '-')) {
						j++;
					}
					if (j < len && Character.isDigit(sql.charAt(j))) {
						while (j < len && Character.isDigit(sql.charAt(j))) {
							j++;
						}
						i = j;
					} else {
						i = expStart;
					}
				}
				tokens.add(new SqlToken(SqlTokenType.NUMBER, sql.substring(start, i), start, i));
				continue;
			}

			if (isWordStart(c)) {
				int start = i;
				i++;
				while (i < len && isWordPart(sql.charAt(i))) {
					i++;
				}
				tokens.add(new SqlToken(SqlTokenType.WORD, sql.substring(start, i), start, i));
				continue;
			}

			String twoChar = (i + 1 < len) ? sql.substring(i, i + 2) : null;
			if (twoChar != null && isTwoCharOperator(twoChar)) {
				tokens.add(new SqlToken(SqlTokenType.PUNCTUATION, twoChar, i, i + 2));
				i += 2;
				continue;
			}

			tokens.add(new SqlToken(SqlTokenType.PUNCTUATION, String.valueOf(c), i, i + 1));
			i++;
		}
		return tokens;
	}

	private static boolean isWhitespace(char c) {
		return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f';
	}

	private static boolean isWordStart(char c) {
		return Character.isLetter(c) || c == '_' || c == '$' || c == '#';
	}

	private static boolean isWordPart(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
	}

	private static boolean isTwoCharOperator(String candidate) {
		for (String op : TWO_CHAR_OPERATORS) {
			if (op.equals(candidate)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Consumes a {@code quote}-delimited token starting at {@code start} (which holds the opening
	 * quote character), honoring the standard SQL doubled-quote escape ({@code ''} inside a string,
	 * {@code ""} inside a double-quoted identifier, {@code ``} inside a backtick identifier), and
	 * returns the index just past the closing quote.
	 */
	private static int consumeQuoted(String sql, int start, char quote) throws SqlLexException {
		int len = sql.length();
		int i = start + 1;
		while (true) {
			if (i >= len) {
				throw new SqlLexException("Unterminated " + (quote == '\'' ? "string literal" : "quoted identifier"), start);
			}
			char c = sql.charAt(i);
			if (c == quote) {
				if (i + 1 < len && sql.charAt(i + 1) == quote) {
					i += 2;
					continue;
				}
				return i + 1;
			}
			i++;
		}
	}
}
