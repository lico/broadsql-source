package com.upandcoding.broadsql.dao.pull;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Covers the grammar extension for {@code PULL ... AS CSV}/{@code AS TXT} (see docs/PULL_TO_TEXT.md) -
 * pure static-method tests, no database needed. Unlike {@code AS H2}/{@code AS XLSX}/{@code AS ODS}, the
 * destination for these two formats is a bare {@code <name>} with no dot - a dot is a parse-time error,
 * not a table/tab separator.
 */
class TestPullCommandParserText {

	@Test
	void parsesACsvDestination() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO REPORT_CUSTOMERS AS CSV", null);

		Assertions.assertEquals(PullStatement.Format.CSV, statement.getFormat());
		Assertions.assertEquals("REPORT_CUSTOMERS", statement.getTargetName());
		Assertions.assertNull(statement.getTargetTable(), "CSV/TXT destinations have no tab/table part");
		Assertions.assertEquals(PullStatement.Mode.OVERWRITE, statement.getMode());
	}

	@Test
	void parsesATxtDestinationWithAParenthesizedQuery() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse(
				"(SELECT ID FROM CUSTOMER WHERE COUNTRY = 'FR') TO FRENCH_CUSTOMERS AS TXT", null);

		Assertions.assertEquals(PullStatement.Format.TXT, statement.getFormat());
		Assertions.assertEquals("FRENCH_CUSTOMERS", statement.getTargetName());
		Assertions.assertNull(statement.getTargetTable());
		Assertions.assertEquals("SELECT ID FROM CUSTOMER WHERE COUNTRY = 'FR'", statement.getSourceQuery());
	}

	@Test
	void rejectsADotInTheCsvDestination() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT.CUSTOMERS AS CSV", null));

		Assertions.assertTrue(ex.getMessage().contains("no dot"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsADotInTheTxtDestination() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT.CUSTOMERS AS TXT", null));

		Assertions.assertTrue(ex.getMessage().contains("no dot"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsAModeClauseForCsv() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT_CUSTOMERS AS CSV MODE OVERWRITE", null));

		Assertions.assertTrue(ex.getMessage().contains("MODE is not supported for AS CSV"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsAModeClauseForTxt() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT_CUSTOMERS AS TXT MODE APPEND KEY(ID)", null));

		Assertions.assertTrue(ex.getMessage().contains("MODE is not supported for AS TXT"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsAnIllegalCharacterInTheCsvFileName() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO BAD/NAME AS CSV", null));

		Assertions.assertTrue(ex.getMessage().contains("Invalid name"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsAReservedWindowsDeviceNameAsTheTxtFileName() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO CON AS TXT", null));

		Assertions.assertTrue(ex.getMessage().contains("reserved Windows device name"), "got: " + ex.getMessage());
	}

	/**
	 * GitHub #152 corrected this test's own name and assertion: before that sprint, {@code AS H2} (like
	 * {@code AS XLSX}/{@code AS ODS}) genuinely required a dot in the destination - unlike {@code AS CSV}/
	 * {@code AS TXT} above, which never had a table/tab concept to omit at all. #152 made that dot
	 * optional for every dot-capable format (H2/XLSX/ODS alike), defaulting the table/tab part to
	 * {@code DATA} - so {@code AS H2} with no dot is no longer a parse error, it is
	 * {@code AS H2} against table {@code DATA}, exactly as {@code AS XLSX}/{@code AS ODS} already behave
	 * (see {@link TestPullCommandParserDefaults#h2DestinationAlsoDefaultsToData}). This test was renamed
	 * from {@code asH2StillRequiresADot} to assert the new, intended behavior instead of the retired one.
	 */
	@Test
	void asH2WithNoDotDefaultsTheTableToData() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO WORKCOPY AS H2", null);

		Assertions.assertEquals("WORKCOPY", statement.getTargetName());
		Assertions.assertEquals("DATA", statement.getTargetTable());
	}
}
