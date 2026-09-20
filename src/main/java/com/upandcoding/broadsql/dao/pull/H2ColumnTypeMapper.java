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
 * proprietary timestamp-with-zone types, mapped to plain {@code TIMESTAMP}). Large objects
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
		switch (jdbcType) {
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

			case Types.TIME:
			case Types.TIME_WITH_TIMEZONE:
				return "TIME";

			case Types.TIMESTAMP:
			// Oracle's proprietary TIMESTAMP WITH TIME ZONE / WITH LOCAL TIME ZONE (see
			// OracleJdbcTypes) have no dedicated H2 mapping of their own - both are read via
			// ResultSet.getTimestamp(), same as a plain TIMESTAMP, so they map to plain TIMESTAMP
			// here too rather than "TIMESTAMP WITH TIME ZONE" below (which is reserved for the
			// standard JDBC type, whose value actually keeps its offset end to end).
			case OracleJdbcTypes.TIMESTAMPTZ:
			case OracleJdbcTypes.TIMESTAMPLTZ:
				return "TIMESTAMP";

			case Types.TIMESTAMP_WITH_TIMEZONE:
				return "TIMESTAMP WITH TIME ZONE";

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
