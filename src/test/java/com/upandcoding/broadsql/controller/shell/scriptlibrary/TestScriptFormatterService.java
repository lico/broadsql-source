package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestScriptFormatterService {

	private final ScriptFormatterService formatter = new ScriptFormatterService();

	@Test
	void sqlLibraryEntryFormatsTheBodyAndKeepsTheHeaderUntouched() {
		String content = "-- @description: d\n-- @status: draft\nselect a,b from t where x=1;";

		FormatOutcome outcome = formatter.format(content);

		Assertions.assertTrue(outcome.isSupported(), outcome.reason());
		Assertions.assertTrue(outcome.formattedContent().startsWith("-- @description: d\n-- @status: draft\n"), outcome.formattedContent());
		Assertions.assertTrue(outcome.formattedContent().contains("from"), "keyword casing must be left exactly as typed:\n" + outcome.formattedContent());
	}

	@Test
	void stringLiteralsAreNeverAltered() {
		String content = "select 'a  b   --not a comment' from dual;";

		FormatOutcome outcome = formatter.format(content);

		Assertions.assertTrue(outcome.isSupported());
		Assertions.assertTrue(outcome.formattedContent().contains("'a  b   --not a comment'"), outcome.formattedContent());
	}

	@Test
	void aBroadSqlCommandStatementInAMixedScriptIsLeftByteForByteUntouched() {
		String content = "SHOW   TABLES;\nselect  *  from customer;";

		FormatOutcome outcome = formatter.format(content);

		Assertions.assertTrue(outcome.isSupported(), outcome.reason());
		Assertions.assertTrue(outcome.formattedContent().contains("SHOW   TABLES"), "the BroadSQL command's original spacing must be untouched:\n" + outcome.formattedContent());
	}

	@Test
	void aSqlStatementInAMixedScriptIsReformatted() {
		String content = "SHOW TABLES;\nselect  *  from   customer;";

		FormatOutcome outcome = formatter.format(content);

		Assertions.assertTrue(outcome.isSupported());
		Assertions.assertFalse(outcome.formattedContent().contains("select  *  from   customer"), "the SQL statement's original multi-space runs must have been reformatted:\n" + outcome.formattedContent());
	}

	@Test
	void commentsBetweenStatementsAreNeverDropped() {
		String content = "SHOW TABLES;\n-- a note about the next statement\nselect * from customer;";

		FormatOutcome outcome = formatter.format(content);

		Assertions.assertTrue(outcome.isSupported());
		Assertions.assertTrue(outcome.formattedContent().contains("-- a note about the next statement"), outcome.formattedContent());
	}

	@Test
	void metadataHeaderIsAlsoPreservedForMixedScripts() {
		String content = "-- @description: a script\nSHOW TABLES;\nselect * from customer;";

		FormatOutcome outcome = formatter.format(content);

		Assertions.assertTrue(outcome.isSupported());
		Assertions.assertTrue(outcome.formattedContent().startsWith("-- @description: a script\n"), outcome.formattedContent());
	}

	@Test
	void anUnterminatedStringLeavesTheWholeDocumentUnchangedAndReportsWhy() {
		String content = "select 'unterminated;";

		FormatOutcome outcome = formatter.format(content);

		Assertions.assertFalse(outcome.isSupported());
		Assertions.assertNotNull(outcome.reason());
	}

	@Test
	void formattingIsIdempotentOnASimpleQuery() {
		String content = "select a, b from t where x = 1;";

		FormatOutcome first = formatter.format(content);
		FormatOutcome second = formatter.format(first.formattedContent());

		Assertions.assertEquals(first.formattedContent(), second.formattedContent());
	}

	@Test
	void formattingNeverModifiesTheInputContentString() {
		String content = "select  a,b  from t;";
		formatter.format(content);

		Assertions.assertEquals("select  a,b  from t;", content);
	}
}
