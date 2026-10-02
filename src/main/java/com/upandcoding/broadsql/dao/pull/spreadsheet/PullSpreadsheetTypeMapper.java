package com.upandcoding.broadsql.dao.pull.spreadsheet;

import java.sql.Types;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;

/**
 * Validates that a query column's JDBC type is one {@code PULL ... AS XLSX/ODS} can write, before any
 * cell of any tab is written - see docs/PULL_TO_SPREADSHEET.md, "Type handling". Deliberately narrow,
 * the same "ordinary business data" set {@code H2ColumnTypeMapper} accepts for {@code PULL ... AS H2}
 * (strings, integers, decimals, floating point, boolean, date/time) - but this class is standalone, not
 * a call into {@code H2ColumnTypeMapper}: the two PULL destination kinds share the command's grammar and
 * nothing else (see docs/PULL_TO_SPREADSHEET.md, "Relationship to PULL ... AS H2").
 *
 * <p>Large objects ({@code BLOB}/{@code CLOB}/{@code NCLOB}), binary data, and structural/vendor-specific
 * types are rejected explicitly rather than silently downgraded to placeholder text - unlike
 * {@code QueryExtractorToExcel2007}/{@code QueryExtractorToODS} (used by {@code EXPORT}/{@code DUMP}),
 * which still write a "Binary content, not exported" placeholder for these types. Checking every column
 * up front, before opening or modifying the target file, means an unsupported column type never leaves a
 * tab half-rewritten or a foreign file partially touched.
 */
final class PullSpreadsheetTypeMapper {

	private PullSpreadsheetTypeMapper() {
	}

	/**
	 * @throws BroadSQLException if {@code jdbcType} is not one of the supported "ordinary business data"
	 *                           types - the message names the offending column and its reported type,
	 *                           meant to be shown to the user as-is
	 */
	static void validateSupported(int jdbcType, String columnLabel, String typeName) throws BroadSQLException {
		switch (jdbcType) {
			case Types.CHAR:
			case Types.NCHAR:
			case Types.VARCHAR:
			case Types.NVARCHAR:
			case Types.LONGVARCHAR:
			case Types.LONGNVARCHAR:
			case Types.BOOLEAN:
			case Types.BIT:
			case Types.TINYINT:
			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.BIGINT:
			case Types.REAL:
			case Types.FLOAT:
			case Types.DOUBLE:
			case Types.DECIMAL:
			case Types.NUMERIC:
			case Types.DATE:
			case Types.TIME:
			case Types.TIME_WITH_TIMEZONE:
			case Types.TIMESTAMP:
			case Types.TIMESTAMP_WITH_TIMEZONE:
			// Oracle's proprietary TIMESTAMP WITH TIME ZONE / WITH LOCAL TIME ZONE - see OracleJdbcTypes.
			case OracleJdbcTypes.TIMESTAMPTZ:
			case OracleJdbcTypes.TIMESTAMPLTZ:
				return;

			default:
				throw new BroadSQLException("Column '" + columnLabel + "' has type " + typeName + " (JDBC type " + jdbcType
						+ "), which PULL ... AS XLSX/ODS cannot write - large objects (BLOB/CLOB/NCLOB), binary, and "
						+ "structural/vendor-specific types are not supported as PULL destinations.");
		}
	}
}
