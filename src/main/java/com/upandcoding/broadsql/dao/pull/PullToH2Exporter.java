package com.upandcoding.broadsql.dao.pull;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Engine behind {@code PULL ... TO <name>.<table> AS H2} (see docs/EXPORT_TO_H2.md, section B): copies
 * a query's current results into a table of a target H2 database - either an existing, registered
 * connection reused as-is, or one freshly created and registered by the caller ({@code CommandPull}),
 * which either way resolves to a plain {@link DatabaseDefinition} before calling this class. Two modes:
 * {@link #overwrite}, which drops and recreates the target table from scratch every run, and
 * {@link #append}, which never drops or updates anything - only ever creates the table on its first run
 * and inserts rows afterward.
 *
 * <p>Standalone by design: opens its own, dedicated JDBC connection to the target H2 database
 * rather than going through the {@code sqlDatabase} singleton
 * ({@link com.upandcoding.broadsql.dao.DatabaseConnection}), which only ever holds one live connection
 * at a time. This lets a PULL run with the source query's connection and the H2 target connection
 * open simultaneously without changing that singleton architecture (see docs/TODO.md, "Passer d'une
 * connexion singleton a un pool de sessions").
 *
 * <p>The target table is never given a primary key, unique constraint, or index - PULL only parks a
 * query's results as a plain table, it does not replicate the source's constraints. Column names
 * come from the query's own {@code ResultSetMetaData.getColumnLabel()}, so a column aliased in the
 * source query (e.g. {@code A.TOTO AS TUTU}) is named {@code TUTU} in the target table, not
 * {@code TOTO}. Every identifier is quoted, so mixed case, reserved words, and special characters in
 * a column label are preserved exactly rather than folded to upper case. Two result columns sharing
 * the same label (realistic once {@code MODE APPEND KEY(...)} allows {@code JOIN}, e.g.
 * {@code SELECT c.id, o.id FROM customer c JOIN orders o ...}) are rejected explicitly by
 * {@link #resolveColumnPlan} rather than reaching H2 as a raw duplicate-column DDL error.
 *
 * <p><b>Failure behavior, verified empirically against a real H2 2.3.232 database</b> (H2's
 * {@code DROP TABLE}/{@code CREATE TABLE} auto-commit immediately, regardless of the connection's
 * auto-commit setting - a {@code rollback()} issued afterward does not undo them): if a column type
 * can't be mapped, that check runs before any DDL, so the existing target table is left completely
 * untouched. If the row-copy loop fails partway through (a data error on some row), the target table
 * has already been dropped and recreated with the query's new shape - that part cannot be rolled
 * back - but the rollback still discards every row batched so far, leaving the new, empty table
 * rather than a partially loaded one. Either way, PULL never leaves a target table half-populated;
 * a failure either changes nothing or leaves an empty table.
 */
public class PullToH2Exporter {

	private static final int BATCH_SIZE = 1000;

	/**
	 * JDBC types a {@code MODE APPEND KEY(<column>)} key column is allowed to have - anything with a
	 * well-defined {@code >} ordering, since the key is used purely as a delta watermark (see
	 * docs/EXPORT_TO_H2.md, "Modes"). Deliberately excludes text/boolean types, where "greater than"
	 * either isn't meaningful for this purpose or invites a lexical-vs-value ordering trap.
	 */
	private static final Set<Integer> ORDERABLE_KEY_TYPES = new HashSet<>(Arrays.asList(
			Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
			Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE,
			Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE));

	/**
	 * Drops {@code targetTable} in {@code target} if it exists, recreates it from
	 * {@code sourceResults}'s current shape, and loads all of its rows.
	 *
	 * @param target        a registered connection whose {@code getDbType()} is H2 - the caller must
	 *                      check this beforehand (see {@code SpringPropertiesConfig.DBTYPE_H2}, and
	 *                      {@code CommandLinkTable} for the existing pattern)
	 * @param targetTable   the table name to (re)create, unquoted (quoting is applied internally)
	 * @param sourceResults the query's result set, positioned before the first row; consumed to
	 *                      completion by this method, but not closed - the caller owns it
	 * @return the number of rows written
	 */
	public int overwrite(DatabaseDefinition target, String targetTable, ResultSet sourceResults) throws BroadSQLException, SQLException {

		ResultSetMetaData metaData = sourceResults.getMetaData();

		// Resolve every column's target DDL type up front, against the query's own metadata, before
		// touching the target database at all - so a column PULL can't map never drops the existing
		// table (docs/EXPORT_TO_H2.md: OVERWRITE is "deliberately brutal, and fully predictable").
		String quotedTable = quoteIdentifier(targetTable);
		ColumnPlan plan = resolveColumnPlan(metaData);

		Connection targetConnection = openH2Connection(target);
		try {
			targetConnection.setAutoCommit(false);

			try (Statement ddl = targetConnection.createStatement()) {
				ddl.execute("DROP TABLE IF EXISTS " + quotedTable);
				ddl.execute("CREATE TABLE " + quotedTable + " (" + plan.columnDefs + ")");
			}

			int rowsWritten = insertRows(targetConnection, quotedTable, plan.quotedColumns, plan.columnTypes, sourceResults);

			targetConnection.commit();
			return rowsWritten;

		} catch (SQLException e) {
			rollbackQuietly(targetConnection);
			throw e;
		} finally {
			targetConnection.close();
		}
	}

	/**
	 * {@code MODE APPEND KEY(<column>)} (see docs/EXPORT_TO_H2.md, "Modes"): if {@code targetTable}
	 * does not exist yet, creates it from {@code sourceResults}'s shape and loads every row (a first
	 * full load, exactly like {@link #overwrite}'s DDL step but without a preceding drop, since there is
	 * nothing to drop). If it already exists, no DDL runs at all - the query's columns are matched by
	 * name against the target's actual columns (case-insensitively; an existing table's columns keep
	 * whatever case they were first created with) and every row from {@code sourceResults} is inserted
	 * as-is; existing rows are never touched. Either way, no row is ever updated or deleted - the
	 * "insert only, never touch an existing row" guarantee that distinguishes {@code APPEND} from the
	 * future {@code MERGE}.
	 *
	 * <p>{@code sourceResults} is expected to already carry only the rows the caller decided are new
	 * (see {@code CommandPull}, which injects a {@code <keyColumn> > <watermark>} filter into the source
	 * query itself via {@link PullSourceShapeValidator#appendKeyFilter} when {@code tableExisted} and the
	 * target already holds rows) - this method does not re-check keys against the target's existing
	 * rows, it only validates that the key column is present and has an orderable type.
	 *
	 * @param target        a registered connection whose {@code getDbType()} is H2
	 * @param targetTable   the table name, unquoted (quoting is applied internally)
	 * @param keyColumn     the key column named in {@code KEY(...)}, exactly as typed - matched
	 *                      case-insensitively against both the query's result columns and, when the
	 *                      table already exists, the target's actual columns
	 * @param sourceResults the query's result set, positioned before the first row; consumed to
	 *                      completion by this method, but not closed - the caller owns it
	 * @param tableExisted  whether {@code targetTable} already existed in {@code target} - as previously
	 *                      determined by {@link #tableExists}, before the (possibly filtered) source
	 *                      query was even run, so the caller can build that filter correctly
	 * @return the number of rows written
	 */
	public int append(DatabaseDefinition target, String targetTable, String keyColumn, ResultSet sourceResults, boolean tableExisted)
			throws BroadSQLException, SQLException {

		ResultSetMetaData metaData = sourceResults.getMetaData();
		String quotedTable = quoteIdentifier(targetTable);
		ColumnPlan plan = resolveColumnPlan(metaData);
		checkKeyType(metaData, keyColumn);
		// NULL-key safety (docs/EXPORT_TO_H2.md, "APPEND KEY(...) opened up to joined queries") is
		// enforced by the caller (CommandPull), via checkKeyNullability, before this method is even
		// called - deliberately *after* its own checkKeyType call there (see checkKeyNullability's
		// Javadoc: a wrong-type key should fail with a type error, not a NULL-safety one, if it's both).
		// It needs the pre-DDL ResultSetMetaData too, but on the same ResultSet the caller already
		// holds, so there is no reason to duplicate that check here.

		Connection targetConnection = openH2Connection(target);
		try {
			targetConnection.setAutoCommit(false);

			String[] insertColumnNames;
			if (tableExisted) {
				insertColumnNames = matchAgainstExistingColumns(targetConnection, targetTable, plan.quotedColumns, metaData);
			} else {
				try (Statement ddl = targetConnection.createStatement()) {
					ddl.execute("CREATE TABLE " + quotedTable + " (" + plan.columnDefs + ")");
				}
				insertColumnNames = plan.quotedColumns;
			}

			int rowsWritten = insertRows(targetConnection, quotedTable, insertColumnNames, plan.columnTypes, sourceResults);

			targetConnection.commit();
			return rowsWritten;

		} catch (SQLException e) {
			rollbackQuietly(targetConnection);
			throw e;
		} finally {
			targetConnection.close();
		}
	}

	/**
	 * Whether the {@code KEY(<column>)} column has an orderable type - anything with a well-defined
	 * {@code >}, since the key is used purely as a delta watermark (see docs/EXPORT_TO_H2.md, "Modes").
	 * Deliberately excludes text/boolean types, where "greater than" either is not meaningful for this
	 * purpose or invites a lexical-vs-value ordering trap. Extracted out of {@link #append} so
	 * {@code CommandPull} can call it before {@link #checkKeyNullability}: a key column that is both the
	 * wrong type <i>and</i> nullable should fail with a type error, not a NULL-safety one - the type
	 * error is the more fundamental problem, and naming a fix for it ("use a numeric or date/time
	 * column") is more directly actionable than a nullability warning that does not even apply once the
	 * type itself is fixed.
	 *
	 * @throws BroadSQLException if the key column's type is not in {@link #ORDERABLE_KEY_TYPES}
	 */
	public void checkKeyType(ResultSetMetaData metaData, String keyColumn) throws SQLException, BroadSQLException {
		int keyIndex = findColumnIndex(metaData, keyColumn);
		if (!ORDERABLE_KEY_TYPES.contains(metaData.getColumnType(keyIndex))) {
			throw new BroadSQLException("KEY column '" + keyColumn + "' has type " + metaData.getColumnTypeName(keyIndex)
					+ ", which MODE APPEND KEY(...) does not support - only numeric or date/time columns can be used as a delta key.");
		}
	}

	/**
	 * The {@code NULL}-key safety check for {@code MODE APPEND KEY(<column>)} (see
	 * docs/EXPORT_TO_H2.md, "APPEND KEY(...) opened up to joined queries") - call after
	 * {@link #checkKeyType} and before {@link #append}, against the same {@code ResultSetMetaData} the
	 * caller is about to pass it, so a source query that cannot be trusted never reaches the target
	 * database at all.
	 *
	 * <p>Reasoning, per {@link ResultSetMetaData#isNullable}'s three possible answers:
	 * <ul>
	 * <li>{@link ResultSetMetaData#columnNoNulls} - the driver confirms the key can never be
	 * {@code NULL} in this result. Proceed; nothing to warn about.</li>
	 * <li>{@link ResultSetMetaData#columnNullable} - the driver confirms it <b>can</b> be {@code NULL}.
	 * Always rejected, {@code force} or not - this is the actual delta-filter trap the check exists to
	 * catch (see {@link PullSourceShapeValidator}'s class Javadoc), so letting a user force past a
	 * <i>confirmed</i> risk would defeat the point.</li>
	 * <li>{@link ResultSetMetaData#columnNullableUnknown} - the driver cannot say (common for a computed
	 * or aliased-expression key column, e.g. {@code COALESCE(...)}/{@code CASE}/a scalar subquery, which
	 * the JDBC spec explicitly permits an "unknown" answer for). Rejected by default; {@code force}
	 * proceeds anyway, and the caller must show the returned warning to the user when that happens.</li>
	 * </ul>
	 *
	 * <p><b>Empirically confirmed limitation (real H2 2.3.232 database, see docs/EXPORT_TO_H2.md for the
	 * spike): this check cannot be trusted for an outer join.</b> A {@code LEFT}/{@code RIGHT}/
	 * {@code FULL OUTER JOIN} can synthesize a {@code NULL} on an otherwise {@code NOT NULL} column for
	 * an unmatched row, but H2's driver reports {@link ResultSetMetaData#columnNoNulls} regardless - it
	 * derives the answer from the column's own declared constraint, not from the join's actual effect on
	 * the projection, so this method silently, wrongly says "safe" for exactly the case it most needs to
	 * catch. It remains genuinely reliable for a plain column reference and for an {@code INNER JOIN}
	 * (also verified against the same database), and honest (returns {@code columnNullableUnknown}
	 * rather than a wrong answer) for a computed/expression key column. Callers should also print
	 * {@link PullSourceShapeValidator#usesOuterJoin} as an unconditional caution note, independent of
	 * what this method concludes, since an outer join can slip past it with no warning at all.
	 *
	 * @return an explicit warning to show the user if the key's nullability could not be verified and
	 *         {@code force} allowed proceeding anyway; {@code null} if the key is confirmed never
	 *         {@code NULL}, nothing to warn about
	 * @throws BroadSQLException if the key column is confirmed nullable (never forceable), or if
	 *                           nullability is unknown and {@code force} is {@code false}
	 */
	public String checkKeyNullability(ResultSetMetaData metaData, String keyColumn, boolean force) throws SQLException, BroadSQLException {
		int keyIndex = findColumnIndex(metaData, keyColumn);
		int nullable = metaData.isNullable(keyIndex);

		if (nullable == ResultSetMetaData.columnNoNulls) {
			return null;
		}
		if (nullable == ResultSetMetaData.columnNullable) {
			throw new BroadSQLException("KEY column '" + keyColumn + "' can be NULL for some rows of this query (the driver "
					+ "confirmed it) - MODE APPEND KEY(...) cannot safely use a nullable key column as a delta watermark (a NULL "
					+ "key would never satisfy 'key > watermark', so that row would be silently, permanently skipped by every "
					+ "future PULL). This cannot be forced past - restructure the query so the key column is never NULL (e.g. "
					+ "an INNER JOIN instead of an OUTER JOIN), or use MODE OVERWRITE.");
		}
		// columnNullableUnknown
		if (!force) {
			throw new BroadSQLException("Could not verify KEY column '" + keyColumn + "' can never be NULL for this query "
					+ "(the driver reported unknown nullability, common for a computed or joined column) - MODE APPEND KEY(...) "
					+ "refuses to guess. Add FORCE to proceed anyway if you are certain this key can never be NULL for any row "
					+ "this query can return (MODE APPEND KEY(" + keyColumn + ") FORCE), or use MODE OVERWRITE.");
		}
		return "WARNING: could not verify KEY column '" + keyColumn + "' can never be NULL for this query (driver reported "
				+ "unknown nullability) - proceeding because FORCE was specified. If this key can be NULL for some rows, those "
				+ "rows will be silently and permanently skipped by every future PULL.";
	}

	/**
	 * Whether {@code targetTable} already exists in {@code target} - checked before the source query
	 * even runs, so {@code CommandPull} knows whether to compute a delta watermark and filter the source
	 * query, or to do a full first load.
	 */
	public boolean tableExists(DatabaseDefinition target, String targetTable) throws BroadSQLException, SQLException {
		Connection targetConnection = openH2Connection(target);
		try (PreparedStatement ps = targetConnection.prepareStatement(
				"SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ?")) {
			ps.setString(1, targetTable);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1) > 0;
			}
		} finally {
			targetConnection.close();
		}
	}

	/**
	 * {@code SELECT MAX(<keyColumn>) FROM <targetTable>} against the already-pulled target table - the
	 * delta watermark for {@code MODE APPEND KEY(...)} (docs/EXPORT_TO_H2.md, "Modes": computed from the
	 * target table itself rather than tracked as separate state, so it self-heals if the table is ever
	 * dropped/recreated). Returns {@code null} when the table is empty (no watermark yet - the caller
	 * should do a full, unfiltered load) or when the caller already confirmed the table doesn't exist at
	 * all.
	 *
	 * @param keyColumn matched case-insensitively against the target's actual columns, since the target
	 *                  keeps whatever case its columns were first created with
	 * @throws BroadSQLException if {@code keyColumn} does not name any column of the existing target
	 *                           table
	 */
	public Object computeWatermark(DatabaseDefinition target, String targetTable, String keyColumn) throws BroadSQLException, SQLException {
		Connection targetConnection = openH2Connection(target);
		try {
			Map<String, String> actualColumns = fetchActualColumnNames(targetConnection, targetTable);
			String actualKeyColumn = actualColumns.get(keyColumn.toUpperCase(Locale.ROOT));
			if (actualKeyColumn == null) {
				throw new BroadSQLException("KEY column '" + keyColumn + "' not found in existing target table '" + targetTable
						+ "' - available columns: " + actualColumns.values());
			}
			try (Statement st = targetConnection.createStatement();
					ResultSet rs = st.executeQuery("SELECT MAX(" + quoteIdentifier(actualKeyColumn) + ") FROM " + quoteIdentifier(targetTable))) {
				rs.next();
				return rs.getObject(1);
			}
		} finally {
			targetConnection.close();
		}
	}

	/**
	 * Holds what {@link #resolveColumnPlan} computes once from a query's {@link ResultSetMetaData} and
	 * both {@link #overwrite} and {@link #append} need afterward: each column's quoted target identifier,
	 * its JDBC type (for {@link #copyColumnValue}), and the DDL fragment to create it with.
	 */
	private static final class ColumnPlan {
		final String[] quotedColumns;
		final int[] columnTypes;
		final String columnDefs;

		ColumnPlan(String[] quotedColumns, int[] columnTypes, String columnDefs) {
			this.quotedColumns = quotedColumns;
			this.columnTypes = columnTypes;
			this.columnDefs = columnDefs;
		}
	}

	private ColumnPlan resolveColumnPlan(ResultSetMetaData metaData) throws SQLException, BroadSQLException {
		int columnCount = metaData.getColumnCount();
		String[] quotedColumns = new String[columnCount];
		int[] columnTypes = new int[columnCount];
		Set<String> seenLabels = new HashSet<>();
		StringBuilder columnDefs = new StringBuilder();
		for (int i = 1; i <= columnCount; i++) {
			String label = metaData.getColumnLabel(i);
			if (!seenLabels.add(label)) {
				// Realistic since MODE APPEND KEY(...) allows JOIN (docs/EXPORT_TO_H2.md, "APPEND KEY(...)
				// opened up to joined queries") - e.g. SELECT c.id, o.id FROM customer c JOIN orders o ...
				// both label ID. Caught here, shared by OVERWRITE and APPEND, rather than left to surface as
				// a raw H2 "duplicate column name" DDL error.
				throw new BroadSQLException("PULL source query has two columns both named '" + label + "' - give one an "
						+ "explicit alias (e.g. SELECT c.id AS customer_id, o.id AS order_id ...).");
			}
			int jdbcType = metaData.getColumnType(i);
			String ddlType = H2ColumnTypeMapper.toH2DdlType(jdbcType, metaData.getPrecision(i), metaData.getScale(i), label, metaData.getColumnTypeName(i));
			quotedColumns[i - 1] = quoteIdentifier(label);
			columnTypes[i - 1] = jdbcType;
			if (i > 1) {
				columnDefs.append(", ");
			}
			columnDefs.append(quotedColumns[i - 1]).append(" ").append(ddlType);
		}
		return new ColumnPlan(quotedColumns, columnTypes, columnDefs.toString());
	}

	/** 1-based index of the query column whose label matches {@code columnName}, case-insensitively. */
	private int findColumnIndex(ResultSetMetaData metaData, String columnName) throws SQLException, BroadSQLException {
		int columnCount = metaData.getColumnCount();
		List<String> available = new ArrayList<>(columnCount);
		for (int i = 1; i <= columnCount; i++) {
			String label = metaData.getColumnLabel(i);
			available.add(label);
			if (label.equalsIgnoreCase(columnName)) {
				return i;
			}
		}
		throw new BroadSQLException("KEY column '" + columnName + "' not found in the PULL source query's results - available columns: " + available);
	}

	/**
	 * {@code TABLE_NAME -> COLUMN_NAME}-preserving-case lookup, keyed by upper-cased column name, for the
	 * columns {@code targetTable} actually has - used to match a source query's columns, or a
	 * {@code KEY(...)} name, against an existing target table without assuming any particular case.
	 */
	private Map<String, String> fetchActualColumnNames(Connection targetConnection, String targetTable) throws SQLException {
		Map<String, String> byUpperName = new LinkedHashMap<>();
		try (PreparedStatement ps = targetConnection.prepareStatement(
				"SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
			ps.setString(1, targetTable);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String name = rs.getString(1);
					byUpperName.put(name.toUpperCase(Locale.ROOT), name);
				}
			}
		}
		return byUpperName;
	}

	/**
	 * Validates the "structural mismatch" rule shared by {@code APPEND}/{@code MERGE}
	 * (docs/EXPORT_TO_H2.md, "Structural mismatch"): the query's columns and the existing target table's
	 * columns must match exactly by name (case-insensitively) - no partial-column update in either
	 * direction. Returns the target's actual, correctly-cased column names, quoted and in the query's
	 * column order, ready to use as the {@code INSERT}'s column list - since an existing column may have
	 * been created with different casing than the current query happens to report.
	 */
	private String[] matchAgainstExistingColumns(Connection targetConnection, String targetTable, String[] quotedSourceColumns, ResultSetMetaData sourceMetaData)
			throws SQLException, BroadSQLException {
		Map<String, String> actualColumns = fetchActualColumnNames(targetConnection, targetTable);
		String[] insertColumnNames = new String[quotedSourceColumns.length];
		List<String> missingFromTarget = new ArrayList<>();
		for (int i = 1; i <= sourceMetaData.getColumnCount(); i++) {
			String label = sourceMetaData.getColumnLabel(i);
			String actual = actualColumns.remove(label.toUpperCase(Locale.ROOT));
			if (actual == null) {
				missingFromTarget.add(label);
			} else {
				insertColumnNames[i - 1] = quoteIdentifier(actual);
			}
		}
		if (!missingFromTarget.isEmpty() || !actualColumns.isEmpty()) {
			throw new BroadSQLException("PULL aborted: the query's columns don't match the existing target table '" + targetTable + "' - "
					+ (missingFromTarget.isEmpty() ? "" : "query has column(s) the target doesn't have: " + missingFromTarget + ". ")
					+ (actualColumns.isEmpty() ? "" : "target has column(s) the query doesn't have: " + actualColumns.values() + ". ")
					+ "MODE APPEND requires an exact column match - no partial-column update.");
		}
		return insertColumnNames;
	}

	/** Shared batched-insert loop used by both {@link #overwrite} and {@link #append}. */
	private int insertRows(Connection targetConnection, String quotedTable, String[] insertColumnNames, int[] columnTypes, ResultSet sourceResults)
			throws SQLException {
		String insertSql = buildInsertSql(quotedTable, insertColumnNames);
		int rowsWritten = 0;
		try (PreparedStatement insert = targetConnection.prepareStatement(insertSql)) {
			int batched = 0;
			while (sourceResults.next()) {
				for (int i = 1; i <= columnTypes.length; i++) {
					copyColumnValue(sourceResults, insert, i, columnTypes[i - 1]);
				}
				insert.addBatch();
				rowsWritten++;
				batched++;
				if (batched >= BATCH_SIZE) {
					insert.executeBatch();
					batched = 0;
				}
			}
			if (batched > 0) {
				insert.executeBatch();
			}
		}
		return rowsWritten;
	}

	private String buildInsertSql(String quotedTable, String[] quotedColumns) {
		StringBuilder insertSql = new StringBuilder("INSERT INTO ").append(quotedTable).append(" (");
		StringBuilder placeholders = new StringBuilder();
		for (int i = 0; i < quotedColumns.length; i++) {
			if (i > 0) {
				insertSql.append(", ");
				placeholders.append(", ");
			}
			insertSql.append(quotedColumns[i]);
			placeholders.append("?");
		}
		insertSql.append(") VALUES (").append(placeholders).append(")");
		return insertSql.toString();
	}

	private void rollbackQuietly(Connection targetConnection) {
		try {
			targetConnection.rollback();
		} catch (SQLException ignored) {
			// best-effort rollback; the original exception is what the caller sees
		}
	}

	/**
	 * Reads column {@code sourceIndex} from {@code source} with the JDBC getter matching
	 * {@code jdbcType}, then writes it to {@code target} at the same position - mirroring the type
	 * switch in {@link com.upandcoding.broadsql.dao.extractors.QueryExtractorToExcel2007}, since a NULL
	 * read through a primitive getter ({@code getLong}/{@code getBoolean}/{@code getDouble}) must be
	 * written as SQL NULL, not as the getter's {@code 0}/{@code false} default.
	 */
	private void copyColumnValue(ResultSet source, PreparedStatement target, int sourceIndex, int jdbcType) throws SQLException {
		switch (jdbcType) {
			case Types.TINYINT:
			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.BIGINT: {
				long value = source.getLong(sourceIndex);
				if (source.wasNull()) {
					target.setNull(sourceIndex, Types.BIGINT);
				} else {
					target.setLong(sourceIndex, value);
				}
				break;
			}
			case Types.DECIMAL:
			case Types.NUMERIC: {
				BigDecimal value = source.getBigDecimal(sourceIndex);
				target.setBigDecimal(sourceIndex, value);
				break;
			}
			case Types.REAL:
			case Types.FLOAT:
			case Types.DOUBLE: {
				double value = source.getDouble(sourceIndex);
				if (source.wasNull()) {
					target.setNull(sourceIndex, Types.DOUBLE);
				} else {
					target.setDouble(sourceIndex, value);
				}
				break;
			}
			case Types.BOOLEAN:
			case Types.BIT: {
				boolean value = source.getBoolean(sourceIndex);
				if (source.wasNull()) {
					target.setNull(sourceIndex, Types.BOOLEAN);
				} else {
					target.setBoolean(sourceIndex, value);
				}
				break;
			}
			case Types.DATE: {
				Date value = source.getDate(sourceIndex);
				target.setDate(sourceIndex, value);
				break;
			}
			case Types.TIME:
			case Types.TIME_WITH_TIMEZONE: {
				Time value = source.getTime(sourceIndex);
				target.setTime(sourceIndex, value);
				break;
			}
			case Types.TIMESTAMP:
			case Types.TIMESTAMP_WITH_TIMEZONE:
			case OracleJdbcTypes.TIMESTAMPTZ:
			case OracleJdbcTypes.TIMESTAMPLTZ: {
				Timestamp value = source.getTimestamp(sourceIndex);
				target.setTimestamp(sourceIndex, value);
				break;
			}
			default: {
				// CHAR/VARCHAR/NVARCHAR/LONGVARCHAR family - the only other types H2ColumnTypeMapper accepts.
				String value = source.getString(sourceIndex);
				target.setString(sourceIndex, value);
				break;
			}
		}
	}

	/**
	 * Opens a dedicated JDBC connection to {@code target}, mirroring the H2 branch of
	 * {@link com.upandcoding.broadsql.dao.DatabaseConnection#openConnection(boolean)} - duplicated rather
	 * than reused because that method is tied to the {@code sqlDatabase} singleton's console/session
	 * state, which a second, short-lived connection has no business touching.
	 */
	private Connection openH2Connection(DatabaseDefinition target) throws BroadSQLException {
		try {
			Class.forName(target.getDbDriver()).getDeclaredConstructor().newInstance();
			String connector = target.getConnectorDatabase();
			if (StringUtils.isNotBlank(target.getUserName())) {
				connector = connector + ";USER=" + target.getUserName();
			}
			if (StringUtils.isNotBlank(target.getUserPassword())) {
				connector = connector + ";PASSWORD=" + target.getUserPassword();
			}
			return DriverManager.getConnection(connector);
		} catch (ReflectiveOperationException | SQLException e) {
			throw new BroadSQLException("Unable to connect to target H2 database '" + target.getId() + "': " + e.getLocalizedMessage(), e);
		}
	}

	/**
	 * Wraps {@code identifier} in double quotes (H2's quoting character), doubling any embedded quote,
	 * so column labels and table names are used exactly as given - reserved words, mixed case, and
	 * special characters included - never subject to H2's default unquoted-identifier upper-casing.
	 */
	private static String quoteIdentifier(String identifier) {
		return "\"" + identifier.replace("\"", "\"\"") + "\"";
	}
}
