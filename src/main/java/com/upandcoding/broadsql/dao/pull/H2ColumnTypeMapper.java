package com.upandcoding.broadsql.dao.pull;

import java.sql.Types;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;

/**
 * Maps a JDBC column type - as reported by a query's own {@code ResultSetMetaData}, never a source
 * table's catalog metadata (see docs/EXPORT_TO_H2.md, "Modes") - to the H2 DDL column type used to
 * (re)create a {@code PULL ... AS DB} destination table.
 *
 * <p>Deliberately narrow: only "ordinary business data" types are mapped (strings, integers,
 * decimals, floating point, boolean, date/time - including {@link OracleJdbcTypes}, Oracle's
 * proprietary timestamp-with-zone types, mapped like every zone-aware timestamp to
 * {@code TIMESTAMP WITH TIME ZONE}, see {@link TemporalValues}). Large objects
 * ({@code BLOB}/{@code CLOB}/{@code NCLOB}), binary data, and structural/vendor-specific types
 * ({@code ARRAY}, {@code STRUCT}, {@code REF}, {@code ROWID}, {@code SQLXML}, {@code JAVA_OBJECT},
 * {@code DISTINCT}, {@code OTHER}) are rejected explicitly rather than silently downgraded to text, so
 * a PULL never produces a target column that quietly lost information.
 */
public final class H2ColumnTypeMapper {

	/**
	 * A source precision/scale above this is treated as "unconstrained" rather than taken literally -
	 * some drivers report a sentinel value (e.g. {@code Integer.MAX_VALUE} for an unbounded Postgres
	 * {@code TEXT}/{@code NUMERIC} column) that would otherwise produce a nonsensical
	 * {@code VARCHAR(2147483647)}/{@code DECIMAL(2147483647,0)}.
	 */
	private static final int MAX_SANE_PRECISION = 1_000_000;

	private H2ColumnTypeMapper() {
	}

	/**
	 * @param jdbcType    a {@link Types} constant, from {@code ResultSetMetaData.getColumnType()}
	 * @param precision   from {@code ResultSetMetaData.getPrecision()}
	 * @param scale       from {@code ResultSetMetaData.getScale()}
	 * @param columnLabel from {@code ResultSetMetaData.getColumnLabel()} - used only to name the
	 *                    column in the error message when the type can't be mapped
	 * @param typeName    from {@code ResultSetMetaData.getColumnTypeName()} - used only to name the
	 *                    type in the error message when it can't be mapped
	 * @return the H2 DDL type to use for this column
	 * @throws BroadSQLException if {@code jdbcType} has no supported H2 mapping
	 */
	public static String toH2DdlType(int jdbcType, int precision, int scale, String columnLabel, String typeName) throws BroadSQLException {
		switch (TemporalValues.effectiveType(jdbcType, typeName)) {
			case Types.CHAR:
			case Types.NCHAR:
			case Types.VARCHAR:
			case Types.NVARCHAR:
			case Types.LONGVARCHAR:
			case Types.LONGNVARCHAR:
				return isSanePrecision(precision) ? "VARCHAR(" + precision + ")" : "VARCHAR";

			case Types.BOOLEAN:
			case Types.BIT:
				return "BOOLEAN";

			case Types.TINYINT:
				return "TINYINT";

			case Types.SMALLINT:
				return "SMALLINT";

			case Types.INTEGER:
				return "INTEGER";

			case Types.BIGINT:
				return "BIGINT";

			case Types.REAL:
				return "REAL";

			case Types.FLOAT:
			case Types.DOUBLE:
				return "DOUBLE";

			case Types.DECIMAL:
			case Types.NUMERIC:
				return isSanePrecision(precision) ? "DECIMAL(" + precision + "," + Math.max(scale, 0) + ")" : "DECIMAL";

			case Types.DATE:
				return "DATE";

			// Times and timestamps keep every fractional digit (H2's default precision is 0 for TIME and 6 for
			// TIMESTAMP): the same precision as the text exports
			case Types.TIME:
				return "TIME(9)";

			// Zone-aware values keep their offset in H2 (TemporalValues.effectiveType: the standard codes,
			// Oracle's vendor codes and PostgreSQL's timestamptz/timetz); they are copied as OffsetDateTime /
			// OffsetTime, never through the client JVM's zone.
			case Types.TIME_WITH_TIMEZONE:
				return "TIME(9) WITH TIME ZONE";

			case Types.TIMESTAMP:
				return "TIMESTAMP(9)";

			case Types.TIMESTAMP_WITH_TIMEZONE:
				return "TIMESTAMP(9) WITH TIME ZONE";

			default:
				throw new BroadSQLException("Column '" + columnLabel + "' has type " + typeName + " (JDBC type " + jdbcType
						+ "), which PULL cannot map to an H2 column type - large objects (BLOB/CLOB/NCLOB), binary, and "
						+ "structural/vendor-specific types are not supported as PULL destinations.");
		}
	}

	private static boolean isSanePrecision(int precision) {
		return precision > 0 && precision <= MAX_SANE_PRECISION;
	}
}
