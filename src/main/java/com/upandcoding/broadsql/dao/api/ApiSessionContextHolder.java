package com.upandcoding.broadsql.dao.api;

/**
 * Static holder for the current {@link ApiSessionContext} - the "active API" sibling of
 * {@code LastApiExecutionResultHolder}, same reasoning: {@code CommandConnect}/{@code CommandDisconnect}/
 * {@code CommandRun}/{@code SHOW ENDPOINTS}/the shell prompt have no dependency-injected path to share
 * this state, and BroadSQL runs one command at a time on a single dedicated worker thread, so a plain
 * {@code volatile} field needs no further synchronization (SPRINT XT02-7B).
 */
public final class ApiSessionContextHolder {

	private static volatile ApiSessionContext current;

	private ApiSessionContextHolder() {
	}

	public static void set(ApiSessionContext context) {
		current = context;
	}

	public static ApiSessionContext get() {
		return current;
	}

	public static void clear() {
		current = null;
	}
}
