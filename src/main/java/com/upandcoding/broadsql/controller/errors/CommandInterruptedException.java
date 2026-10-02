package com.upandcoding.broadsql.controller.errors;

/**
 * Thrown when the user cancels the currently executing command with CTRL+C. Caught centrally by
 * CommandInterpreter, which reports it and returns to the prompt without closing the current
 * database connection. See docs/TODO.md ("Ameliorations de la GUI").
 */
public class CommandInterruptedException extends BroadSQLException {

	private static final long serialVersionUID = 1L;

	public CommandInterruptedException(String message) {
		super(message);
	}
}
