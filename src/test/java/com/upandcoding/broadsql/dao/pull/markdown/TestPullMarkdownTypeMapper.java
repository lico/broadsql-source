package com.upandcoding.broadsql.dao.pull.markdown;

import java.sql.Types;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;

/**
 * Direct tests of {@link PullMarkdownTypeMapper#validateSupported}, a pure function of a JDBC type
 * code - no database connection needed. Covers the fix that lets {@code PULL ... AS MD} accept
 * Oracle's proprietary {@code TIMESTAMP WITH TIME ZONE}/{@code TIMESTAMP WITH LOCAL TIME ZONE} columns
 * (see {@link OracleJdbcTypes}, docs/TODO.md "User feedback after release 5.0.8 deployment", item 3).
 */
class TestPullMarkdownTypeMapper {

	@Test
	void acceptsOracleTimestampWithLocalTimeZone() {
		Assertions.assertDoesNotThrow(() -> PullMarkdownTypeMapper.validateSupported(
				OracleJdbcTypes.TIMESTAMPLTZ, "LAST_UPDATE", "TIMESTAMP(6) WITH LOCAL TIME ZONE"));
	}

	@Test
	void acceptsOracleTimestampWithTimeZone() {
		Assertions.assertDoesNotThrow(() -> PullMarkdownTypeMapper.validateSupported(
				OracleJdbcTypes.TIMESTAMPTZ, "CREATED_AT", "TIMESTAMP(6) WITH TIME ZONE"));
	}

	@Test
	void stillAcceptsAnOrdinaryTimestamp() {
		Assertions.assertDoesNotThrow(() -> PullMarkdownTypeMapper.validateSupported(Types.TIMESTAMP, "CREATED_AT", "TIMESTAMP"));
	}

	@Test
	void stillRejectsAGenuinelyUnsupportedType() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullMarkdownTypeMapper.validateSupported(Types.BLOB, "PHOTO", "BLOB"));

		Assertions.assertTrue(ex.getMessage().contains("PHOTO"));
		Assertions.assertTrue(ex.getMessage().contains("BLOB"));
	}
}
