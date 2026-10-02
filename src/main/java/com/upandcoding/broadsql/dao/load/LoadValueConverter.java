package com.upandcoding.broadsql.dao.load;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;

import org.apache.commons.lang3.time.DateUtils;

/**
 * Converts one source (CSV) value to the Java object that will be bound with
 * {@code PreparedStatement.setObject(index, value, jdbcType)} for a given target column - or
 * {@code null}, meaning "bind {@code java.sql.Types.NULL}", which the caller does with
 * {@code setNull} - never a string built into SQL text (SPRINT 0912B section 5.1).
 *
 * <p>NULL/blank rule (section 9 requires one explicit v1 rule; this is it, documented here and in
 * {@code docs/SQL_LOAD.md}):
 * <ul>
 * <li>the literal token {@code NULL} (case-insensitive, whole field) always binds SQL {@code NULL},
 * regardless of the target type;</li>
 * <li>an empty field binds an empty string for a character type ({@code CHAR}/{@code VARCHAR}/
 * {@code LONGVARCHAR}/{@code CLOB}/{@code NCHAR}/{@code NVARCHAR}/{@code LONGNVARCHAR}, since the
 * database can store that distinctly from {@code NULL}) and SQL {@code NULL} for every other type;</li>
 * <li>a source column entirely absent from the header row is handled one level up, by
 * {@link LoadValidator} - it is simply omitted from the generated {@code INSERT} column list, letting
 * a database default/generated value apply.</li>
 * </ul>
 *
 * <p>{@code sysdate}/{@code systimestamp} (case-insensitive) are recognized for {@code DATE}/
 * {@code TIMESTAMP} columns as the current date/time, preserving the one convenience the legacy
 * {@code directLoad} family had.
 */
final class LoadValueConverter {

	private static final String[] DATE_PATTERNS = { "yyyy-MM-dd", "dd-MMM-yy", "dd/MM/yyyy" };
	private static final String[] TIMESTAMP_PATTERNS = { "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "dd-MMM-yy HH:mm:ss" };

	private LoadValueConverter() {
	}

	static Object convert(String rawValue, LoadTargetColumn column) {
		String value = rawValue == null ? "" : rawValue;

		if ("NULL".equalsIgnoreCase(value.trim())) {
			return null;
		}
		if (value.isEmpty()) {
			return isCharacterType(column.getJdbcType()) ? "" : null;
		}

		String trimmed = value.trim();
		switch (column.getJdbcType()) {
			case Types.CHAR:
			case Types.VARCHAR:
			case Types.LONGVARCHAR:
			case Types.CLOB:
			case Types.NCHAR:
			case Types.NVARCHAR:
			case Types.LONGNVARCHAR:
				return value;

			case Types.TINYINT:
			case Types.SMALLINT:
			case Types.INTEGER:
				try {
					return Integer.valueOf(trimmed);
				} catch (NumberFormatException e) {
					throw conversionFailure(column);
				}

			case Types.BIGINT:
				try {
					return Long.valueOf(trimmed);
				} catch (NumberFormatException e) {
					throw conversionFailure(column);
				}

			case Types.NUMERIC:
			case Types.DECIMAL:
				try {
					return new BigDecimal(trimmed);
				} catch (NumberFormatException e) {
					throw conversionFailure(column);
				}

			case Types.REAL:
				try {
					return Float.valueOf(trimmed);
				} catch (NumberFormatException e) {
					throw conversionFailure(column);
				}

			case Types.FLOAT:
			case Types.DOUBLE:
				try {
					return Double.valueOf(trimmed);
				} catch (NumberFormatException e) {
					throw conversionFailure(column);
				}

			case Types.BOOLEAN:
			case Types.BIT:
				return convertBoolean(trimmed, column);

			case Types.DATE:
				return convertDate(trimmed, column);

			case Types.TIMESTAMP:
				return convertTimestamp(trimmed, column);

			case Types.TIME:
				return convertTime(trimmed, column);

			default:
				return value;
		}
	}

	private static boolean isCharacterType(int jdbcType) {
		switch (jdbcType) {
			case Types.CHAR:
			case Types.VARCHAR:
			case Types.LONGVARCHAR:
			case Types.CLOB:
			case Types.NCHAR:
			case Types.NVARCHAR:
			case Types.LONGNVARCHAR:
				return true;
			default:
				return false;
		}
	}

	private static Boolean convertBoolean(String trimmed, LoadTargetColumn column) {
		if ("TRUE".equalsIgnoreCase(trimmed) || "1".equals(trimmed) || "Y".equalsIgnoreCase(trimmed)) {
			return Boolean.TRUE;
		}
		if ("FALSE".equalsIgnoreCase(trimmed) || "0".equals(trimmed) || "N".equalsIgnoreCase(trimmed)) {
			return Boolean.FALSE;
		}
		throw conversionFailure(column);
	}

	private static Date convertDate(String trimmed, LoadTargetColumn column) {
		if ("SYSDATE".equalsIgnoreCase(trimmed)) {
			return new Date(System.currentTimeMillis());
		}
		try {
			return new Date(DateUtils.parseDate(trimmed, DATE_PATTERNS).getTime());
		} catch (ParseException e) {
			throw conversionFailure(column);
		}
	}

	private static Timestamp convertTimestamp(String trimmed, LoadTargetColumn column) {
		if ("SYSDATE".equalsIgnoreCase(trimmed) || "SYSTIMESTAMP".equalsIgnoreCase(trimmed)) {
			return new Timestamp(System.currentTimeMillis());
		}
		try {
			return new Timestamp(DateUtils.parseDate(trimmed, TIMESTAMP_PATTERNS).getTime());
		} catch (ParseException e) {
			throw conversionFailure(column);
		}
	}

	private static Time convertTime(String trimmed, LoadTargetColumn column) {
		try {
			SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
			format.setLenient(false);
			return new Time(format.parse(trimmed).getTime());
		} catch (ParseException e) {
			throw conversionFailure(column);
		}
	}

	private static LoadValueConversionException conversionFailure(LoadTargetColumn column) {
		return new LoadValueConversionException("cannot be converted to " + column.getTypeName());
	}
}
