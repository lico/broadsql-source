package com.upandcoding.broadsql.dao.export;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A tabular result BroadSQL already holds (SPRINT 2309T: {@code DUMP /}'s last displayed result, or
 * {@code DUMP LIB}'s captured script result), exposed as a plain {@link ResultSet} over a throwaway H2
 * database - the one shape every export writer consumes, so these sources need no writer of their own.
 * Same design as {@code MaterializedApiResult} for {@code API RESULT}.
 *
 * <p>Owns the connection, the statement and the result set; {@link #close()} releases all three
 * best-effort, issuing {@code SHUTDOWN} first so the throwaway database never outlives the command.
 */
public final class MaterializedTabularResult implements AutoCloseable {

	private final Connection connection;
	private final Statement statement;
	private final ResultSet resultSet;

	MaterializedTabularResult(Connection connection, Statement statement, ResultSet resultSet) {
		this.connection = connection;
		this.statement = statement;
		this.resultSet = resultSet;
	}

	/** @return a {@code SELECT *} over the held rows, positioned before the first row */
	public ResultSet getResultSet() {
		return resultSet;
	}

	@Override
	public void close() throws SQLException {
		SQLException failure = null;
		try {
			resultSet.close();
		} catch (SQLException e) {
			failure = e;
		}
		try {
			statement.close();
		} catch (SQLException e) {
			failure = failure == null ? e : failure;
		}
		try (Statement shutdown = connection.createStatement()) {
			shutdown.execute("SHUTDOWN");
		} catch (SQLException e) {
			failure = failure == null ? e : failure;
		}
		try {
			connection.close();
		} catch (SQLException e) {
			failure = failure == null ? e : failure;
		}
		if (failure != null) {
			throw failure;
		}
	}
}
