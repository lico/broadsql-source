package com.upandcoding.broadsql.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import com.upandcoding.broadsql.dao.pull.TemporalText;
import com.upandcoding.broadsql.dao.pull.TemporalValues;

/**
 * SPRINT 0110A: the typed JDBC reader extracted from {@code QueryExtractorToScreen} (where it builds the typed
 * snapshot of {@code DUMP /}) so the scalar query assignment of {@code LET} reads values exactly the same way
 * (spec section 7.2). Behavior is unchanged for {@code DUMP /}.
 */
public final class JdbcTypedValueReader {

	private JdbcTypedValueReader() {
	}

	/**
	 * The typed form of a non-NULL cell already read as {@code text} by {@code getString()}. Only the scalar types
	 * every export format supports are re-read with their own getter; text is reused as is; any other type (LOB,
	 * binary, structural) is not re-read at all - some drivers allow a streamed column to be read only once - and
	 * keeps its text.
	 *
	 * @param jdbcType the effective type ({@link TemporalValues#effectiveType})
	 */
	public static Object typedValue(ResultSet results, int columnIndex, int jdbcType, String text) throws SQLException {
		switch (jdbcType) {
			case Types.DATE:
				return TemporalValues.getLocalDate(results, columnIndex);
			case Types.TIME:
				return TemporalText.getTime(results, columnIndex);
			case Types.TIME_WITH_TIMEZONE:
				return TemporalValues.getOffsetTime(results, columnIndex);
			case Types.TIMESTAMP:
				return TemporalValues.getLocalDateTime(results, columnIndex);
			case Types.TIMESTAMP_WITH_TIMEZONE:
				// jdbcType is TemporalValues.effectiveType: every zone-aware timestamp keeps its offset for DUMP /
				return TemporalValues.getOffsetDateTime(results, columnIndex);
			default:
				return scalarValue(results, columnIndex, jdbcType, text);
		}
	}

	/** The non-temporal part of {@link #typedValue}: the scalar getters, or the displayed text. */
	private static Object scalarValue(ResultSet results, int columnIndex, int jdbcType, String text) throws SQLException {
		switch (jdbcType) {
			case Types.TINYINT:
			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.BIGINT:
				return results.getLong(columnIndex);
			case Types.DECIMAL:
			case Types.NUMERIC:
				return results.getBigDecimal(columnIndex);
			case Types.DOUBLE:
			case Types.REAL:
				return results.getDouble(columnIndex);
			case Types.FLOAT:
				return results.getFloat(columnIndex);
			case Types.BOOLEAN:
			case Types.BIT:
				return results.getBoolean(columnIndex);
			default:
				return text;
		}
	}

	/**
	 * SPRINT 0110A: whether a query column of effective type {@code jdbcType} can be held by a SQL scripting
	 * variable (spec section 7.2): character, integer, exact and approximate numeric, boolean, date, time and
	 * timestamp types, with or without time zone. LOBs, binary, structural and vendor types cannot.
	 */
	public static boolean isScalarVariableType(int jdbcType) {
		switch (jdbcType) {
			case Types.CHAR:
			case Types.VARCHAR:
			case Types.LONGVARCHAR:
			case Types.NCHAR:
			case Types.NVARCHAR:
			case Types.LONGNVARCHAR:
			case Types.TINYINT:
			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.BIGINT:
			case Types.DECIMAL:
			case Types.NUMERIC:
			case Types.REAL:
			case Types.FLOAT:
			case Types.DOUBLE:
			case Types.BOOLEAN:
			case Types.BIT:
			case Types.DATE:
			case Types.TIME:
			case Types.TIME_WITH_TIMEZONE:
			case Types.TIMESTAMP:
			case Types.TIMESTAMP_WITH_TIMEZONE:
				return true;
			default:
				return false;
		}
	}
}
