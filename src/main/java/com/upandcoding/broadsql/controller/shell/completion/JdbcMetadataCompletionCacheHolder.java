package com.upandcoding.broadsql.controller.shell.completion;

/**
 * Process-wide access to the one {@link JdbcMetadataCompletionCache} instance - SPRINT 0917-02,
 * section 26. Mirrors {@code com.upandcoding.broadsql.dao.api.ApiSessionContextHolder}'s existing pattern
 * for the same reason: {@link JdbcMetadataCompletionProvider} (constructed once, at startup, alongside
 * the JLine completer) and {@code CommandInterpreter} (which must invalidate the cache after DDL and
 * after a connection change - neither of which the completer itself ever sees) need to reach the exact
 * same cache instance without threading an extra constructor parameter through several unrelated
 * classes just for this.
 */
public final class JdbcMetadataCompletionCacheHolder {

	private static final JdbcMetadataCompletionCache INSTANCE = new JdbcMetadataCompletionCache();

	private JdbcMetadataCompletionCacheHolder() {
	}

	public static JdbcMetadataCompletionCache get() {
		return INSTANCE;
	}
}
