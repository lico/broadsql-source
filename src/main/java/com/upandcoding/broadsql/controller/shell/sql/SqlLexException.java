package com.upandcoding.broadsql.controller.shell.sql;

/**
 * Thrown by {@link SqlTokenizer} when the SQL text cannot be tokenized safely - an unterminated
 * string literal, quoted identifier or block comment. Both {@link SqlWildcardExpander} and
 * {@link SqlFormatterService} treat this as "fail closed": leave the current SQL unchanged and
 * report the reason, never guess past a lexical ambiguity.
 */
public class SqlLexException extends Exception {

	private static final long serialVersionUID = 1L;

	private final int offset;

	public SqlLexException(String message, int offset) {
		super(message);
		this.offset = offset;
	}

	/** 0-based character offset into the source text where the problem was detected. */
	public int getOffset() {
		return offset;
	}
}
