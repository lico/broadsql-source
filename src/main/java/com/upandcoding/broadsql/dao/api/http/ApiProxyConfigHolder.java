package com.upandcoding.broadsql.dao.api.http;

/**
 * The application-wide, resolved {@link ApiProxyConfig} - SPRINT XT02A (URL-Native API Execution),
 * section 19. Populated once at startup ({@code com.upandcoding.broadsql.controller.BroadSQL.main}) from
 * the INI file; every {@link ApiHttpTransport} constructed afterward (deep inside the execution engine,
 * with no direct access to {@code ConsoleSettings}) reads it here - the same static-holder shape as
 * {@link com.upandcoding.broadsql.dao.api.ApiSessionContextHolder} already uses for other cross-cutting
 * session/config state, kept deliberately separate from the transport's own default constructor so a
 * test can still construct an isolated {@link ApiHttpTransport} with an explicit config via its second
 * constructor, bypassing this holder entirely.
 */
public final class ApiProxyConfigHolder {

	private static volatile ApiProxyConfig current = ApiProxyConfig.none();

	private ApiProxyConfigHolder() {
	}

	public static void set(ApiProxyConfig config) {
		current = config == null ? ApiProxyConfig.none() : config;
	}

	public static ApiProxyConfig get() {
		return current;
	}
}
