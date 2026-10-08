package com.upandcoding.broadsql.controller.shell.completion;

import java.util.ArrayList;
import java.util.List;

import com.upandcoding.broadsql.controller.shell.sql.SqlLexException;
import com.upandcoding.broadsql.controller.shell.sql.SqlToken;
import com.upandcoding.broadsql.controller.shell.sql.SqlTokenType;
import com.upandcoding.broadsql.controller.shell.sql.SqlTokenizer;

/**
 * Builds a {@link CompletionContext} from raw text and a cursor position - SPRINT 0917-02, section 16
 * (cursor-position completion) and section 17 (strings/comments). Reuses {@link SqlTokenizer}
 * (SPRINT 0912A's lexer, already shared by {@code SqlWildcardExpander}/{@code SqlFormatterService})
 * rather than writing a second lexer (section 17: "Reuse existing SQL lexical utilities").
 *
 * <p>{@link SqlTokenizer} itself fails closed on an unterminated string/quoted-identifier/block
 * comment - the right behavior for its own callers, which only ever tokenize <i>complete</i> SQL, but
 * wrong here: while the user is actively typing (e.g. {@code SELECT 'sel<TAB>}), the trailing
 * construct is legitimately still open. {@link #tolerantTokenize} catches that case and turns it into
 * one final "open" token spanning to the end of the buffer, so a partially-typed string/comment is
 * still recognized as one instead of aborting completion entirely.
 */
public final class SqlCompletionLexer {

	private SqlCompletionLexer() {
	}

	private static boolean isIdentifierChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
	}

	/**
	 * @param fullBuffer the whole pending statement text (see {@link CompletionContext}'s javadoc) -
	 *                    never just the current single line
	 * @param cursor      offset into {@code fullBuffer} where completion was requested
	 * @param connected   whether a database connection is currently active
	 */
	public static CompletionContext analyze(String fullBuffer, int cursor, boolean connected) {
		String text = fullBuffer == null ? "" : fullBuffer;
		int pos = Math.max(0, Math.min(cursor, text.length()));

		TolerantResult tokenized = tolerantTokenize(text);
		List<SqlToken> tokens = tokenized.tokens;

		boolean insideStringOrComment = false;
		for (SqlToken token : tokens) {
			if (isLiteralOrComment(token.getType()) && isInside(token, tokenized.openToken, pos)) {
				insideStringOrComment = true;
				break;
			}
		}

		int wordStart = pos;
		while (wordStart > 0 && isIdentifierChar(text.charAt(wordStart - 1))) {
			wordStart--;
		}
		int wordEnd = pos;
		while (wordEnd < text.length() && isIdentifierChar(text.charAt(wordEnd))) {
			wordEnd++;
		}
		String word = text.substring(wordStart, pos);

		String qualifier = null;
		if (wordStart > 0 && text.charAt(wordStart - 1) == '.') {
			int qualEnd = wordStart - 1;
			int qualStart = qualEnd;
			while (qualStart > 0 && isIdentifierChar(text.charAt(qualStart - 1))) {
				qualStart--;
			}
			if (qualStart < qualEnd) {
				qualifier = text.substring(qualStart, qualEnd);
			}
		}

		// Current statement = everything after the last top-level ';' ending at or before the cursor.
		int statementStart = 0;
		for (SqlToken token : tokens) {
			if (token.isType(SqlTokenType.PUNCTUATION) && ";".equals(token.getText()) && token.getEnd() <= pos) {
				statementStart = token.getEnd();
			}
		}

		List<SqlToken> statementTokensBeforeCursor = new ArrayList<>();
		String firstWordOfStatement = null;
		for (SqlToken token : tokens) {
			if (token.getStart() < statementStart || token.getEnd() > wordStart || token.isInsignificant()) {
				continue;
			}
			statementTokensBeforeCursor.add(token);
			if (firstWordOfStatement == null && token.isType(SqlTokenType.WORD)) {
				firstWordOfStatement = token.getText().toUpperCase(java.util.Locale.ROOT);
			}
		}

		return new CompletionContext(text, pos, tokens, insideStringOrComment, wordStart, wordEnd, word, qualifier,
				statementTokensBeforeCursor, firstWordOfStatement, connected);
	}

	private static boolean isLiteralOrComment(SqlTokenType type) {
		return type == SqlTokenType.STRING_LITERAL || type == SqlTokenType.QUOTED_IDENTIFIER || type == SqlTokenType.LINE_COMMENT
				|| type == SqlTokenType.BLOCK_COMMENT;
	}

	/** Line comments and the one possible unterminated "open" token have no real closing delimiter - being exactly at their end offset still counts as inside. Every other (properly closed) literal/comment token does not. */
	private static boolean isInside(SqlToken token, SqlToken openToken, int cursor) {
		boolean noClosingDelimiter = token == openToken || token.getType() == SqlTokenType.LINE_COMMENT;
		return noClosingDelimiter ? (token.getStart() < cursor && cursor <= token.getEnd()) : (token.getStart() < cursor && cursor < token.getEnd());
	}

	private static final class TolerantResult {
		final List<SqlToken> tokens;
		/** The synthetic trailing token standing in for an unterminated construct, or {@code null} if tokenization completed normally. */
		final SqlToken openToken;

		TolerantResult(List<SqlToken> tokens, SqlToken openToken) {
			this.tokens = tokens;
			this.openToken = openToken;
		}
	}

	private static TolerantResult tolerantTokenize(String text) {
		try {
			return new TolerantResult(SqlTokenizer.tokenize(text), null);
		} catch (SqlLexException e) {
			int offset = Math.max(0, Math.min(e.getOffset(), text.length()));
			List<SqlToken> prefixTokens;
			try {
				prefixTokens = new ArrayList<>(SqlTokenizer.tokenize(text.substring(0, offset)));
			} catch (SqlLexException impossible) {
				// The prefix up to the reported offset was already consumed cleanly by the failing call
				// above, so re-tokenizing it alone cannot fail - defensive fallback only.
				prefixTokens = new ArrayList<>();
				offset = 0;
			}
			char opener = offset < text.length() ? text.charAt(offset) : '\'';
			SqlTokenType type;
			if (opener == '"' || opener == '`') {
				type = SqlTokenType.QUOTED_IDENTIFIER;
			} else if (opener == '/') {
				type = SqlTokenType.BLOCK_COMMENT;
			} else {
				type = SqlTokenType.STRING_LITERAL;
			}
			SqlToken openToken = new SqlToken(type, text.substring(offset), offset, text.length());
			List<SqlToken> all = new ArrayList<>(prefixTokens);
			all.add(openToken);
			return new TolerantResult(all, openToken);
		}
	}
}
