package com.upandcoding.broadsql.dao.load;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.SqlExecutionException;
import com.upandcoding.broadsql.dao.DatabaseConnection;

/**
 * Executes an already-validated {@link LoadPlan}: one {@code INSERT ... VALUES (?, ?, ...)} prepared
 * once, every row bound as a JDBC parameter (SPRINT 0912B section 5.1 - never string-concatenated),
 * batched internally with {@link Statement#addBatch()}/{@link Statement#executeBatch()} for
 * efficiency (section 13), inside a single transaction that is committed once at the very end or
 * rolled back entirely on any failure (section 12 - "batch boundaries are not transaction
 * boundaries"). The connection's prior autocommit state is always restored before returning, success
 * or failure.
 */
public final class LoadExecutor {

	private static final int BATCH_SIZE = 500;

	private LoadExecutor() {
	}

	public static LoadResult execute(DatabaseConnection sqlDatabase, LoadPlan plan) throws BroadSQLException {
		if (!plan.isReadyToExecute()) {
			throw new BroadSQLException("LOAD refused: preflight validation did not pass. No changes have been made.");
		}

		String insertSql = buildInsertSql(plan);
		Connection connection = sqlDatabase.getDirectConnection();
		String connectionId = sqlDatabase.getPlatform() != null ? sqlDatabase.getPlatform().getId() : null;

		boolean priorAutoCommit;
		try {
			priorAutoCommit = connection.getAutoCommit();
		} catch (SQLException se) {
			throw new BroadSQLException(se);
		}

		List<Object[]> rows = plan.getBoundRows();
		List<String> insertColumns = plan.getInsertColumns();
		int[] insertColumnJdbcTypes = new int[insertColumns.size()];
		for (int i = 0; i < insertColumns.size(); i++) {
			LoadTargetColumn column = plan.getTarget().findColumn(insertColumns.get(i));
			insertColumnJdbcTypes[i] = column != null ? column.getJdbcType() : java.sql.Types.VARCHAR;
		}
		int totalInserted = 0;

		try {
			connection.setAutoCommit(false);
			try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
				int rowsInCurrentBatch = 0;
				int batchStartRow = 1;

				for (int i = 0; i < rows.size(); i++) {
					bindRow(statement, insertColumnJdbcTypes, rows.get(i));
					statement.addBatch();
					rowsInCurrentBatch++;

					boolean isLastRow = (i == rows.size() - 1);
					if (rowsInCurrentBatch == BATCH_SIZE || isLastRow) {
						try {
							int[] counts = statement.executeBatch();
							totalInserted += sumUpdateCounts(counts);
						} catch (BatchUpdateException bue) {
							rollbackQuietly(connection);
							throw batchFailure(insertSql, connectionId, bue, batchStartRow, rowsInCurrentBatch);
						}
						batchStartRow += rowsInCurrentBatch;
						rowsInCurrentBatch = 0;
					}
				}
			}

			connection.commit();
			return new LoadResult(rows.size(), totalInserted, true);

		} catch (SQLException se) {
			rollbackQuietly(connection);
			throw new SqlExecutionException(insertSql, se, connectionId, "INSERT");
		} finally {
			try {
				connection.setAutoCommit(priorAutoCommit);
			} catch (SQLException se) {
				// Best-effort restoration only - the load's own commit/rollback outcome is already decided.
			}
		}
	}

	private static void rollbackQuietly(Connection connection) {
		try {
			connection.rollback();
		} catch (SQLException ignored) {
			// The original failure is what gets reported; a rollback failure on top of it isn't actionable.
		}
	}

	private static String buildInsertSql(LoadPlan plan) {
		StringBuilder columnsClause = new StringBuilder();
		StringBuilder placeholders = new StringBuilder();
		List<String> columns = plan.getInsertColumns();
		for (int i = 0; i < columns.size(); i++) {
			if (i > 0) {
				columnsClause.append(", ");
				placeholders.append(", ");
			}
			columnsClause.append(columns.get(i));
			placeholders.append('?');
		}
		return "INSERT INTO " + plan.getTarget().getQualifiedName() + " (" + columnsClause + ") VALUES (" + placeholders + ")";
	}

	private static void bindRow(PreparedStatement statement, int[] jdbcTypes, Object[] row) throws SQLException {
		for (int i = 0; i < row.length; i++) {
			if (row[i] == null) {
				statement.setNull(i + 1, jdbcTypes[i]);
			} else {
				statement.setObject(i + 1, row[i], jdbcTypes[i]);
			}
		}
	}

	private static int sumUpdateCounts(int[] counts) {
		int sum = 0;
		for (int count : counts) {
			if (count == Statement.SUCCESS_NO_INFO) {
				sum += 1;
			} else if (count >= 0) {
				sum += count;
			}
		}
		return sum;
	}

	/**
	 * A {@link BatchUpdateException} reports (per the JDBC spec) update counts only for the
	 * statements a driver actually attempted before stopping - {@code getUpdateCounts().length} tells
	 * us how many of this batch's rows were attempted, which lets us name the source row range this
	 * batch covered and, when the driver's count is shorter than the batch, the approximate row the
	 * failure landed on. This is deliberately phrased as "at or after" rather than an exact row - the
	 * spec explicitly warns against inventing precision a driver doesn't actually guarantee (section
	 * 14: "never invent precision from ambiguous BatchUpdateException data").
	 */
	private static SqlExecutionException batchFailure(String sql, String connectionId, BatchUpdateException bue,
			int batchStartRow, int batchSize) {
		int[] counts = bue.getUpdateCounts();
		int attempted = counts != null ? counts.length : 0;
		int batchEndRow = batchStartRow + batchSize - 1;
		String rowHint = (attempted > 0 && attempted < batchSize)
				? " (failure at or after source row " + (batchStartRow + attempted - 1) + ")"
				: "";
		SQLException annotated = new SQLException(
				"LOAD batch covering source rows " + batchStartRow + "-" + batchEndRow + rowHint + ": " + bue.getMessage(),
				bue.getSQLState(), bue.getErrorCode(), bue);
		annotated.setNextException(bue.getNextException());
		return new SqlExecutionException(sql, annotated, connectionId, "INSERT");
	}
}
