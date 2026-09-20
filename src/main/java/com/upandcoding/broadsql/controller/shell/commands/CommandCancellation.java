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
 */
public final class CommandCancellation {

	private static final AtomicBoolean requested = new AtomicBoolean(false);

	private CommandCancellation() {
	}

	public static void request() {
		requested.set(true);
	}

	public static boolean isRequested() {
		return requested.get();
	}

	public static void reset() {
		requested.set(false);
	}
}
