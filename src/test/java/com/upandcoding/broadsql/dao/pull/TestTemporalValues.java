package com.upandcoding.broadsql.dao.pull;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.OracleJdbcTypes;

/** How exports classify and read time-zone-aware values, including on drivers without JDBC 4.2 date mappings. */
class TestTemporalValues {

	@Test
	void everyZoneAwareTimestampIsHandledAsTimestampWithTimeZone() {
		Assertions.assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, TemporalValues.effectiveType(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE"));
		Assertions.assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, TemporalValues.effectiveType(OracleJdbcTypes.TIMESTAMPTZ, "TIMESTAMP WITH TIME ZONE"));
		Assertions.assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, TemporalValues.effectiveType(OracleJdbcTypes.TIMESTAMPLTZ, "TIMESTAMP WITH LOCAL TIME ZONE"));
		// PostgreSQL reports timestamptz and timetz under the zone-less codes
		Assertions.assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, TemporalValues.effectiveType(Types.TIMESTAMP, "timestamptz"));
		Assertions.assertEquals(Types.TIME_WITH_TIMEZONE, TemporalValues.effectiveType(Types.TIME, "timetz"));
		// zone-less and other types are unchanged
		Assertions.assertEquals(Types.TIMESTAMP, TemporalValues.effectiveType(Types.TIMESTAMP, "timestamp"));
		Assertions.assertEquals(Types.TIMESTAMP, TemporalValues.effectiveType(Types.TIMESTAMP, "DATETIME"));
		Assertions.assertEquals(Types.VARCHAR, TemporalValues.effectiveType(Types.VARCHAR, "with time zone"));
	}

	/** A result set whose driver has no {@code getObject(int, Class)} mapping and returns a {@link Timestamp}. */
	private static ResultSet legacyDriver(Timestamp value) {
		return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] { ResultSet.class }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "getObject":
					if (args.length == 2) {
						throw new SQLFeatureNotSupportedException("no JDBC 4.2 mapping");
					}
					return value;
				case "getTimestamp":
					return value;
				default:
					throw new UnsupportedOperationException(method.getName());
			}
		});
	}

	@Test
	void withoutAnOffsetMappingTheInstantIsKeptInUtcWhateverTheJvmZone() throws Exception {
		Instant instant = OffsetDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneOffset.ofHours(5)).toInstant();
		TimeZone original = TimeZone.getDefault();
		try {
			for (String zone : new String[] { "Europe/Paris", "America/New_York", "Asia/Tokyo" }) {
				TimeZone.setDefault(TimeZone.getTimeZone(zone));
				OffsetDateTime read = TemporalValues.getOffsetDateTime(legacyDriver(Timestamp.from(instant)), 1);
				Assertions.assertEquals(OffsetDateTime.of(2024, 1, 15, 5, 0, 0, 0, ZoneOffset.UTC), read, zone);
				Assertions.assertEquals("2024-01-15 05:00:00+00:00", TemporalText.offsetDateTime(read), zone);
			}
		} finally {
			TimeZone.setDefault(original);
		}
		Assertions.assertNull(TemporalValues.getOffsetDateTime(legacyDriver(null), 1));
	}

	@Test
	void aSpreadsheetCellHoldsTheInstantInUtc() {
		Assertions.assertEquals(LocalDateTime.of(2024, 1, 15, 5, 0),
				TemporalValues.toUtc(OffsetDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneOffset.ofHours(5))));
		Assertions.assertEquals(LocalDateTime.of(2024, 1, 15, 15, 0, 0, 123_456_000),
				TemporalValues.toUtc(OffsetDateTime.of(2024, 1, 15, 10, 0, 0, 123_456_000, ZoneOffset.ofHours(-5))));
	}

	@Test
	void theTextFormKeepsTheOffsetAndTheFraction() {
		OffsetDateTime value = OffsetDateTime.of(2024, 1, 15, 10, 0, 0, 123_456_000, ZoneOffset.ofHours(5));
		Assertions.assertEquals("2024-01-15 10:00:00.123456+05:00", TemporalText.offsetDateTime(value));
		Assertions.assertEquals("2024-01-15T10:00:00.123456+05:00", TemporalText.isoOffsetDateTime(value));
		Assertions.assertEquals("2024-01-15 10:00:00+00:00", TemporalText.offsetDateTime(OffsetDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC)));
	}
}
