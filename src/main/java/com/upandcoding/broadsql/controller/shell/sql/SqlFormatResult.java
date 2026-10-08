package com.upandcoding.broadsql.controller.shell.sql;

/** The outcome of {@link SqlFormatterService#format}: mirrors {@link WildcardExpansionResult}. */
public final class SqlFormatResult {

	private final boolean supported;
	private final String formattedSql;
	private final String reason;

	private SqlFormatResult(boolean supported, String formattedSql, String reason) {
		this.supported = supported;
		this.formattedSql = formattedSql;
		this.reason = reason;
	}

	public static SqlFormatResult success(String formattedSql) {
		return new SqlFormatResult(true, formattedSql, null);
	}

	public static SqlFormatResult unsupported(String reason) {
		return new SqlFormatResult(false, null, reason);
	}

	public boolean isSupported() {
		return supported;
	}

	public String getFormattedSql() {
		return formattedSql;
	}

	public String getReason() {
		return reason;
	}
}
