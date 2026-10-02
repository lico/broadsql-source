package com.upandcoding.broadsql.dao.pull.html;

import java.sql.Types;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;

/**
 * Validates that a query column's JDBC type is one {@code PULL ... AS HTML} can write, before any byte of
 * the target file is written - see docs/PULL_TO_TEXT.md, "Type handling". Same "ordinary business data"
 * set every other PULL destination accepts, duplicated independently here too - see
 * {@code com.upandcoding.broadsql.dao.pull.json.PullJsonTypeMapper}'s Javadoc for why this isn't shared code.
 */
final class PullHtmlTypeMapper {

	private PullHtmlTypeMapper() {
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
						+ "), which PULL ... AS HTML cannot write - large objects (BLOB/CLOB/NCLOB), binary, and "
						+ "structural/vendor-specific types are not supported as PULL destinations.");
		}
	}
}
