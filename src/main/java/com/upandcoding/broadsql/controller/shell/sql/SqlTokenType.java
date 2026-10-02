package com.upandcoding.broadsql.controller.shell.sql;

/**
 * The kinds of lexical unit {@link SqlTokenizer} produces. Deliberately coarse - SPRINT 0912A's
 * consumers ({@link SqlWildcardExpander}, {@link SqlFormatterService}) never need a full SQL
 * grammar, only enough structure to tell a projection wildcard from {@code COUNT(*)}/multiplication,
 * and to reflow whitespace without ever touching the text of a string, comment or identifier.
 */
public enum SqlTokenType {

	/** A run of spaces/tabs/newlines. Never touched, only ever replaced wholesale between tokens. */
	WHITESPACE,

	/** {@code -- ...} up to (not including) the line terminator. Content preserved verbatim. */
	LINE_COMMENT,

	/** {@code /* ... *}{@code /}, including Oracle-style hints ({@code /*+ ... *}{@code /}). Content preserved verbatim. */
	BLOCK_COMMENT,

	/** A {@code '...'} string literal, {@code ''} escapes included verbatim in the token text. */
	STRING_LITERAL,

	/** A {@code "..."} or {@code `...`} quoted identifier, escapes included verbatim in the token text. */
	QUOTED_IDENTIFIER,

	/** A numeric literal (digits, at most one decimal point, optional exponent). */
	NUMBER,

	/** A maximal run of identifier/keyword characters ({@code [A-Za-z0-9_$#]}, not leading with a digit). */
	WORD,

	/** A single punctuation/operator character, or one of the recognized two-character operators. */
	PUNCTUATION
}
