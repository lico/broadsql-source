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
 */
class TestH2ColumnTypeMapper {

	@Test
	void mapsOracleTimestampWithLocalTimeZoneToPlainTimestamp() throws BroadSQLException {
		String ddlType = H2ColumnTypeMapper.toH2DdlType(OracleJdbcTypes.TIMESTAMPLTZ, 11, 6, "LAST_UPDATE", "TIMESTAMP(6) WITH LOCAL TIME ZONE");

		Assertions.assertEquals("TIMESTAMP", ddlType);
	}

	@Test
	void mapsOracleTimestampWithTimeZoneToPlainTimestamp() throws BroadSQLException {
		String ddlType = H2ColumnTypeMapper.toH2DdlType(OracleJdbcTypes.TIMESTAMPTZ, 13, 6, "CREATED_AT", "TIMESTAMP(6) WITH TIME ZONE");

		Assertions.assertEquals("TIMESTAMP", ddlType);
	}

	@Test
	void stillMapsAnOrdinaryTimestampToPlainTimestamp() throws BroadSQLException {
		Assertions.assertEquals("TIMESTAMP", H2ColumnTypeMapper.toH2DdlType(Types.TIMESTAMP, 23, 0, "CREATED_AT", "TIMESTAMP"));
	}

	@Test
	void stillMapsTheStandardJdbcTimestampWithTimezoneToItsOwnH2Type() throws BroadSQLException {
		Assertions.assertEquals("TIMESTAMP WITH TIME ZONE",
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
