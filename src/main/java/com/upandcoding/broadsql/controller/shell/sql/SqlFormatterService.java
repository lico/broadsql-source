package com.upandcoding.broadsql.controller.shell.sql;

import java.util.List;
import java.util.Set;

/**
 * Implements {@code FORMAT;} (see docs/SPRINT_0912A_EXPAND_FORMAT_SQL_ERRORS.md, section 5).
 *
 * <p><b>Safety strategy</b>, chosen instead of adopting an external formatter library or writing a
 * regex-based one (both explicitly discouraged by the sprint spec): tokenize with the same
 * {@link SqlTokenizer} {@link SqlWildcardExpander} uses, then re-emit the <em>exact same token
 * text</em> in the <em>exact same order</em>, only deciding the whitespace between tokens. No
 * string literal, comment, quoted identifier, number or keyword is ever rewritten, reordered or
 * dropped - only inter-token spacing and line breaks change. This gives a semantic-safety guarantee
 * that holds unconditionally, by construction, for any input that tokenizes successfully: SQL
 * semantics never depend on how many spaces or newlines separate two tokens, so a transformation
 * that only ever changes that can never change what the statement means. Keyword casing is
 * deliberately left exactly as typed for the same reason - it is a text change, however
 * semantically harmless in practice, and this design's guarantee is that no token text changes at
 * all.
 *
 * <p>The only failure mode is therefore a tokenization failure (unterminated string/comment/quoted
 * identifier) - {@link #format} then reports the reason and leaves the input untouched, per the
 * shared transformation contract.
 */
public final class SqlFormatterService {

	private static final String INDENT = "    ";

	private static final Set<String> NEWLINE_BEFORE = Set.of(
			"FROM", "WHERE", "HAVING", "UNION", "INTERSECT", "EXCEPT", "MINUS", "ON", "SET", "VALUES");

	/** Recognized keywords - used only to decide "no space before (" (function/list call vs. clause). SPRINT 0917-02: now the shared {@link SqlKeywords#ALL}, not a private copy. */
	private static final Set<String> KEYWORDS = SqlKeywords.ALL;

	private static final Set<String> JOIN_WORDS = Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "OUTER");

	private SqlFormatterService() {
	}

	public static SqlFormatResult format(String sql) {
		if (sql == null || sql.isBlank()) {
			return SqlFormatResult.unsupported("No query to format.");
		}

		List<SqlToken> tokens;
		try {
			tokens = SqlTokenizer.tokenize(sql);
		} catch (SqlLexException e) {
			return SqlFormatResult.unsupported("Cannot safely format the current SQL (" + e.getMessage() + "). Current query unchanged.");
		}

		List<SqlToken> content = tokens.stream().filter(t -> !t.isType(SqlTokenType.WHITESPACE)).toList();
		if (content.isEmpty()) {
			return SqlFormatResult.unsupported("No query to format.");
		}

		StringBuilder out = new StringBuilder();
		int depth = 0;
		boolean forceNewlineNext = false;
		for (int i = 0; i < content.size(); i++) {
			SqlToken t = content.get(i);
			SqlToken prev = i > 0 ? content.get(i - 1) : null;

			if (t.isType(SqlTokenType.PUNCTUATION) && t.is(")")) {
				depth--;
			}

			if (prev != null) {
				if (forceNewlineNext) {
					out.append('\n');
					forceNewlineNext = false;
				} else {
					appendSeparator(out, prev, t, depth);
				}
			}

			out.append(t.getText());

			if (t.isType(SqlTokenType.PUNCTUATION) && t.is("(")) {
				depth++;
			}
			if (t.isType(SqlTokenType.LINE_COMMENT)) {
				forceNewlineNext = true;
			}
		}

		return SqlFormatResult.success(out.toString());
	}

	private static void appendSeparator(StringBuilder out, SqlToken prev, SqlToken current, int depthAfterCurrentClose) {
		boolean prevIsOpenParen = prev.isType(SqlTokenType.PUNCTUATION) && prev.is("(");
		boolean prevIsDot = prev.isType(SqlTokenType.PUNCTUATION) && prev.is(".");
		boolean currentIsCloseParen = current.isType(SqlTokenType.PUNCTUATION) && current.is(")");
		boolean currentIsComma = current.isType(SqlTokenType.PUNCTUATION) && current.is(",");
		boolean currentIsDot = current.isType(SqlTokenType.PUNCTUATION) && current.is(".");
		boolean currentIsSemicolon = current.isType(SqlTokenType.PUNCTUATION) && current.is(";");

		if (prevIsOpenParen || prevIsDot || currentIsCloseParen || currentIsComma || currentIsDot || currentIsSemicolon) {
			return;
		}

		boolean prevIsWordNotKeyword = prev.isType(SqlTokenType.WORD) && !isKeyword(prev);
		if (current.isType(SqlTokenType.PUNCTUATION) && current.is("(") && prevIsWordNotKeyword) {
			return;
		}

		// Depth-0 comma: start a new, indented line for the next projection/list item.
		if (depthAfterCurrentClose == 0 && prev.isType(SqlTokenType.PUNCTUATION) && prev.is(",")) {
			out.append('\n').append(INDENT);
			return;
		}

		if (depthAfterCurrentClose == 0 && current.isType(SqlTokenType.WORD)) {
			String word = current.getText().toUpperCase();
			if (NEWLINE_BEFORE.contains(word)) {
				out.append('\n');
				return;
			}
			if ((word.equals("AND") || word.equals("OR"))) {
				out.append('\n').append(INDENT);
				return;
			}
			if (JOIN_WORDS.contains(word) && !isPrecededByJoinWord(prev)) {
				out.append('\n');
				return;
			}
		}

		out.append(' ');
	}

	private static boolean isPrecededByJoinWord(SqlToken prev) {
		return prev.isType(SqlTokenType.WORD) && JOIN_WORDS.contains(prev.getText().toUpperCase());
	}

	private static boolean isKeyword(SqlToken token) {
		return token.isType(SqlTokenType.WORD) && KEYWORDS.contains(token.getText().toUpperCase());
	}
}
