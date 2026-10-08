package com.upandcoding.broadsql.dao.pull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * {@code BROADSQL_PULL_AUDIT} - the reserved, append-only provenance table {@code PULL ... AS H2}
 * maintains inside every destination H2 database (see docs/EXPORT_TO_H2.md, "H2 export provenance
 * (BROADSQL_PULL_AUDIT)"). One row is appended for every successful {@link PullToH2Exporter#overwrite}/
 * {@link PullToH2Exporter#append} run, recording the destination table, the exact source query, the
 * successful-completion timestamp, the source BroadSQL connection id, the row count written, and the
 * PULL mode ({@code OVERWRITE}/{@code APPEND}).
 *
 * <p><b>Execution-level provenance, not row-level data history</b>: this table records "a PULL of query
 * Q into table T wrote N rows at time X", never anything about individual rows (first/last seen, a
 * fingerprint, inserted/changed/deleted). A future incremental/delta-capture feature needing row-level
 * history is a separate concern with its own metadata - the only connection between the two is that a
 * future delta refresh should still generate one ordinary row here describing that refresh execution.
 *
 * <p><b>Append-only</b>: every successful PULL execution adds one row, even when refreshing the same
 * target table repeatedly - so {@code SELECT * FROM BROADSQL_PULL_AUDIT WHERE TARGET_TABLE = 'X' ORDER
 * BY EXECUTED_AT DESC} shows the full refresh history, not just the latest state. There is no
 * upsert-by-target-table behavior and none is planned - the data volume is inherently tiny (one row per
 * PULL execution, not per source row).
 *
 * <p><b>Reserved name, enforced at two independent layers</b>: {@link #isReservedName} is checked by
 * {@link PullCommandParser} (so {@code PULL ... TO <name>.BROADSQL_PULL_AUDIT AS H2} is rejected before
 * any database is touched) and, defensively, by {@link PullToH2Exporter#overwrite}/{@link
 * PullToH2Exporter#append} themselves, in case a caller ever reaches those methods without going through
 * the parser.
 *
 * <p><b>Legacy/foreign database safety</b>: {@link #ensureExists} creates the table automatically the
 * first time a PULL needs it (no manual migration required for an H2 file created before this feature
 * existed), but if a table already named {@code BROADSQL_PULL_AUDIT} exists with a different column set
 * - a user's own table that happens to collide with this reserved name - it is left completely
 * untouched and {@link BroadSQLException} is thrown instead of silently adopting, dropping, or
 * overwriting it.
 */
final class PullAuditTable {

	static final String TABLE_NAME = "BROADSQL_PULL_AUDIT";

	private static final Set<String> EXPECTED_COLUMNS = new HashSet<>(Arrays.asList(
			"ID", "TARGET_TABLE", "QUERY_TEXT", "EXECUTED_AT", "SOURCE_CONNECTION", "ROW_COUNT", "PULL_MODE"));

	private PullAuditTable() {
	}

	/** Case-insensitive: {@code targetTable} is a PULL destination name, never itself quoted/cased specially. */
	static boolean isReservedName(String targetTable) {
		return TABLE_NAME.equalsIgnoreCase(targetTable);
	}

	/**
	 * Creates {@value #TABLE_NAME} if it does not exist yet in {@code connection}'s database. If a table
	 * by that name already exists, its column set must match {@link #EXPECTED_COLUMNS} exactly (H2 folds
	 * unquoted identifiers to upper case, so this comparison is effectively case-insensitive already) -
	 * anything else is treated as a foreign, incompatible table and rejected without being touched.
	 *
	 * <p>Called on every successful {@link PullToH2Exporter#overwrite}/{@link PullToH2Exporter#append}
	 * run, inside the same transaction, before the audit row is inserted and before the caller's own
	 * {@code commit()} - cheap (one metadata query) and idempotent, so there is no separate migration
	 * step for an H2 file created before this feature existed.
	 */
	static void ensureExists(Connection connection) throws SQLException, BroadSQLException {
		Set<String> actualColumns = fetchColumnNames(connection);
		if (actualColumns.isEmpty()) {
			try (Statement ddl = connection.createStatement()) {
				ddl.execute("CREATE TABLE " + TABLE_NAME + " ("
						+ "ID BIGINT AUTO_INCREMENT PRIMARY KEY, "
						+ "TARGET_TABLE VARCHAR(255) NOT NULL, "
						+ "QUERY_TEXT VARCHAR NOT NULL, "
						+ "EXECUTED_AT TIMESTAMP NOT NULL, "
						+ "SOURCE_CONNECTION VARCHAR(255), "
						+ "ROW_COUNT BIGINT NOT NULL, "
						+ "PULL_MODE VARCHAR(20) NOT NULL)");
			}
			return;
		}
		if (!actualColumns.equals(EXPECTED_COLUMNS)) {
			throw new BroadSQLException("A table named '" + TABLE_NAME + "' already exists in this database, but its columns "
					+ "don't match what BroadSQL expects for its own PULL provenance metadata (expected: " + EXPECTED_COLUMNS
					+ ", found: " + actualColumns + "). BroadSQL will not use, overwrite, or drop this table - rename or remove "
					+ "it yourself first, or use a different target H2 database.");
		}
	}

	/**
	 * Appends one audit row - call only after the export itself has fully succeeded (the target table
	 * already dropped/recreated or created, every row already written), inside the same transaction, and
	 * only immediately before the caller's own {@code commit()} - see {@link PullToH2Exporter#overwrite}/
	 * {@link PullToH2Exporter#append}. {@code executedAt} is the successful-completion timestamp (the
	 * row-copy loop has already finished by the time this is called), not the start time.
	 *
	 * @param sourceConnectionId the same connection identifier {@code PULL ... AS XLSX/ODS} already
	 *                           records in its own {@code QUERIES} info tab ({@code
	 *                           sqlDatabase.getPlatform().getId()}) - never a JDBC URL, never a database
	 *                           name, never credentials; {@code null} when there is no current connection
	 * @param rowCount           the number of rows the export actually wrote, exactly as returned to the
	 *                           caller - a {@code long} so a very large export is represented exactly
	 */
	static void recordSuccess(Connection connection, String targetTable, String queryText, String sourceConnectionId, long rowCount, String pullMode)
			throws SQLException {
		try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + TABLE_NAME
				+ " (TARGET_TABLE, QUERY_TEXT, EXECUTED_AT, SOURCE_CONNECTION, ROW_COUNT, PULL_MODE) VALUES (?, ?, ?, ?, ?, ?)")) {
			insert.setString(1, targetTable);
			insert.setString(2, queryText);
			insert.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
			if (sourceConnectionId != null) {
				insert.setString(4, sourceConnectionId);
			} else {
				insert.setNull(4, Types.VARCHAR);
			}
			insert.setLong(5, rowCount);
			insert.setString(6, pullMode);
			insert.executeUpdate();
		}
	}

	private static Set<String> fetchColumnNames(Connection connection) throws SQLException {
		Set<String> columns = new HashSet<>();
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ?")) {
			ps.setString(1, TABLE_NAME);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					columns.add(rs.getString(1).toUpperCase(Locale.ROOT));
				}
			}
		}
		return columns;
	}
}
