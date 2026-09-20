package com.upandcoding.broadsql.dao.pull.html;

import java.sql.Types;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;

/**
 * Direct tests of {@link PullHtmlTypeMapper#validateSupported}, a pure function of a JDBC type code -
 * no database connection needed. Covers the fix that lets {@code PULL ... AS HTML} accept Oracle's
 * proprietary {@code TIMESTAMP WITH TIME ZONE}/{@code TIMESTAMP WITH LOCAL TIME ZONE} columns (see
 * {@link OracleJdbcTypes}, docs/TODO.md "User feedback after release 5.0.8 deployment", item 3).
 */
class TestPullHtmlTypeMapper {

	@Test
	void acceptsOracleTimestampWithLocalTimeZone() {
		Assertions.assertDoesNotThrow(() -> PullHtmlTypeMapper.validateSupported(
				OracleJdbcTypes.TIMESTAMPLTZ, "LAST_UPDATE", "TIMESTAMP(6) WITH LOCAL TIME ZONE"));
	}

	@Test
	void acceptsOracleTimestampWithTimeZone() {
		Assertions.assertDoesNotThrow(() -> PullHtmlTypeMapper.validateSupported(
				OracleJdbcTypes.TIMESTAMPTZ, "CREATED_AT", "TIMESTAMP(6) WITH TIME ZONE"));
	}

	@Test
	void stillAcceptsAnOrdinaryTimestamp() {
		Assertions.assertDoesNotThrow(() -> PullHtmlTypeMapper.validateSupported(Types.TIMESTAMP, "CREATED_AT", "TIMESTAMP"));
	}

	@Test
	void stillRejectsAGenuinelyUnsupportedType() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullHtmlTypeMapper.validateSupported(Types.BLOB, "PHOTO", "BLOB"));

		Assertions.assertTrue(ex.getMessage().contains("PHOTO"));
		Assertions.assertTrue(ex.getMessage().contains("BLOB"));
	}
}
