package com.upandcoding.broadsql.controller.shell.sql;

/**
 * One lexical unit produced by {@link SqlTokenizer}: its {@link SqlTokenType}, its exact source
 * text (never normalized - callers that need a case-insensitive comparison do it themselves), and
 * its {@code [start, end)} character offsets into the original SQL string.
 */
public final class SqlToken {

	private final SqlTokenType type;
	private final String text;
	private final int start;
	private final int end;

	public SqlToken(SqlTokenType type, String text, int start, int end) {
		this.type = type;
		this.text = text;
		this.start = start;
		this.end = end;
	}

	public SqlTokenType getType() {
		return type;
	}

	public String getText() {
		return text;
	}

	public int getStart() {
		return start;
	}

	public int getEnd() {
		return end;
	}

	/** Case-insensitive comparison against a plain-ASCII keyword, e.g. {@code is("SELECT")}. */
	public boolean is(String keyword) {
		return text.equalsIgnoreCase(keyword);
	}

	public boolean isType(SqlTokenType candidate) {
		return type == candidate;
	}

	/** Whitespace and comments carry no syntactic meaning for shape-matching purposes. */
	public boolean isInsignificant() {
		return type == SqlTokenType.WHITESPACE || type == SqlTokenType.LINE_COMMENT || type == SqlTokenType.BLOCK_COMMENT;
	}

	@Override
	public String toString() {
		return type + "(" + text + ")@" + start;
	}
}
