package com.upandcoding.broadsql.dao.pull;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Covers the grammar for {@code PULL API RESULT TO ...} (the fourth {@code PULL} source form, see
 * docs/BroadSQL XT02 overnight batch plan, sub-sprint 7 - "API result export and local snapshot") -
 * {@code PullCommandParser.parse} is a pure static method, so these run without a database or a
 * {@code Command}. The most important case here is {@link #aRealTableLiterallyNamedApiStillParsesAsAnOrdinaryIdentifierSource()}:
 * the two-token lookahead for {@code API RESULT} must never misfire on a genuine table named {@code API}.
 */
class TestPullCommandParserApiResult {

	@Test
	void parsesAnApiResultSourceToH2() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("API RESULT TO WORKCOPY.USERS AS H2", null);

		Assertions.assertTrue(statement.isApiResultSource());
		Assertions.assertNull(statement.getSourceQuery(), "an API RESULT source has no SQL to run");
		Assertions.assertEquals(PullStatement.Format.H2, statement.getFormat());
		Assertions.assertEquals("WORKCOPY", statement.getTargetName());
		Assertions.assertEquals("USERS", statement.getTargetTable());
		Assertions.assertEquals(PullStatement.Mode.OVERWRITE, statement.getMode());
	}

	@Test
	void parsesAnApiResultSourceToXlsx() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("API RESULT TO REPORT.USERS AS XLSX", null);

		Assertions.assertTrue(statement.isApiResultSource());
		Assertions.assertEquals(PullStatement.Format.XLSX, statement.getFormat());
		Assertions.assertEquals("REPORT", statement.getTargetName());
		Assertions.assertEquals("USERS", statement.getTargetTable());
	}

	@Test
	void parsesAnApiResultSourceToOds() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("API RESULT TO REPORT.USERS AS ODS", null);

		Assertions.assertTrue(statement.isApiResultSource());
		Assertions.assertEquals(PullStatement.Format.ODS, statement.getFormat());
	}

	@Test
	void parsesAnApiResultSourceToCsv() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("API RESULT TO USERS AS CSV", null);

		Assertions.assertTrue(statement.isApiResultSource());
		Assertions.assertEquals(PullStatement.Format.CSV, statement.getFormat());
		Assertions.assertEquals("USERS", statement.getTargetName());
		Assertions.assertNull(statement.getTargetTable());
	}

	@Test
	void parsesAnApiResultSourceToJson() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("API RESULT TO USERS AS JSON", null);

		Assertions.assertTrue(statement.isApiResultSource());
		Assertions.assertEquals(PullStatement.Format.JSON, statement.getFormat());
	}

	@Test
	void isCaseInsensitive() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("api result TO WORKCOPY.USERS AS H2", null);

		Assertions.assertTrue(statement.isApiResultSource());
	}

	@Test
	void rejectsModeAppendForAnApiResultSource() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("API RESULT TO WORKCOPY.USERS AS H2 MODE APPEND KEY(ID)", null));

		Assertions.assertTrue(ex.getMessage().contains("MODE APPEND is not supported for PULL API RESULT"),
				"expected a clear MODE-APPEND-not-supported message, got: " + ex.getMessage());
	}

	/**
	 * The regression case the two-token lookahead exists to protect against: a real table literally named
	 * {@code API} (its very next token is not the literal word {@code RESULT}) must still parse as the
	 * ordinary one-token identifier source, exactly as it always has.
	 */
	@Test
	void aRealTableLiterallyNamedApiStillParsesAsAnOrdinaryIdentifierSource() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("API TO WORKCOPY.API AS H2", null);

		Assertions.assertFalse(statement.isApiResultSource());
		Assertions.assertEquals("SELECT * FROM API", statement.getSourceQuery());
		Assertions.assertEquals("WORKCOPY", statement.getTargetName());
		Assertions.assertEquals("API", statement.getTargetTable());
	}

	/**
	 * Same regression case, but with the table named {@code API} used as the flat-file destination's
	 * target name too, just to further confirm nothing about "API" anywhere in the line is special except
	 * the exact two-token phrase {@code API RESULT} at the very start of the source.
	 */
	@Test
	void aTableNamedApiFollowedByAnUnrelatedTokenStillParsesAsAnOrdinaryIdentifierSource() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("API TO API_EXPORT AS CSV", null);

		Assertions.assertFalse(statement.isApiResultSource());
		Assertions.assertEquals("SELECT * FROM API", statement.getSourceQuery());
		Assertions.assertEquals("API_EXPORT", statement.getTargetName());
	}
}
