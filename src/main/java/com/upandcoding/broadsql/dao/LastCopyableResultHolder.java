package com.upandcoding.broadsql.dao;

/**
 * Static holder resolving {@code COPY RESULT}'s "most recent result" invariant across BroadSQL's two
 * independent, pre-existing "last result" mechanisms - the SQL {@code lastSQLQuery} text and
 * {@link LastApiExecutionResultHolder} - neither of which knows the other exists, and neither of which
 * is timestamped (SPRINT XT02B acceptance correction, item 6: {@code COPY RESULT} previously always
 * preferred a held {@code lastSQLQuery} over a newer {@code RUN} result, since it had no way to tell
 * which of the two actually ran last).
 *
 * <p>Deliberately the smallest fix that produces correct semantics for every execution order, per the
 * "invalidating the competing holder" option this class actually implements in its simplest form:
 * rather than tracking a sequence number or timestamp on each side and comparing them, a single slot
 * holds a small {@link LastCopyableResult} tag naming whichever source produced a result last - {@link
 * #recordSql(String)} and {@link #recordApi(LastApiExecutionResult)} are called at the exact two
 * existing call sites that already update {@code lastSQLQuery} ({@code
 * CommandInterpreter#executeMultiStatementLine}) and {@link LastApiExecutionResultHolder} ({@code
 * CommandRun}/{@code CommandApiExecuteEndpoint}'s {@code captureLastApiExecutionResult}), each
 * unconditionally overwriting this slot - so whichever call happened most recently is, by construction,
 * what {@link #get()} returns; no comparison is needed. {@code COPY RESULT} still re-runs
 * {@code lastSQLQuery} itself, or reuses the exact {@link LastApiExecutionResult} object captured here
 * (not a fresh read of {@link LastApiExecutionResultHolder}, which a later failed {@code RUN} attempt
 * may have since cleared for its own, unrelated reason - see {@link LastCopyableResult}'s Javadoc).
 *
 * <p>Same threading assumption as {@link LastApiExecutionResultHolder}: BroadSQL runs one command at a
 * time on a single dedicated worker thread, so a plain {@code volatile} field is sufficient.
 */
public final class LastCopyableResultHolder {

	private static volatile LastCopyableResult current;

	private LastCopyableResultHolder() {
	}

	public static void recordSql(String sqlQuery) {
		current = LastCopyableResult.ofSql(sqlQuery);
	}

	public static void recordApi(LastApiExecutionResult apiResult) {
		current = LastCopyableResult.ofApi(apiResult);
	}

	public static LastCopyableResult get() {
		return current;
	}

	public static void clear() {
		current = null;
	}
}
