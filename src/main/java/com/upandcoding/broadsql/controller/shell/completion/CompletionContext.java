package com.upandcoding.broadsql.controller.shell.completion;

import java.util.List;

import com.upandcoding.broadsql.controller.shell.sql.SqlToken;

/**
 * Everything a {@link CompletionProvider} needs to decide what to offer at one TAB press - SPRINT
 * 0917-02, section 20. Built once per TAB press by {@link SqlCompletionLexer#analyze}, so providers
 * never reparse the raw line themselves (section 20: "Do not force every completion provider to
 * reparse the raw command line independently").
 *
 * <p>{@code fullBuffer}/{@code cursor} cover the <b>whole pending multi-line statement</b>, not just
 * the single line JLine itself hands the completer - see {@code PendingStatementBufferHolder} for why
 * that distinction exists (section 18: multiline SQL must complete correctly).
 */
public final class CompletionContext {

	private final String fullBuffer;
	private final int cursor;
	private final List<SqlToken> tokens;
	private final boolean insideStringOrComment;
	private final int wordStart;
	private final int wordEnd;
	private final String word;
	private final String qualifier;
	private final List<SqlToken> statementTokensBeforeCursor;
	private final String firstWordOfStatement;
	private final boolean connected;

	CompletionContext(String fullBuffer, int cursor, List<SqlToken> tokens, boolean insideStringOrComment, int wordStart, int wordEnd,
			String word, String qualifier, List<SqlToken> statementTokensBeforeCursor, String firstWordOfStatement, boolean connected) {
		this.fullBuffer = fullBuffer;
		this.cursor = cursor;
		this.tokens = tokens;
		this.insideStringOrComment = insideStringOrComment;
		this.wordStart = wordStart;
		this.wordEnd = wordEnd;
		this.word = word;
		this.qualifier = qualifier;
		this.statementTokensBeforeCursor = statementTokensBeforeCursor;
		this.firstWordOfStatement = firstWordOfStatement;
		this.connected = connected;
	}

	/** The full pending statement text (possibly spanning several already-entered lines - see class javadoc), never just the current JLine line. */
	public String getFullBuffer() {
		return fullBuffer;
	}

	/** Cursor offset into {@link #getFullBuffer()}. */
	public int getCursor() {
		return cursor;
	}

	/** Every lexical token of {@link #getFullBuffer()}, including whitespace/comments, in source order. */
	public List<SqlToken> getTokens() {
		return tokens;
	}

	/** {@code true} when the cursor is inside a string literal, quoted identifier or comment - section 17: no SQL/BroadSQL/metadata completion must be offered here. */
	public boolean isInsideStringOrComment() {
		return insideStringOrComment;
	}

	/** Start offset (into {@link #getFullBuffer()}) of the word currently being completed. */
	public int getWordStart() {
		return wordStart;
	}

	/** End offset (into {@link #getFullBuffer()}) of the word currently being completed - normally equal to {@link #getCursor()}. */
	public int getWordEnd() {
		return wordEnd;
	}

	/** The (possibly empty) word being completed, e.g. {@code "cust"} in {@code SELECT * FROM cust<TAB>}. */
	public String getWord() {
		return word;
	}

	/**
	 * The identifier immediately before a {@code .} right before {@link #getWord()}, or {@code null} -
	 * e.g. {@code "c"} in {@code c.customer_na<TAB>}, {@code "sales"} in {@code sales.<TAB>}. Section 9/10.
	 */
	public String getQualifier() {
		return qualifier;
	}

	/** Significant (non-whitespace, non-comment) tokens of the current statement, from its start up to (not including) the word being completed. */
	public List<SqlToken> getStatementTokensBeforeCursor() {
		return statementTokensBeforeCursor;
	}

	/** Upper-cased first significant token of the current statement (the BroadSQL command or SQL verb), or {@code null} if the statement is empty so far. */
	public String getFirstWordOfStatement() {
		return firstWordOfStatement;
	}

	/** {@code true} if a database connection is currently active - metadata providers must return no candidates otherwise (section 28). */
	public boolean isConnected() {
		return connected;
	}
}
