package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.RunOutcome;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunContext;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunCoordinator;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;

/**
 * GitHub #207: Script text is data. A TAB in a Script ({@code @}, {@code LIB RUN}, the BroadSQL Editor's Run, all
 * through {@code ScriptExecutor}) is plain whitespace: these paths read the file and hand each statement to the
 * interpreter, never through the console's key handling, so no completion candidate can be inserted. The files
 * below contain real TAB characters, and each test checks the effect in the database, not only the absence of an
 * error.
 */
class TestScriptTabWhitespace extends ScriptingTestBase {

	/** The issue's script, with real TABs. */
	private static final String ISSUE_SCRIPT = "SELECT A,\n\tB,\n\tC\nFROM XYZ\nWHERE 1=1\n\tAND WHATEVER;\n";

	/** The same shape, with an effect that can be read back: only rows matching the TAB-indented condition are copied. */
	private static final String COPY_SCRIPT = "\tINSERT INTO ACTIVE_COPY (ID, NAME)\n"
			+ "SELECT ID,\n\tNAME\nFROM TEST_TABLE\nWHERE 1=1\n\tAND STATUS = 'ACTIVE'\n\t;\n"
			+ "SELECT\tID,\tNAME,\tSTATUS\tFROM\tTEST_TABLE\tWHERE\t1=1\tAND\tSTATUS\t=\t'ACTIVE';\n";

	@BeforeEach
	void createTables() {
		line("CREATE TABLE TEST_TABLE (ID INT PRIMARY KEY, NAME VARCHAR(20), STATUS VARCHAR(10));"
				+ "INSERT INTO TEST_TABLE VALUES (1, 'alpha', 'ACTIVE'), (2, 'beta', 'CLOSED'), (3, 'gamma', 'ACTIVE');"
				+ "CREATE TABLE ACTIVE_COPY (ID INT PRIMARY KEY, NAME VARCHAR(20));"
				+ "CREATE TABLE XYZ (A INT, B INT, C INT, WHATEVER BOOLEAN);"
				+ "INSERT INTO XYZ VALUES (10, 20, 30, TRUE), (11, 21, 31, FALSE);");
		clearOutput();
	}

	@ParameterizedTest
	@ValueSource(strings = { "@", "LIB RUN " })
	void tabIndentedSqlRunsAsWritten(String call) throws Exception {
		lib("copy.sql", COPY_SCRIPT);
		ScriptRunResult result = run(call + "copy.sql;");
		Assertions.assertEquals(ScriptStatus.SUCCESS, result.getStatus(), output());
		Assertions.assertEquals(2, result.getExecuted(), output());
		Assertions.assertEquals(2L, count("ACTIVE_COPY"), output());
		Assertions.assertEquals("alpha", sql("SELECT NAME FROM ACTIVE_COPY WHERE ID = 1"));
		Assertions.assertEquals("gamma", sql("SELECT NAME FROM ACTIVE_COPY WHERE ID = 3"));
		Assertions.assertTrue(output().contains("\tAND\tSTATUS\t=\t'ACTIVE'"), "the statement keeps its TABs: " + output());
	}

	@ParameterizedTest
	@ValueSource(strings = { "@", "LIB RUN " })
	void theIssueScriptRunsAndNothingIsInserted(String call) throws Exception {
		lib("issue.sql", ISSUE_SCRIPT);
		ScriptRunResult result = run(call + "issue.sql;");
		Assertions.assertEquals(ScriptStatus.SUCCESS, result.getStatus(), output());
		Assertions.assertTrue(output().contains("1=1 \tAND WHATEVER"), "the statement keeps its TABs (the echo shows line breaks as spaces): " + output());
		Assertions.assertTrue(output().contains("|10 "), "the matching row is shown: " + output());
		Assertions.assertFalse(output().contains("|11 "), "the row the condition excludes is not: " + output());
		Assertions.assertFalse(output().contains("ALL"), output());
	}

	@Test
	void atAndLibRunGiveTheSameResult() throws Exception {
		lib("issue.sql", ISSUE_SCRIPT);
		run("@issue.sql;");
		String viaAt = withoutRunDetails(output());
		clearOutput();
		run("LIB RUN issue.sql;");
		Assertions.assertEquals(viaAt, withoutRunDetails(output()));
	}

	/** The run identifier and the timing differ from one run to the next. */
	private static String withoutRunDetails(String output) {
		return output.replaceAll("\\[run [^\\]]+\\]", "").replaceAll("fetched in \\d+ ms", "");
	}

	/** A query keyword followed by a TAB or a line break is a query, as with a space; it used to be sent as an update and fail. */
	@ParameterizedTest
	@ValueSource(strings = { "SELECT\tID FROM TEST_TABLE", "SELECT\nID FROM TEST_TABLE", "select\r\nID FROM TEST_TABLE",
			"WITH\tX AS (SELECT 1 AS ID) SELECT ID FROM X", "SHOW\tTABLES", "SELECT ID FROM TEST_TABLE" })
	void aQueryKeywordFollowedByAnyWhitespaceIsAQuery(String query) {
		Assertions.assertTrue(CommandUtils.isNotUpdateStatement(query), query);
	}

	@ParameterizedTest
	@ValueSource(strings = { "SELECTX", "SELECT", "INSERT\tINTO T VALUES (1)", "UPDATE T SET A = 1", "selection\t1" })
	void otherStatementsAreStillUpdates(String query) {
		Assertions.assertFalse(CommandUtils.isNotUpdateStatement(query), query);
	}

	@Test
	void aTypedQueryWithTabsBetweenItsTokensShowsItsRows() {
		line("SELECT\tNAME\tFROM\tTEST_TABLE\tWHERE\tID\t=\t3;");
		Assertions.assertTrue(output().contains("gamma"), output());
		Assertions.assertFalse(output().contains("ERROR"), output());
	}

	@Test
	void theEditorRunExecutesTabIndentedSqlAsWritten() throws Exception {
		lib("copy.sql", COPY_SCRIPT);
		ScriptRunContext context = new ScriptRunContext(db, interpreter, null, null, settings, db.getPlatform().getId());
		RunOutcome outcome = new ScriptRunCoordinator().run("copy.sql", context, "");
		Assertions.assertEquals(ScriptStatus.SUCCESS, outcome.scriptStatus(), outcome.capturedOutput());
		Assertions.assertEquals(2L, count("ACTIVE_COPY"), outcome.capturedOutput());
	}
}
