package com.upandcoding.broadsql.dao.pull;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Covers the grammar for {@code PULL ... AS JSON}/{@code AS MD}/{@code AS HTML} (see
 * docs/PULL_TO_TEXT.md) - pure static-method tests, no database needed. Same "flat file" destination
 * grammar as {@code AS CSV}/{@code AS TXT} (see {@link TestPullCommandParserText}): a bare {@code <name>}
 * with no dot, no {@code MODE} clause.
 */
class TestPullCommandParserFlatFormats {

	@Test
	void parsesAJsonDestination() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO REPORT_CUSTOMERS AS JSON", null);

		Assertions.assertEquals(PullStatement.Format.JSON, statement.getFormat());
		Assertions.assertEquals("REPORT_CUSTOMERS", statement.getTargetName());
		Assertions.assertNull(statement.getTargetTable());
		Assertions.assertEquals(PullStatement.Mode.OVERWRITE, statement.getMode());
	}

	@Test
	void parsesAMarkdownDestination() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO REPORT_CUSTOMERS AS MD", null);

		Assertions.assertEquals(PullStatement.Format.MD, statement.getFormat());
		Assertions.assertEquals("REPORT_CUSTOMERS", statement.getTargetName());
		Assertions.assertNull(statement.getTargetTable());
	}

	@Test
	void parsesAnHtmlDestinationWithAParenthesizedQuery() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse(
				"(SELECT ID FROM CUSTOMER WHERE COUNTRY = 'FR') TO FRENCH_CUSTOMERS AS HTML", null);

		Assertions.assertEquals(PullStatement.Format.HTML, statement.getFormat());
		Assertions.assertEquals("FRENCH_CUSTOMERS", statement.getTargetName());
		Assertions.assertEquals("SELECT ID FROM CUSTOMER WHERE COUNTRY = 'FR'", statement.getSourceQuery());
	}

	@Test
	void rejectsADotInTheJsonDestination() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT.CUSTOMERS AS JSON", null));

		Assertions.assertTrue(ex.getMessage().contains("no dot"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsADotInTheMarkdownDestination() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT.CUSTOMERS AS MD", null));

		Assertions.assertTrue(ex.getMessage().contains("no dot"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsADotInTheHtmlDestination() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT.CUSTOMERS AS HTML", null));

		Assertions.assertTrue(ex.getMessage().contains("no dot"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsAModeClauseForJson() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT_CUSTOMERS AS JSON MODE OVERWRITE", null));

		Assertions.assertTrue(ex.getMessage().contains("MODE is not supported for AS JSON"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsAModeClauseForMarkdown() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT_CUSTOMERS AS MD MODE APPEND KEY(ID)", null));

		Assertions.assertTrue(ex.getMessage().contains("MODE is not supported for AS MD"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsAModeClauseForHtml() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT_CUSTOMERS AS HTML MODE OVERWRITE", null));

		Assertions.assertTrue(ex.getMessage().contains("MODE is not supported for AS HTML"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsAnIllegalCharacterInTheJsonFileName() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO BAD/NAME AS JSON", null));

		Assertions.assertTrue(ex.getMessage().contains("Invalid name"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsAReservedWindowsDeviceNameAsTheHtmlFileName() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO CON AS HTML", null));

		Assertions.assertTrue(ex.getMessage().contains("reserved Windows device name"), "got: " + ex.getMessage());
	}
}
