package com.upandcoding.broadsql.dao.pull;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;

import com.upandcoding.broadsql.dao.OracleJdbcTypes;

/**
 * How exports read JDBC date and time values: deterministically, never through the client JVM's default time
 * zone.
 *
 * <p><b>Time-zone-aware values</b> ({@code TIMESTAMP WITH TIME ZONE}, PostgreSQL's {@code timestamptz}, Oracle's
 * {@code TIMESTAMP WITH [LOCAL] TIME ZONE}, and the {@code TIME WITH TIME ZONE} equivalents) are read as
 * {@link OffsetDateTime}/{@link OffsetTime} with the offset the database returns
 * ({@code getObject(column, OffsetDateTime.class)}, JDBC 4.2). A driver without that mapping falls back to the
 * value's instant ({@link Timestamp#toInstant()}, which does not depend on the JVM zone), expressed in UTC: the
 * original offset is then unknown, but the instant is exact and the output is the same on every machine.
 *
 * <p><b>Time-zone-less values</b> ({@code DATE}, {@code TIME}, {@code TIMESTAMP}) are read as
 * {@link LocalDate}/{@link LocalTime}/{@link LocalDateTime}, the wall-clock value as stored, with no conversion.
 * {@code getTimestamp()} is only a fallback: it goes through the JVM zone and can shift a value falling in that
 * zone's daylight-saving gap.
 *
 * <p>{@link #effectiveType} gives every exporter the same classification: a column whose driver reports a
 * zone-aware type under a generic code (PostgreSQL reports {@code timestamptz} as {@code TIMESTAMP}) or a
 * vendor code (Oracle) is handled as {@link Types#TIMESTAMP_WITH_TIMEZONE} or
 * {@link Types#TIME_WITH_TIMEZONE}.
 */
public final class TemporalValues {

	private TemporalValues() {
	}

	/**
	 * The JDBC type an exporter should handle the column as: {@link Types#TIMESTAMP_WITH_TIMEZONE} for every
	 * zone-aware timestamp (standard code, Oracle's vendor codes, or a {@code TIMESTAMP} whose type name says it
	 * has a zone), {@link Types#TIME_WITH_TIMEZONE} likewise for times, {@code jdbcType} otherwise.
	 */
	public static int effectiveType(int jdbcType, String typeName) {
		switch (jdbcType) {
			case Types.TIMESTAMP_WITH_TIMEZONE:
			case OracleJdbcTypes.TIMESTAMPTZ:
			case OracleJdbcTypes.TIMESTAMPLTZ:
				return Types.TIMESTAMP_WITH_TIMEZONE;
			case Types.TIME_WITH_TIMEZONE:
				return Types.TIME_WITH_TIMEZONE;
			case Types.TIMESTAMP:
				return namesAZone(typeName, "TIMESTAMPTZ") ? Types.TIMESTAMP_WITH_TIMEZONE : jdbcType;
			case Types.TIME:
				return namesAZone(typeName, "TIMETZ") ? Types.TIME_WITH_TIMEZONE : jdbcType;
			default:
				return jdbcType;
		}
	}

	private static boolean namesAZone(String typeName, String shortName) {
		if (typeName == null) {
			return false;
		}
		String upper = typeName.trim().toUpperCase(Locale.ROOT);
		return upper.equals(shortName) || upper.contains("WITH TIME ZONE") || upper.contains("WITH LOCAL TIME ZONE");
	}

	/** A zone-aware timestamp with its offset, or {@code null} for SQL {@code NULL} (see the class comment). */
	public static OffsetDateTime getOffsetDateTime(ResultSet results, int columnIndex) throws SQLException {
		try {
			return results.getObject(columnIndex, OffsetDateTime.class);
		} catch (SQLException | RuntimeException | AbstractMethodError e) {
			// no JDBC 4.2 mapping: try the driver's own object, then the instant
		}
		Object value;
		try {
			value = results.getObject(columnIndex);
		} catch (SQLException | RuntimeException e) {
			value = null;
		}
		if (value instanceof OffsetDateTime) {
			return (OffsetDateTime) value;
		}
		if (value instanceof ZonedDateTime) {
			return ((ZonedDateTime) value).toOffsetDateTime();
		}
		if (value instanceof Instant) {
			return ((Instant) value).atOffset(ZoneOffset.UTC);
		}
		Timestamp timestamp = results.getTimestamp(columnIndex);
		return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
	}

	/** A zone-aware time with its offset, or {@code null} for SQL {@code NULL}. */
	public static OffsetTime getOffsetTime(ResultSet results, int columnIndex) throws SQLException {
		try {
			return results.getObject(columnIndex, OffsetTime.class);
		} catch (SQLException | RuntimeException | AbstractMethodError e) {
			// no JDBC 4.2 mapping: the instant of the time on the epoch day, in UTC
		}
		Time time = results.getTime(columnIndex);
		return time == null ? null : Instant.ofEpochMilli(time.getTime()).atOffset(ZoneOffset.UTC).toOffsetTime();
	}

	/** A time-zone-less timestamp as stored, or {@code null} for SQL {@code NULL}. */
	public static LocalDateTime getLocalDateTime(ResultSet results, int columnIndex) throws SQLException {
		try {
			return results.getObject(columnIndex, LocalDateTime.class);
		} catch (SQLException | RuntimeException | AbstractMethodError e) {
			Timestamp value = results.getTimestamp(columnIndex);
			return value == null ? null : value.toLocalDateTime();
		}
	}

	/** A date as stored, or {@code null} for SQL {@code NULL}. */
	public static LocalDate getLocalDate(ResultSet results, int columnIndex) throws SQLException {
		try {
			return results.getObject(columnIndex, LocalDate.class);
		} catch (SQLException | RuntimeException | AbstractMethodError e) {
			Date value = results.getDate(columnIndex);
			return value == null ? null : value.toLocalDate();
		}
	}

	/**
	 * The date and time a spreadsheet cell holds for a zone-aware timestamp: the same instant, normalized to UTC
	 * ({@code 2024-01-15 10:00:00+05:00} gives {@code 2024-01-15 05:00:00}). Spreadsheet date cells have no zone.
	 */
	public static LocalDateTime toUtc(OffsetDateTime value) {
		return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
	}

	/** The time a spreadsheet cell holds for a zone-aware time: the same instant, normalized to UTC. */
	public static LocalTime toUtc(OffsetTime value) {
		return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalTime();
	}
}
