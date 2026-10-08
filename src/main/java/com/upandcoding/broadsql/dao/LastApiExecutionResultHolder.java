package com.upandcoding.broadsql.dao;

/**
 * Static holder for the current {@link LastApiExecutionResult} snapshot - the API-side sibling of
 * {@link LastQueryResultHolder}, exact structural mirror of it (see that class's Javadoc for the full
 * reasoning: {@code CommandApiExecuteEndpoint} has no dependency-injected path back to whichever
 * {@code PULL} invocation might later need this snapshot, and BroadSQL runs one command at a time on a
 * single dedicated worker thread, so a plain {@code volatile} field is sufficient - no concurrent
 * producers are possible).
 */
public final class LastApiExecutionResultHolder {

	private static volatile LastApiExecutionResult current;

	private LastApiExecutionResultHolder() {
	}

	public static void set(LastApiExecutionResult result) {
		current = result;
	}

	public static LastApiExecutionResult get() {
		return current;
	}

	/**
	 * Invalidates any previously held snapshot. Called by {@code CommandApiExecuteEndpoint} at the very
	 * start of every execution attempt (before the policy check, variable resolution, authentication, or
	 * the network call), so a policy refusal, an unresolved variable, an unsupported-authentication
	 * failure, a network error, or a cancellation always leaves {@code PULL API RESULT} with nothing to
	 * export instead of silently re-exporting a stale result from an earlier, unrelated successful
	 * execution (SPRINT XT02 verification finding 1).
	 */
	public static void clear() {
		current = null;
	}
}
