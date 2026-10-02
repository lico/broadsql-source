package com.upandcoding.broadsql.controller.errors;

import java.sql.SQLException;

/**
 * Wraps a {@link SQLException} raised while executing SQL the user actually typed or ran ({@code
 * DatabaseConnection#executeSelectQuery}/{@code executeUpdateQuery}), carrying exactly what
 * {@code SqlErrorRenderer} needs to build the readable diagnostic block from
 * docs/SPRINT_0912A_EXPAND_FORMAT_SQL_ERRORS.md, section 6 - the exact SQL text submitted to JDBC
 * (not a reformatted copy) and the connection it was submitted to.
 *
 * <p>A plain {@link BroadSQLException} (as every other JDBC call site in
 * {@code DatabaseConnection} still throws) keeps rendering exactly as before -
 * {@code ShellConsole#error} only takes the richer path for this specific type, so metadata/DDL
 * helper queries that are not "SQL the user ran" are never forced through the SQL renderer (see
 * section 6.2, "do not force unrelated non-SQL application errors through this renderer").
 *
 * <p>{@code operation} (e.g. {@code "SELECT"}, {@code "UPDATE"}) is carried but not currently
 * rendered - kept for the future safe {@code LOAD} engine's reuse of this same renderer for
 * {@link java.sql.BatchUpdateException} (section 6.8), which is explicitly out of scope for this
 * sprint.
 */
public class SqlExecutionException extends BroadSQLException {

	private static final long serialVersionUID = 1L;

	private final String sql;
	private final SQLException sqlException;
	private final String connectionId;
	private final String operation;

	public SqlExecutionException(String sql, SQLException cause, String connectionId, String operation) {
		super(cause);
		this.sql = sql;
		this.sqlException = cause;
		this.connectionId = connectionId;
		this.operation = operation;
	}

	public String getSql() {
		return sql;
	}

	public SQLException getSqlException() {
		return sqlException;
	}

	public String getConnectionId() {
		return connectionId;
	}

	public String getOperation() {
		return operation;
	}
}
