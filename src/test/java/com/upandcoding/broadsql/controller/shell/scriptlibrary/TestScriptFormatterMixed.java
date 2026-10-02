package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.scripts.StatementSplitter;

/**
 * SPRINT 1909S: a Script may mix SQL and BroadSQL commands, so the formatter must never rewrite a
 * BroadSQL command, must preserve everything between statements, and must decline (not corrupt) when the
 * statement model cannot be trusted. Formatter and executor share one statement model
 * ({@link StatementSplitter#splitSpans}).
 */
class TestScriptFormatterMixed {

	private final ScriptFormatterService formatter = new ScriptFormatterService();

	private static final String[] COMMAND_STATEMENTS = {
			"CONNECT   PROD",
			"LIB   RUN   maintenance/cleanup.bsql   'a b'   \"c d\"",
			"@foo.bsql   1   2",
			"@\"C:\\My Scripts\\foo.bsql\"   value1   \"value 2\"",
			"@./helper.bsql",
			"EXPORT   RESULT   customers.xlsx",
			"SHOW    TABLES",
			"JS   RUN   thing.js",
	};

	@Test
	void everyKindOfBroadSqlCommandSurvivesByteForByte() {
		for (String command : COMMAND_STATEMENTS) {
			String content = "select  a,b  from   t;\n" + command + ";\nselect  c  from   u;\n";

			FormatOutcome outcome = formatter.format(content);

			Assertions.assertTrue(outcome.isSupported(), command + ": " + outcome.reason());
			Assertions.assertTrue(outcome.formattedContent().contains(command + ";"), "the command must be untouched: " + command + "\n" + outcome.formattedContent());
		}
	}

	@Test
	void theAtSignCommandIsProtectedEvenWhenItIsTheOnlyStatement() {
		FormatOutcome outcome = formatter.format("@foo.bsql    a    b;");
		Assertions.assertTrue(outcome.isSupported());
		Assertions.assertEquals("@foo.bsql    a    b;", outcome.formattedContent());
	}

	@Test
	void formatSelectionHonoursTheSameProtectionAsFormatDocument() {
		FormatOutcome outcome = formatter.formatSelection("select  a  from   t;\n@foo.bsql    1    2;\nLIB   RUN   x;");

		Assertions.assertTrue(outcome.isSupported(), outcome.reason());
		Assertions.assertTrue(outcome.formattedContent().contains("@foo.bsql    1    2;"), outcome.formattedContent());
		Assertions.assertTrue(outcome.formattedContent().contains("LIB   RUN   x;"), outcome.formattedContent());
	}

	@Test
	void separatorsCommentsAndBlankLinesBetweenStatementsAreKeptExactly() {
		String content = "-- header note\n\nselect 1;\n\n\n/* block */ CONNECT   PROD; -- trailing\n\n-- final comment\n";

		FormatOutcome outcome = formatter.format(content);

		Assertions.assertTrue(outcome.isSupported(), outcome.reason());
		String formatted = outcome.formattedContent();
		Assertions.assertTrue(formatted.startsWith("-- header note\n\n"), formatted);
		Assertions.assertTrue(formatted.contains("/* block */ CONNECT   PROD; -- trailing"), formatted);
		Assertions.assertTrue(formatted.endsWith("\n\n-- final comment\n"), formatted);
	}

	@Test
	void aTrailingCommentAfterTheLastSemicolonIsNotLost() {
		FormatOutcome outcome = formatter.format("select 1; -- the end");
		Assertions.assertTrue(outcome.isSupported());
		Assertions.assertTrue(outcome.formattedContent().endsWith("; -- the end"), outcome.formattedContent());
	}

	@Test
	void anUnterminatedQuoteDeclinesTheWholeDocumentAndExplainsWhere() {
		String content = "select 1;\nLIB RUN x 'oops;\nselect 2;";

		FormatOutcome outcome = formatter.format(content);

		Assertions.assertFalse(outcome.isSupported());
		Assertions.assertTrue(outcome.reason().contains("Unterminated single quote") && outcome.reason().contains("line 2"), outcome.reason());
	}

	@Test
	void anUnterminatedBlockCommentAlsoDeclines() {
		Assertions.assertFalse(formatter.format("select 1; /* never closed").isSupported());
	}

	@Test
	void semicolonsInsideStringLiteralsDoNotSplitStatements() {
		FormatOutcome outcome = formatter.format("select  'a;b'  from   t;\nSHOW   TABLES;");
		Assertions.assertTrue(outcome.isSupported(), outcome.reason());
		Assertions.assertTrue(outcome.formattedContent().contains("'a;b'"), outcome.formattedContent());
		Assertions.assertTrue(outcome.formattedContent().contains("SHOW   TABLES;"), outcome.formattedContent());
	}

	@Test
	void formattingTwiceChangesNothingFurtherOnAMixedScript() {
		String content = "-- @description: mixed\nselect  a,b  from   t;\n@foo.bsql   1;\nSHOW   TABLES;\nselect 1;\n";

		FormatOutcome first = formatter.format(content);
		FormatOutcome second = formatter.format(first.formattedContent());

		Assertions.assertTrue(first.isSupported() && second.isSupported());
		Assertions.assertEquals(first.formattedContent(), second.formattedContent());
	}

	@Test
	void theFormatterSeesExactlyTheStatementsTheExecutorSees() {
		String text = "select 1; @foo.bsql 1 2; -- c\nLIB RUN x; select 'a;b';";
		List<String> executorStatements = StatementSplitter.split(text);

		int seen = 0;
		for (StatementSplitter.Span span : StatementSplitter.splitSpans(text).getSpans()) {
			Assertions.assertEquals(executorStatements.get(seen++), span.getText());
		}
		Assertions.assertEquals(executorStatements.size(), seen);
		Assertions.assertTrue(ScriptFormatterService.isBroadSqlCommand("@foo.bsql 1 2"));
		Assertions.assertTrue(ScriptFormatterService.isBroadSqlCommand("LIB RUN x"));
		Assertions.assertFalse(ScriptFormatterService.isBroadSqlCommand("select 1"));
	}

	@Test
	void theMetadataHeaderIsKeptOnDocumentFormatButNotInvented() {
		FormatOutcome withHeader = formatter.format("-- @description: d\n@foo.bsql   1;");
		Assertions.assertTrue(withHeader.formattedContent().startsWith("-- @description: d\n"), withHeader.formattedContent());
		FormatOutcome without = formatter.format("@foo.bsql   1;");
		Assertions.assertEquals("@foo.bsql   1;", without.formattedContent());
	}
}
