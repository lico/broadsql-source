package com.upandcoding.broadsql.controller.shell.style;

/**
 * SPRINT 2409K: the semantic roles BroadSQL emits. Code says what a piece of text is; the active
 * {@link Theme} alone decides how it looks, through {@link TerminalStyle}. No other class writes ANSI
 * escape sequences.
 */
public enum StyleRole {
	ERROR,
	WARNING,
	INFO,
	SUCCESS,

	/** The prompt's fixed parts ({@code > }, the {@code [API ...]} brackets). */
	PROMPT,
	/** The current connection id in the prompt. */
	PROMPT_CONNECTION,
	/** The environment name in the prompt's API segment. */
	PROMPT_ENVIRONMENT,
	/** The connection id in the prompt, when the connection's Environment is flagged Production (replaces {@link #PROMPT_CONNECTION}). */
	PROMPT_PRODUCTION,

	SQL_KEYWORD,
	SQL_LITERAL,
	SQL_COMMENT,

	/** A BroadSQL command keyword typed at the prompt. */
	COMMAND,
	/** The arguments following a BroadSQL command keyword. */
	COMMAND_ARGUMENT,

	/** The matching part of TAB completion candidates in JLine's completion menu. */
	COMPLETION
}
