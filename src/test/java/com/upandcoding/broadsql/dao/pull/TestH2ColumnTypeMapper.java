package com.upandcoding.broadsql.dao.pull;

import java.sql.Types;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;

/**
 * Direct tests of {@link H2ColumnTypeMapper#toH2DdlType}, a pure function of a JDBC type code - no
 * database connection needed. Covers the fix that lets {@code PULL ... AS H2} accept Oracle's
 * proprietary {@code TIMESTAMP WITH TIME ZONE}/{@code TIMESTAMP WITH LOCAL TIME ZONE} columns (see
 * {@link OracleJdbcTypes}, docs/TODO.md "User feedback after release 5.0.8 deployment", item 3) -
 * before this fix, both fell into the {@code default} case and were rejected exactly like a genuinely
 * unsupported type (BLOB, ARRAY, etc.), since neither has a {@code java.sql.Types} constant of its own.
 * Every time-zone-aware timestamp (standard code, Oracle's codes, PostgreSQL's {@code timestamptz} reported as
 * {@code TIMESTAMP}) now keeps its offset in H2, and times and timestamps keep every fractional digit.
 */
class TestH2ColumnTypeMapper {

	@Test
	void mapsOracleTimestampWithLocalTimeZoneToATimestampWithTimeZone() throws BroadSQLException {
		String ddlType = H2ColumnTypeMapper.toH2DdlType(OracleJdbcTypes.TIMESTAMPLTZ, 11, 6, "LAST_UPDATE", "TIMESTAMP(6) WITH LOCAL TIME ZONE");

		Assertions.assertEquals("TIMESTAMP(9) WITH TIME ZONE", ddlType);
	}

	@Test
	void mapsOracleTimestampWithTimeZoneToATimestampWithTimeZone() throws BroadSQLException {
		String ddlType = H2ColumnTypeMapper.toH2DdlType(OracleJdbcTypes.TIMESTAMPTZ, 13, 6, "CREATED_AT", "TIMESTAMP(6) WITH TIME ZONE");

		Assertions.assertEquals("TIMESTAMP(9) WITH TIME ZONE", ddlType);
	}

	@Test
	void mapsPostgresqlTimestamptzReportedAsTimestampToATimestampWithTimeZone() throws BroadSQLException {
		Assertions.assertEquals("TIMESTAMP(9) WITH TIME ZONE", H2ColumnTypeMapper.toH2DdlType(Types.TIMESTAMP, 35, 6, "CREATED_AT", "timestamptz"));
		Assertions.assertEquals("TIME(9) WITH TIME ZONE", H2ColumnTypeMapper.toH2DdlType(Types.TIME, 21, 6, "AT", "timetz"));
	}

	@Test
	void stillMapsAnOrdinaryTimestampToATimestampWithoutZone() throws BroadSQLException {
		Assertions.assertEquals("TIMESTAMP(9)", H2ColumnTypeMapper.toH2DdlType(Types.TIMESTAMP, 23, 0, "CREATED_AT", "TIMESTAMP"));
		Assertions.assertEquals("TIME(9)", H2ColumnTypeMapper.toH2DdlType(Types.TIME, 8, 0, "AT", "TIME"));
	}

	@Test
	void stillMapsTheStandardJdbcTimestampWithTimezoneToItsOwnH2Type() throws BroadSQLException {
		Assertions.assertEquals("TIMESTAMP(9) WITH TIME ZONE",
				H2ColumnTypeMapper.toH2DdlType(Types.TIMESTAMP_WITH_TIMEZONE, 29, 6, "CREATED_AT", "TIMESTAMPTZ"));
	}

	@Test
	void stillRejectsAGenuinelyUnsupportedType() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> H2ColumnTypeMapper.toH2DdlType(Types.BLOB, 0, 0, "PHOTO", "BLOB"));

		Assertions.assertTrue(ex.getMessage().contains("PHOTO"));
		Assertions.assertTrue(ex.getMessage().contains("BLOB"));
	}
}
