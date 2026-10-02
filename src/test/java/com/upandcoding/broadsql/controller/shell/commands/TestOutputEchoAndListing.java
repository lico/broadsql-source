package com.upandcoding.broadsql.controller.shell.commands;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptValue;

/**
 * SPRINT 0110A: {@code OUTPUT QUIET|NORMAL} (spec section 11, each row of the 11.4 table in both modes, on screen
 * and in the activity log), {@code ECHO} (10) and {@code SHOW SCRIPT VARIABLES} (7.9).
 */
class TestOutputEchoAndListing extends ScriptingTestBase {

	/** One Script producing every output category of the 11.4 table. */
	private String everyCategory(String mode) {
		return "OUTPUT " + mode + ";\n" // 1
				+ "INSERT INTO customer VALUES (1, 'a', 'FR');\n" // row-count feedback, blank separator
				+ "LET who = 'Ann';\n" // LET confirmation
				+ "SELECT name AS RESULT_COLUMN FROM customer WHERE id = ${id};\n" // bound-value line, result table and footer
				+ "SHOW SCRIPT VARIABLES;\n" // listing
				+ "ECHO 'Hello ${who}';\n" // ECHO
				+ "SHOW TABLES;\n"; // other command output
	}

	@Test
	void normalShowsEverything() throws Exception {
		enableActivityLog();
		lib("all.bsql", everyCategory("NORMAL"));
		ScriptRunResult r = run("@all.bsql id=1;");
		Assertions.assertEquals(ScriptStatus.SUCCESS, r.getStatus(), output());
		String screen = output();
		for (String visible : new String[] { "Found 7 queries", "INSERT INTO customer VALUES (1, 'a', 'FR')", "1 row(s) created.", "who = 'Ann' (VARCHAR)",
				"-- id = 1", "RESULT_COLUMN", "rows fetched", "Hello Ann", "CUSTOMER", "all.bsql: SUCCESS" }) {
			Assertions.assertTrue(screen.contains(visible), visible + " missing:\n" + screen);
		}
	}

	@Test
	void quietHidesExactlyTheRoutineCategoriesOnScreenAndTheLogKeepsThem() throws Exception {
		enableActivityLog();
		lib("all.bsql", everyCategory("QUIET"));
		ScriptRunResult r = run("@all.bsql id=1;");
		Assertions.assertEquals(ScriptStatus.SUCCESS, r.getStatus(), output());
		String screen = output();
		for (String hidden : new String[] { "INSERT INTO customer VALUES (1, 'a', 'FR')", "1 row(s) created.", "who = 'Ann' (VARCHAR)", "-- id = 1",
				"all.bsql: SUCCESS" }) {
			Assertions.assertFalse(screen.contains(hidden), hidden + " must be hidden:\n" + screen);
		}
		for (String shown : new String[] { "RESULT_COLUMN", "rows fetched", "Hello Ann", "CUSTOMER", "|who " }) {
			Assertions.assertTrue(screen.contains(shown), shown + " must be shown:\n" + screen);
		}
		Assertions.assertTrue(screen.contains("Found 7 queries"), "printed before OUTPUT QUIET ran");
		String log = activityLog();
		for (String logged : new String[] { "INSERT INTO customer VALUES (1, 'a', 'FR')", "1 row(s) created.", "who = 'Ann' (VARCHAR)", "-- id = 1",
				"all.bsql: SUCCESS", "Hello Ann", "RESULT_COLUMN" }) {
			Assertions.assertTrue(log.contains(logged), logged + " must be in the activity log:\n" + log);
		}
	}

	@Test
	void quietNeverHidesWarningsErrorsNoticesOrANonSuccessStatus() throws Exception {
		autocommit(false);
		lib("noisy.bsql", "OUTPUT QUIET;\nON ERROR STOP;\nINSERT INTO customer VALUES (1, 'a', 'FR');\nINSERT INTO nope VALUES (1);\n");
		ScriptRunResult r = run("@noisy.bsql;");
		Assertions.assertEquals(ScriptStatus.FAILED, r.getStatus());
		String screen = output();
		Assertions.assertTrue(screen.contains("NOPE"), "SQL error: " + screen);
		Assertions.assertTrue(screen.contains("were rolled back"), "rollback warning: " + screen);
		Assertions.assertTrue(screen.contains("stopped by ON ERROR STOP"), "stop notice: " + screen);
		Assertions.assertTrue(screen.contains("noisy.bsql: FAILED"), "non-SUCCESS status: " + screen);
		lib("pending.bsql", "OUTPUT QUIET;\nINSERT INTO customer VALUES (2, 'b', 'FR');\n");
		clearOutput();
		run("@pending.bsql;");
		Assertions.assertTrue(output().contains("Uncommitted changes are pending"), "pending notice: " + output());
		Assertions.assertFalse(output().contains("pending.bsql: SUCCESS"), output());
	}

	@Test
	void quietKeepsMetadataWarnings() throws Exception {
		CommandTestSupport.shareVault(interpreter, com.upandcoding.broadsql.dao.TestDatabaseConnections.newFileBackedVault());
		lib("tagged.bsql", "-- @environment: SOMEWHERE_ELSE\nOUTPUT QUIET;\nSELECT 1;\n");
		interpreter.setPlatform(db.getPlatform().getId());
		run("@tagged.bsql;");
		Assertions.assertTrue(output().contains("is tagged for environment SOMEWHERE_ELSE"), output());
	}

	@Test
	void aChildStartsWithTheCallersModeAndItsChangeEndsWhenItReturns() throws Exception {
		lib("child.bsql", "INSERT INTO customer VALUES (1, 'child', 'FR');\nOUTPUT NORMAL;\nINSERT INTO customer VALUES (2, 'child normal', 'FR');\n");
		lib("parent.bsql", "OUTPUT QUIET;\n@child.bsql;\nINSERT INTO customer VALUES (3, 'parent after', 'FR');\n");
		run("@parent.bsql;");
		String screen = output();
		Assertions.assertFalse(screen.contains("(1, 'child', 'FR')"), "inherited QUIET: " + screen);
		Assertions.assertTrue(screen.contains("(2, 'child normal', 'FR')"), "the child's own NORMAL: " + screen);
		Assertions.assertFalse(screen.contains("(3, 'parent after', 'FR')"), "the parent is QUIET again: " + screen);
	}

	@Test
	void outputReturnsToNormalAfterFailureStopAndCancellation() throws Exception {
		lib("q1.bsql", "OUTPUT QUIET;\nINSERT INTO nope VALUES (1);\n");
		lib("q2.bsql", "ON ERROR STOP;\nOUTPUT QUIET;\nINSERT INTO nope VALUES (1);\n");
		lib("q3.bsql", "OUTPUT QUIET;\nCANCEL PROBE;\nSELECT 1;\n");
		interpreter.getCommands().put("CANCEL PROBE", new Command("CANCEL PROBE") {
			@Override
			public void execute(String query) {
				CommandCancellation.request();
			}
		});
		for (String script : new String[] { "q1.bsql", "q2.bsql", "q3.bsql" }) {
			run("@" + script + ";");
			Assertions.assertFalse(context().isQuiet(), script);
			clearOutput();
			line("INSERT INTO customer VALUES (" + script.charAt(1) + ", 'x', 'FR');");
			Assertions.assertTrue(output().contains("1 row(s) created."), script + ": the prompt shows routine output again: " + output());
		}
	}

	// ---- ECHO ----

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "ECHO 'abc'|abc", "ECHO 'It''s done'|It's done", "ECHO 'a;b'|a;b", "ECHO '--'|--",
			"ECHO '/* */'|/* */", "ECHO '$${x}'|${x}", "ECHO 'Use $${name} in SQL'|Use ${name} in SQL", "ECHO '${x}'|42", "ECHO 'cost: $5'|cost: $5",
			"ECHO '  indented   text  '|  indented   text  ", "echo 'lower'|lower", "ECHO 'value %1'|value %1", "ECHO '<@nowhere.txt>'|<@nowhere.txt>",
			"ECHO 'say \"hi\"'|say \"hi\"", "ECHO 'Region: ${r}, active: ${a}'|Region: NULL, active: true" })
	void echoForms(String statement, String printed) {
		line("LET x = 42; LET r = NULL; LET a = TRUE;");
		clearOutput();
		line(statement + ";");
		Assertions.assertTrue(output().contains(printed), statement + " -> " + output());
		Assertions.assertFalse(output().startsWith(console.getPrompt() + printed), "no prompt prefix");
	}

	@Test
	void anEmptyMessagePrintsAnEmptyLine() {
		line("ECHO '';");
		Assertions.assertEquals("\n", output().replace("\r", ""));
	}

	@Test
	void aMultiLineMessagePrintsSeveralLines() throws Exception {
		lib("ml.bsql", "ECHO 'Line 1\nLine 2';\n");
		run("@ml.bsql;");
		Assertions.assertTrue(output().contains("Line 1\nLine 2"), output());
	}

	@ParameterizedTest
	@ValueSource(strings = { "ECHO", "ECHO Hello", "ECHO 'a' 'b'", "ECHO 'a' x", "ECHO \"Hello\"", "ECHO 'Hi ${who}'", "ECHO 'Hi ${ bad }'" })
	void invalidEchoFailsAndPrintsNothing(String statement) {
		line(statement + "; LET after = 1;");
		Assertions.assertTrue(output().contains("ERROR"), output());
		Assertions.assertFalse(output().contains("Hello") && !output().contains("ERROR"), output());
		Assertions.assertNull(var("after"), "the line stopped at the failure");
	}

	@Test
	void anUnquotedEchoWithACommentSwallowingTheNextStatementIsDetected() throws Exception {
		lib("merged.bsql", "ECHO Step 1 -- loading;\nSELECT 1;\n");
		ScriptRunResult r = run("@merged.bsql;");
		Assertions.assertEquals(ScriptStatus.COMPLETED_WITH_ERRORS, r.getStatus(), output());
		Assertions.assertTrue(output().contains("ECHO takes one single-quoted message"), output());
	}

	@Test
	void anUnquotedApostropheFailsTheScriptInPreflight() throws Exception {
		lib("apos.bsql", "ECHO It's done;\nSELECT 1;\n");
		ScriptRunResult r = run("@apos.bsql;");
		Assertions.assertEquals(ScriptStatus.FAILED, r.getStatus());
		Assertions.assertEquals(0, r.getExecuted());
	}

	@Test
	void echoSanitizesControlCharactersFromValues() throws Exception {
		line("LET v = SELECT 'red' || CHAR(27) || '[31m' || CHAR(7) || 'x';");
		clearOutput();
		line("ECHO 'v=${v}';");
		Assertions.assertTrue(output().contains("v=red?[31m?x"), output());
		Assertions.assertFalse(output().contains("\u001b"), output());
	}

	@Test
	void echoIsLogged() throws Exception {
		enableActivityLog();
		line("ECHO 'logged message';");
		Assertions.assertTrue(activityLog().contains("logged message"), activityLog());
	}

	@Test
	void echoAtThePromptAndInAScriptBehaveTheSame() throws Exception {
		line("LET n = 3; ECHO 'n is ${n}';");
		String prompt = outputLines().get(outputLines().size() - 1);
		clearOutput();
		lib("e.bsql", "OUTPUT QUIET;\nECHO 'n is ${n}';\n");
		run("@e.bsql;");
		// QUIET applies from the OUTPUT statement on (spec 11.3): the lines printed before it ran remain
		Assertions.assertEquals(List.of("Found 2 queries", "OUTPUT QUIET", prompt), outputLines());
	}

	// ---- SHOW SCRIPT VARIABLES ----

	@Test
	void anEmptyNamespace() {
		line("SHOW SCRIPT VARIABLES;");
		Assertions.assertTrue(output().contains("No script variables are defined."), output());
	}

	@Test
	void theListingIsSortedTypedQuotedAndUsesTheLatestSpelling() {
		line("LET zeta = 1; LET Alpha = 'O''Brien'; LET mid = NULL; LET flag = FALSE; LET n_str = 'NULL'; LET d = SELECT DATE '2026-01-31';");
		line("LET t = SELECT CAST(NULL AS INTEGER); LET ALPHA = 'x''y';");
		clearOutput();
		line("show   script   variables;");
		List<String> rows = outputLines().stream().filter(l -> l.startsWith("|") && !l.startsWith("|-")).toList();
		Assertions.assertEquals(List.of("Name", "Type", "Value"), List.of(rows.get(0).split("\\|")).stream().skip(1).map(String::trim).toList(), output());
		List<String> names = rows.stream().skip(1).map(l -> l.split("\\|")[1].trim()).toList();
		Assertions.assertEquals(List.of("ALPHA", "d", "flag", "mid", "n_str", "t", "zeta"), names);
		String text = output();
		Assertions.assertTrue(text.contains("'x''y'"), text);
		Assertions.assertTrue(text.contains("'NULL'"), "the string NULL is quoted: " + text);
		Assertions.assertTrue(text.contains("|NULL"), "a NULL is not quoted: " + text);
		Assertions.assertTrue(text.contains("INTEGER"), "typed NULL shows its type: " + text);
		Assertions.assertTrue(text.contains("2026-01-31") && text.contains("DATE"), text);
		Assertions.assertTrue(text.contains("false") && text.contains("BOOLEAN"), text);
		for (String line : outputLines()) {
			if (line.startsWith("|")) {
				Assertions.assertTrue(line.endsWith("|"), "table output standard: " + line);
			}
		}
	}

	@Test
	void theListingSanitizesValues() {
		interpreter.getScriptVariables().assign("ctl", ScriptValue.ofString("a\u001bb"));
		line("SHOW SCRIPT VARIABLES;");
		Assertions.assertTrue(output().contains("'a?b'"), output());
	}

	@Test
	void extraTokensAreRefused() {
		line("SHOW SCRIPT VARIABLES x;");
		Assertions.assertTrue(output().contains("SHOW SCRIPT VARIABLES takes no argument"), output());
	}

	@Test
	void theListingIsShownUnderQuietAndNeverListsApiVariables() throws Exception {
		line("VAR X=api;");
		line("LET y = 1;");
		lib("l.bsql", "OUTPUT QUIET;\nSHOW SCRIPT VARIABLES;\n");
		clearOutput();
		run("@l.bsql;");
		Assertions.assertTrue(output().contains("|y "), output());
		Assertions.assertFalse(output().contains("api"), output());
	}
}
