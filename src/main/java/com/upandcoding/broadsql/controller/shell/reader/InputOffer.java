package com.upandcoding.broadsql.controller.shell.reader;

/**
 * What happened to text offered to the command prompt with {@link ConsoleLineReader#offerInput}: placed on the
 * input line for the user to review and submit, or refused, with the reason. Offered text is never submitted.
 */
public enum InputOffer {

	/** The text is on the prompt's input line, not submitted: the user reviews, edits, and presses Enter. */
	PLACED,
	/** The console cannot receive offered text ({@code activatejline=OFF}, the basic console). */
	UNSUPPORTED,
	/** The console is not waiting for a command: a command is running, or it is asking something else (a password, a confirmation). */
	NOT_AT_PROMPT,
	/** Earlier lines of a statement not yet ended with {@code ;} are waiting: the offered text would join that statement. */
	STATEMENT_PENDING,
	/** The input line already holds typed text, which is never replaced. */
	INPUT_NOT_EMPTY
}
