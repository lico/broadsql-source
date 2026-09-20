package com.upandcoding.broadsql.dao.pull;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Covers the grammar extension for {@code PULL ... AS XLSX}/{@code AS ODS} (see
 * docs/PULL_TO_SPREADSHEET.md) - {@code PullCommandParser.parse} is a pure static method, so these run
 * without a database or a {@code Command}. {@code AS H2}'s own grammar is covered separately (its
 * existing behavior is deliberately unchanged by this extension, asserted here only to the extent of
 * confirming it still resolves to {@link PullStatement.Format#H2}).
 */
class TestPullCommandParserSpreadsheet {

	@Test
	void parsesAnXlsxDestination() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO REPORT.CUSTOMERS AS XLSX", null);

		Assertions.assertEquals(PullStatement.Format.XLSX, statement.getFormat());
		Assertions.assertEquals("REPORT", statement.getTargetName());
		Assertions.assertEquals("CUSTOMERS", statement.getTargetTable());
		Assertions.assertEquals(PullStatement.Mode.OVERWRITE, statement.getMode());
		Assertions.assertEquals("SELECT * FROM CUSTOMER", statement.getSourceQuery());
	}

	@Test
	void parsesAnOdsDestinationWithAParenthesizedQuery() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse(
				"(SELECT ID FROM CUSTOMER WHERE COUNTRY = 'FR') TO REPORT.FRENCH AS ODS", null);

		Assertions.assertEquals(PullStatement.Format.ODS, statement.getFormat());
		Assertions.assertEquals("REPORT", statement.getTargetName());
		Assertions.assertEquals("FRENCH", statement.getTargetTable());
		Assertions.assertEquals("SELECT ID FROM CUSTOMER WHERE COUNTRY = 'FR'", statement.getSourceQuery());
	}

	@Test
	void asH2StillResolvesToTheH2Format() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO WORKCOPY.CUSTOMER AS H2", null);

		Assertions.assertEquals(PullStatement.Format.H2, statement.getFormat());
	}

	@Test
	void rejectsAModeClauseForXlsx() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT.CUSTOMERS AS XLSX MODE OVERWRITE", null));

		Assertions.assertTrue(ex.getMessage().contains("MODE is not supported for AS XLSX"),
				"expected a MODE-not-supported error, got: " + ex.getMessage());
	}

	@Test
	void rejectsAModeAppendClauseForOds() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT.CUSTOMERS AS ODS MODE APPEND KEY(ID)", null));

		Assertions.assertTrue(ex.getMessage().contains("MODE is not supported for AS ODS"),
				"expected a MODE-not-supported error, got: " + ex.getMessage());
	}

	@Test
	void rejectsAnIllegalCharacterInTheTabName() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT.BAD/NAME AS XLSX", null));

		Assertions.assertTrue(ex.getMessage().contains("Invalid tab name"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsQueriesAsATabNameBecauseItIsReservedForTheInfoTab() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT.QUERIES AS XLSX", null));

		Assertions.assertTrue(ex.getMessage().contains("reserved for the per-file query log tab"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsATabNameLongerThan31Characters() {
		String tooLong = "A".repeat(32);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT." + tooLong + " AS XLSX", null));

		Assertions.assertTrue(ex.getMessage().contains("limited to"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsAnIllegalCharacterInTheFileName() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO BAD/NAME.CUSTOMERS AS XLSX", null));

		Assertions.assertTrue(ex.getMessage().contains("Invalid name"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsAReservedWindowsDeviceNameAsTheFileName() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO CON.CUSTOMERS AS ODS", null));

		Assertions.assertTrue(ex.getMessage().contains("reserved Windows device name"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsAnUnknownFormat() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT.CUSTOMERS AS YAML", null));

		Assertions.assertTrue(ex.getMessage().contains("use AS H2, AS XLSX, AS ODS, AS CSV, AS TXT, AS JSON, AS MD, or AS HTML"), "got: " + ex.getMessage());
	}
}
