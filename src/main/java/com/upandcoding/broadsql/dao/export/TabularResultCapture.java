package com.upandcoding.broadsql.dao.export;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The explicit result-capture scope behind {@code DUMP LIB} (SPRINT 2309T, #163). While a scope is open,
 * every tabular result a SQL statement produces ({@code DatabaseConnection#executeSelectQuery}) goes into
 * the scope's {@link TabularResultSpool} instead of the screen, so the script's own final result is known
 * when it ends. Nothing is read from, or written to, the global last-result snapshot
 * ({@code LastQueryResultHolder}): a {@code DUMP LIB} never exports an unrelated earlier result, and never
 * replaces the one {@code DUMP /} would export.
 *
 * <p>Same shape as {@code LastCaptureSuppressor}/{@code CommandCancellation}: a static holder, because
 * BroadSQL runs one command at a time (on successive worker threads, so not a {@code ThreadLocal}) and the
 * SQL layer has no execution-context object to thread a spool through. Scopes nest (a {@code DUMP LIB}
 * inside a captured script captures into its own spool).
 */
public final class TabularResultCapture {

	private static final Deque<TabularResultSpool> ACTIVE = new ArrayDeque<>();

	private TabularResultCapture() {
	}

	public static synchronized void begin(TabularResultSpool spool) {
		ACTIVE.push(spool);
	}

	/** Closes the scope opened with {@code spool}; always call from a {@code finally}. */
	public static synchronized void end(TabularResultSpool spool) {
		ACTIVE.remove(spool);
	}

	/** @return the innermost open scope's spool, or {@code null} when no capture is in progress */
	public static synchronized TabularResultSpool current() {
		return ACTIVE.peek();
	}
}
