package com.upandcoding.broadsql.dao.api.tabular;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The outcome of {@link ApiResultMaterializer#materialize} - a throwaway H2 in-memory database holding
 * one table copied from a {@code LastApiExecutionResult}'s tabularized rows, plus a
 * {@code SELECT * FROM ...} {@link ResultSet} already positioned against it. {@code AutoCloseable} so
 * {@code CommandPull}'s existing {@code finally} block can close it exactly like it already closes a SQL
 * source's {@code ResultSet}/{@code Statement} - see docs/BroadSQL XT02 overnight batch plan, sub-sprint 7.
 *
 * <p>Owns the connection, the statement that produced {@link #getResultSet()}, and the result set itself -
 * {@link #close()} releases all three, best-effort (a failure on one does not prevent the others from
 * being attempted), and issues {@code SHUTDOWN} first so the {@code DB_CLOSE_DELAY=-1} database backing
 * this snapshot does not linger in the JVM after the command finishes.
 */
public final class MaterializedApiResult implements AutoCloseable {

	private final Connection connection;
	private final Statement statement;
	private final ResultSet resultSet;

	MaterializedApiResult(Connection connection, Statement statement, ResultSet resultSet) {
		this.connection = connection;
		this.statement = statement;
		this.resultSet = resultSet;
	}

	/** @return a {@code SELECT * FROM ...} result set against the throwaway table - the same shape any other PULL source's {@code ResultSet} has. */
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
		try (Statement shutdown = connection.createStatement()) {
			shutdown.execute("SHUTDOWN");
		} catch (SQLException e) {
			if (failure == null) {
				failure = e;
			}
		}
		try {
			statement.close();
		} catch (SQLException e) {
			if (failure == null) {
				failure = e;
			}
		}
		try {
			connection.close();
		} catch (SQLException e) {
			if (failure == null) {
				failure = e;
			}
		}
		if (failure != null) {
			throw failure;
		}
	}
}
