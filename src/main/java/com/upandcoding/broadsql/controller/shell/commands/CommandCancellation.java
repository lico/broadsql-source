package com.upandcoding.broadsql.controller.shell.commands;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation flag for the command currently executing, set by the CTRL+C signal
 * handler in CommandInterpreter and checked periodically by long-running loops (query extractors).
 *
 * Deliberately NOT based on Thread.interrupt(): interrupting a thread that happens to be blocked in
 * the JDBC driver's own file I/O (e.g. H2 embedded storage, which uses interruptible NIO channels
 * internally) closes that channel as an unwanted side effect and corrupts the connection - observed
 * when interrupting a CONNECT ("IO Exception: null" from H2). See docs/TECHNICAL_CHANGE.md.
 *
 * <p>SPRINT 0110A: a second, run-level flag. {@link #reset()} (called at the start of every command)
 * clears only the statement flag, so a CTRL+C that arrives during a statement of a Script, or between two
 * of its statements, still cancels the whole top-level run (spec section 13): the Script executor checks
 * {@link #isRunCancelled()} before and after every statement. {@link #resetRun()} is called when a top-level
 * run or a typed line starts.
 */
public final class CommandCancellation {

	private static final AtomicBoolean requested = new AtomicBoolean(false);
	private static final AtomicBoolean runRequested = new AtomicBoolean(false);

	private CommandCancellation() {
	}

	/** CTRL+C: cancels the running statement and the run it belongs to. */
	public static void request() {
		requested.set(true);
		runRequested.set(true);
	}

	public static boolean isRequested() {
		return requested.get();
	}

	/** Clears the statement flag only (start of every command). */
	public static void reset() {
		requested.set(false);
	}

	/** Whether CTRL+C was pressed since the current top-level run (or typed line) started. */
	public static boolean isRunCancelled() {
		return runRequested.get();
	}

	/** Clears both flags: a new top-level run or typed line starts. */
	public static void resetRun() {
		runRequested.set(false);
		requested.set(false);
	}
}
