package com.upandcoding.broadsql.controller.shell.scripts;

/**
 * SPRINT 0110A: how one statement of a Script (or of a typed line) ended (spec section 12.2). Computed by the
 * executor from every error channel: a thrown exception, an error printed by the command
 * ({@code ShellConsole.error}), an outcome a command states explicitly (a script call reports its called run),
 * and cancellation.
 */
public enum StatementOutcome {
	SUCCESS, FAILED, CANCELLED
}
