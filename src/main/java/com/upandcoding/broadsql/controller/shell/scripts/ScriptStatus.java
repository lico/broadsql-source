package com.upandcoding.broadsql.controller.shell.scripts;

/**
 * SPRINT 0110A: the final status of a Script run (spec section 14.2). A future non-interactive entry point maps
 * them to process exit codes ({@link #exitCode()}, spec 14.6); nothing uses that mapping yet.
 */
public enum ScriptStatus {
	/** Arguments valid, preflight passed, every statement here and in nested runs succeeded, the run reached its end. */
	SUCCESS(0),
	/** The run reached its end and at least one statement failed in it or in a nested run. */
	COMPLETED_WITH_ERRORS(3),
	/** Argument evaluation failed, preflight failed, or the run was ended by {@code ON ERROR STOP}. */
	FAILED(1),
	/** The user cancelled the run. */
	CANCELLED(130);

	private final int exitCode;

	ScriptStatus(int exitCode) {
		this.exitCode = exitCode;
	}

	/** The reserved batch exit code of this status (spec 14.6): SUCCESS 0, FAILED 1, COMPLETED_WITH_ERRORS 3, CANCELLED 130. */
	public int exitCode() {
		return exitCode;
	}
}
