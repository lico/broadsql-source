package com.upandcoding.broadsql.controller.shell.completion;

/**
 * Exposes {@code CommandInterpreter.run()}'s own multi-line accumulation buffer (its local {@code bs},
 * the text of every already-entered line of a not-yet-{@code ;}-terminated statement) to the TAB
 * completion stack - SPRINT 0917-02, section 18.
 *
 * <p>JLine's {@code LineReader}/{@code Completer} only ever sees <b>one line at a time</b> - the line
 * currently being edited. {@code CommandInterpreter.run()} is the only place that already knows about
 * everything typed <i>before</i> that line for the statement in progress; without this holder, a
 * completer has no way to see it. Mirrors {@code com.upandcoding.broadsql.dao.api.ApiSessionContextHolder}'s
 * existing pattern for exactly the same reason: bridging one piece of session-scoped state across two
 * classes that otherwise have no direct reference to each other, without threading it through every
 * intermediate constructor.
 *
 * <p>{@code CommandInterpreter.run()} updates this immediately before each blocking
 * {@code console.readLine()} call - by the time a completer runs, it therefore always reflects exactly
 * what has already been accumulated for the statement in progress, never a stale or future value.
 */
public final class PendingStatementBufferHolder {

	private static final ThreadLocal<String> PENDING = new ThreadLocal<>();

	private PendingStatementBufferHolder() {
	}

	/** @param pendingText everything accumulated so far for the current, not-yet-terminated statement, or {@code null}/blank if nothing is pending. */
	public static void set(String pendingText) {
		PENDING.set(pendingText);
	}

	public static void clear() {
		PENDING.remove();
	}

	/** @return the pending text set by {@link #set}, or {@code ""} if none. */
	public static String get() {
		String value = PENDING.get();
		return value == null ? "" : value;
	}
}
