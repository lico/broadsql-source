package com.upandcoding.broadsql.dao.pull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The one text form of JDBC time and timestamp values in exported files (CSV, TXT, JSON, HTML, Markdown, the
 * text of an ODS time cell): {@code yyyy-MM-dd HH:mm:ss} and {@code HH:mm:ss}, followed by the fractional
 * seconds the value actually carries, and nothing when it carries none. The fraction is written in groups of
 * three digits (milliseconds, then microseconds, then nanoseconds), as {@link LocalDateTime#toString()} does:
 * {@code 2015-05-07 09:49:01} stays as is, {@code 2015-05-07 09:49:01.016} keeps {@code .016}, and a
 * microsecond value keeps its six digits. No precision is dropped and no {@code .000} is invented. A
 * time-zone-aware value is followed by its own offset ({@code 2024-01-15 10:00:00+05:00}), never converted
 * to another zone (see {@link TemporalValues}).
 */
public final class TemporalText {

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private TemporalText() {
	}

	/** {@code yyyy-MM-dd HH:mm:ss[.fraction]}. */
	public static String dateTime(LocalDateTime value) {
		return value.format(DATE_TIME) + fraction(value.getNano());
	}

	/** {@code yyyy-MM-dd'T'HH:mm:ss[.fraction]} (ISO 8601, used by JSON). */
	public static String isoDateTime(LocalDateTime value) {
		return value.format(ISO_DATE_TIME) + fraction(value.getNano());
	}

	/** {@code HH:mm:ss[.fraction]}. */
	public static String time(LocalTime value) {
		return value.format(TIME) + fraction(value.getNano());
	}

	/**
	 * A zone-aware timestamp with its own offset, never converted: {@code yyyy-MM-dd HH:mm:ss[.fraction]+hh:mm}
	 * ({@code 2024-01-15 10:00:00+05:00}; UTC is {@code +00:00}).
	 */
	public static String offsetDateTime(OffsetDateTime value) {
		return dateTime(value.toLocalDateTime()) + offset(value.getOffset());
	}

	/** The ISO 8601 form of {@link #offsetDateTime} (JSON): {@code yyyy-MM-dd'T'HH:mm:ss[.fraction]+hh:mm}. */
	public static String isoOffsetDateTime(OffsetDateTime value) {
		return isoDateTime(value.toLocalDateTime()) + offset(value.getOffset());
	}

	/** A zone-aware time with its own offset: {@code HH:mm:ss[.fraction]+hh:mm}. */
	public static String offsetTime(OffsetTime value) {
		return time(value.toLocalTime()) + offset(value.getOffset());
	}

	/** {@code +hh:mm} ({@code +00:00} for UTC, rather than {@code Z}, so every value has the same shape). */
	static String offset(ZoneOffset offset) {
		return offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId();
	}

	/** {@code ""} for a whole second, otherwise {@code .} and the fraction in groups of three digits. */
	public static String fraction(int nanos) {
		if (nanos == 0) {
			return "";
		}
		if (nanos % 1_000_000 == 0) {
			return String.format(".%03d", nanos / 1_000_000);
		}
		if (nanos % 1_000 == 0) {
			return String.format(".%06d", nanos / 1_000);
		}
		return String.format(".%09d", nanos);
	}

	/**
	 * A TIME column as a {@link LocalTime} with its fractional seconds, or {@code null} for SQL {@code NULL}.
	 * {@link Time#toLocalTime()} keeps whole seconds only, so the driver's {@code LocalTime} mapping (JDBC 4.2)
	 * is read first; a driver without it falls back to {@link ResultSet#getTime}.
	 */
	public static LocalTime getTime(ResultSet results, int columnIndex) throws SQLException {
		try {
			return results.getObject(columnIndex, LocalTime.class);
		} catch (SQLException | RuntimeException | AbstractMethodError e) {
			Time value = results.getTime(columnIndex);
			return value == null ? null : value.toLocalTime().withNano((int) (Math.floorMod(value.getTime(), 1000L) * 1_000_000L));
		}
	}
}
