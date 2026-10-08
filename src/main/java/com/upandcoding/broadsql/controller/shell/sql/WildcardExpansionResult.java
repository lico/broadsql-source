package com.upandcoding.broadsql.controller.shell.sql;

/**
 * The outcome of {@link SqlWildcardExpander#expand}: either the expanded SQL, or a concise reason
 * the current SQL was left unchanged. There is no partial/guessed outcome - see the shared
 * transformation contract in docs/SPRINT_0912A_EXPAND_FORMAT_SQL_ERRORS.md, section 3.
 */
public final class WildcardExpansionResult {

	private final boolean supported;
	private final String expandedSql;
	private final String reason;

	private WildcardExpansionResult(boolean supported, String expandedSql, String reason) {
		this.supported = supported;
		this.expandedSql = expandedSql;
		this.reason = reason;
	}

	public static WildcardExpansionResult success(String expandedSql) {
		return new WildcardExpansionResult(true, expandedSql, null);
	}

	public static WildcardExpansionResult unsupported(String reason) {
		return new WildcardExpansionResult(false, null, reason);
	}

	public boolean isSupported() {
		return supported;
	}

	public String getExpandedSql() {
		return expandedSql;
	}

	public String getReason() {
		return reason;
	}
}
