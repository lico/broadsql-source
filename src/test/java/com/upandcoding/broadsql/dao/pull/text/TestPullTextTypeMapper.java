package com.upandcoding.broadsql.dao.pull.text;

import java.sql.Types;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;

/**
 * Direct tests of {@link PullTextTypeMapper#validateSupported}, a pure function of a JDBC type code -
 * no database connection needed. Covers the fix that lets {@code PULL ... AS CSV/TXT} accept Oracle's
 * proprietary {@code TIMESTAMP WITH TIME ZONE}/{@code TIMESTAMP WITH LOCAL TIME ZONE} columns (see
 * {@link OracleJdbcTypes}, docs/TODO.md "User feedback after release 5.0.8 deployment", item 3).
 */
class TestPullTextTypeMapper {

	@Test
	void acceptsOracleTimestampWithLocalTimeZone() {
		Assertions.assertDoesNotThrow(() -> PullTextTypeMapper.validateSupported(
				OracleJdbcTypes.TIMESTAMPLTZ, "LAST_UPDATE", "TIMESTAMP(6) WITH LOCAL TIME ZONE"));
	}

	@Test
	void acceptsOracleTimestampWithTimeZone() {
		Assertions.assertDoesNotThrow(() -> PullTextTypeMapper.validateSupported(
				OracleJdbcTypes.TIMESTAMPTZ, "CREATED_AT", "TIMESTAMP(6) WITH TIME ZONE"));
	}

	@Test
	void stillAcceptsAnOrdinaryTimestamp() {
		Assertions.assertDoesNotThrow(() -> PullTextTypeMapper.validateSupported(Types.TIMESTAMP, "CREATED_AT", "TIMESTAMP"));
	}

	@Test
	void stillRejectsAGenuinelyUnsupportedType() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullTextTypeMapper.validateSupported(Types.BLOB, "PHOTO", "BLOB"));

		Assertions.assertTrue(ex.getMessage().contains("PHOTO"));
		Assertions.assertTrue(ex.getMessage().contains("BLOB"));
	}
}
