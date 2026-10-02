package com.upandcoding.broadsql.controller.shell.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.scripts.RunIds;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;

/**
 * SPRINT 0110A: the structured run result (spec section 14), {@code ON ERROR STOP|CONTINUE} for every failure
 * category of 12.2, preflight failures (12.5), status aggregation through nesting (14.3), the Run ID and its
 * diagnostic context (14.4), and the transaction rules (15), with autocommit on and off.
 */
class TestScriptStatusAndErrorPolicy extends ScriptingTestBase {

	// ---- statuses ----

	@Test
	void aScriptWhoseStatementsAllSucceedIsSuccessWithItsCounts() throws Exception {
		lib("ok.bsql", "INSERT INTO customer VALUES (1, 'a', 'FR');\nLET x = 1;\nSELECT * FROM customer;\n");
		ScriptRunResult r = run("@ok.bsql;");
		Assertions.assertEquals(ScriptStatus.SUCCESS, r.getStatus(), output());
		Assertions.assertEquals(3, r.getExecuted());
		Assertions.assertEquals(0, r.getFailed());
		Assertions.assertTrue(r.isTopLevel());
		Assertions.assertTrue(output().contains("ok.bsql: SUCCESS (3 statements, 0 failed) [run " + r.getRunId() + "]"), output());
		Assertions.assertFalse(lastStatementStatedFailed());
	}

	@Test
	void continueIsTheDefaultAndCompletesWithErrors() throws Exception {
		lib("load.bsql", "INSERT INTO customer VALUES (1, 'a', 'FR');\nINSERT INTO nope VALUES (1);\nINSERT INTO customer VALUES (2, 'b', 'FR');\nCOMMIT;\n");
		ScriptRunResult r = run("@load.bsql;");
		Assertions.assertEquals(ScriptStatus.COMPLETED_WITH_ERRORS, r.getStatus());
		Assertions.assertEquals(4, r.getExecuted());
		Assertions.assertEquals(1, r.getFailed());
		Assertions.assertEquals(2L, count("CUSTOMER"), "statements after the failure ran");
		Assertions.assertTrue(output().contains("load.bsql: COMPLETED_WITH_ERRORS (4 statements, 1 failed) [run "), output());
		Assertions.assertTrue(lastStatementStatedFailed(), "a non-SUCCESS run fails the typed statement (spec 18.5)");
	}

	@Test
	void anInteractiveLineStopsAfterAScriptCallThatIsNotSuccess() throws Exception {
		lib("partial.bsql", "INSERT INTO nope VALUES (1);\n");
		line("@partial.bsql; INSERT INTO customer VALUES (1, 'after', 'FR');");
		Assertions.assertEquals(0L, count("CUSTOMER"), output());
	}

	// ---- ON ERROR STOP / CONTINUE for every failure category (spec 12.2) ----

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "sql error|INSERT INTO nope VALUES (1)", "command error (thrown)|LIB SHOW no_such_script.bsql",
			"command error (printed)|VAR threshold = 100", "LET literal error|LET x = abc", "LET query error|LET x = SELECT id FROM customer WHERE 1 = 0",
			"undefined reference|SELECT ${undefined_variable}", "malformed reference|SELECT ${ bad }", "ECHO error|ECHO unquoted",
			"OUTPUT error at run time|OUTPUT QUIET extra_token_rejected_in_preflight", "nested FAILED|@missing_child.bsql",
			"DUMP of a failing script|DUMP LIB failing_child.bsql AS CSV", "raw ? with a reference|SELECT ? WHERE 1 = ${one}" })
	void stopEndsTheScriptAtTheFirstFailureOfEveryCategory(String category, String failing) throws Exception {
		lib("failing_child.bsql", "INSERT INTO nope VALUES (1);\nSELECT 1;\n");
		boolean preflight = failing.startsWith("OUTPUT");
		lib("stop.bsql", "ON ERROR STOP;\nLET one = 1;\nINSERT INTO customer VALUES (1, 'before', 'FR');\n" + failing + ";\nINSERT INTO customer VALUES (2, 'after', 'FR');\nCOMMIT;\n");
		ScriptRunResult r = run("@stop.bsql;");
		Assertions.assertEquals(ScriptStatus.FAILED, r.getStatus(), category + ": " + output());
		if (preflight) {
			Assertions.assertEquals(0, r.getExecuted(), "an invalid OUTPUT statement fails the whole Script before anything runs");
			Assertions.assertEquals(0L, count("CUSTOMER"));
			return;
		}
		Assertions.assertEquals(4, r.getStopStatement(), category);
		Assertions.assertEquals(4, r.getStopLine(), category);
		Assertions.assertTrue(output().contains("Script stop.bsql stopped by ON ERROR STOP at statement 4 (line 4)"), output());
		Assertions.assertTrue(output().contains(", stopped at statement 4 (line 4)"), output());
		Assertions.assertEquals(0L, ((Number) sql("SELECT COUNT(*) FROM CUSTOMER WHERE ID = 2")).longValue(), category + ": nothing after the failure runs");
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "sql error|INSERT INTO nope VALUES (1)", "command error (thrown)|LIB SHOW no_such_script.bsql",
			"command error (printed)|VAR threshold = 100", "LET literal error|LET x = abc", "LET query error|LET x = SELECT id FROM customer WHERE 1 = 0",
			"undefined reference|SELECT ${undefined_variable}", "malformed reference|SELECT ${ bad }", "ECHO error|ECHO unquoted",
			"nested FAILED|@missing_child.bsql", "DUMP of a failing script|DUMP LIB failing_child.bsql AS CSV" })
	void continueCountsTheFailureAndRunsTheNextStatement(String category, String failing) throws Exception {
		lib("failing_child.bsql", "INSERT INTO nope VALUES (1);\nSELECT 1;\n");
		lib("cont.bsql", "ON ERROR CONTINUE;\n" + failing + ";\nINSERT INTO customer VALUES (2, 'after', 'FR');\n");
		ScriptRunResult r = run("@cont.bsql;");
		Assertions.assertEquals(ScriptStatus.COMPLETED_WITH_ERRORS, r.getStatus(), category + ": " + output());
		Assertions.assertTrue(r.getFailed() >= 1, category);
		Assertions.assertEquals(1L, count("CUSTOMER"), category + ": the next statement ran");
	}

	@Test
	void stopBeforeACommitNeverCommits() throws Exception {
		autocommit(false);
		lib("cleanup.bsql", "ON ERROR STOP;\nDELETE FROM customer WHERE id < 0;\nINSERT INTO customer VALUES (1, 'pending', 'FR');\nUPDATE audit_archiv SET x = 1;\nCOMMIT;\n");
		ScriptRunResult r = run("@cleanup.bsql;");
		Assertions.assertEquals(ScriptStatus.FAILED, r.getStatus());
		Assertions.assertEquals(0L, ((Number) committed("SELECT COUNT(*) FROM CUSTOMER")).longValue(), "COMMIT did not run");
	}

	@Test
	void warningsAndEmptyResultsAreNotFailures() throws Exception {
		lib("w.bsql", "SELECT * FROM customer WHERE 1 = 0;\nLET n = SELECT COUNT(*) FROM customer;\n");
		Assertions.assertEquals(ScriptStatus.SUCCESS, run("@w.bsql;").getStatus(), output());
	}

	// ---- preflight (spec 12.5) ----

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "ON ERROR RESUME;|Invalid ON ERROR statement", "ON ERROR;|Invalid ON ERROR statement",
			"ON ERROR STOP NOW;|Invalid ON ERROR statement", "OUTPUT SILENT;|Invalid OUTPUT statement", "OUTPUT;|Invalid OUTPUT statement",
			"-- @params: 1st, env<NL>SELECT 1;|declares an invalid parameter", "SELECT 'oops;|Unterminated single quote", "/* open<NL>SELECT 1;|Unterminated block comment" })
	void preflightFailuresRunNothingInEveryPolicy(String body, String expected) throws Exception {
		lib("pre.bsql", "INSERT INTO customer VALUES (1, 'x', 'FR');\n" + body.replace("<NL>", "\n") + "\n");
		ScriptRunResult r = run("@pre.bsql;");
		Assertions.assertEquals(ScriptStatus.FAILED, r.getStatus(), output());
		Assertions.assertEquals(0, r.getExecuted());
		Assertions.assertTrue(output().contains(expected), output());
		Assertions.assertTrue(output().contains("pre.bsql: FAILED (0 statements, 0 failed) [run "), output());
		Assertions.assertEquals(0L, count("CUSTOMER"), "nothing executed");
	}

	@Test
	void anInvalidDirectiveIsReportedWithItsLocation() throws Exception {
		lib("loc.bsql", "SELECT 1;\n\nOUTPUT SILENT;\n");
		run("@loc.bsql;");
		Assertions.assertTrue(output().contains("statement 2, line 3 of loc.bsql"), output());
	}

	@ParameterizedTest
	@ValueSource(strings = { "ON ERROR STOP", "ON ERROR CONTINUE", "OUTPUT QUIET", "OUTPUT NORMAL" })
	void controlsAreRefusedAtThePrompt(String statement) throws Exception {
		line("LET a = 1; " + statement + "; LET b = 2;");
		Assertions.assertTrue(output().contains("applies inside Scripts only"), output());
		Assertions.assertEquals(1L, value("a"));
		Assertions.assertNull(var("b"), "the line stops at the refused statement");
	}

	@Test
	void onErrorsWithAnSIsNotADirectiveAndFailsAsSql() throws Exception {
		lib("s.bsql", "ON ERRORS STOP;\nSELECT 1;\n");
		ScriptRunResult r = run("@s.bsql;");
		Assertions.assertEquals(ScriptStatus.COMPLETED_WITH_ERRORS, r.getStatus(), output());
	}

	// ---- inheritance and restoration ----

	@Test
	void aChildInheritsStopAndItsChangesEndWhenItReturns() throws Exception {
		lib("child.bsql", "ON ERROR CONTINUE;\nINSERT INTO nope VALUES (1);\nINSERT INTO customer VALUES (1, 'child', 'FR');\n");
		lib("inherits.bsql", "INSERT INTO nope VALUES (1);\nINSERT INTO customer VALUES (2, 'after child failure', 'FR');\n");
		lib("parent.bsql", "ON ERROR STOP;\n@child.bsql;\n@inherits.bsql;\nINSERT INTO customer VALUES (3, 'never', 'FR');\n");
		ScriptRunResult r = run("@parent.bsql;");
		Assertions.assertEquals(ScriptStatus.FAILED, r.getStatus(), output());
		Assertions.assertEquals(1L, count("CUSTOMER"), "child's CONTINUE let it go on; inherits.bsql started under STOP and stopped: " + output());
		Assertions.assertTrue(output().contains("Script inherits.bsql stopped by ON ERROR STOP at statement 1 (line 1)"), output());
	}

	@Test
	void policiesNeverLeakToThePromptAfterAnyEnding() throws Exception {
		lib("stops.bsql", "ON ERROR STOP;\nOUTPUT QUIET;\nINSERT INTO nope VALUES (1);\n");
		run("@stops.bsql;");
		Assertions.assertFalse(context().isInsideScript());
		Assertions.assertFalse(context().isQuiet());
		lib("next.bsql", "INSERT INTO nope VALUES (1);\nINSERT INTO customer VALUES (1, 'x', 'FR');\n");
		Assertions.assertEquals(ScriptStatus.COMPLETED_WITH_ERRORS, run("@next.bsql;").getStatus(), "a new top-level run starts with CONTINUE");
	}

	// ---- aggregation through nesting (spec 14.3) ----

	@Test
	void aSuccessfulChildLeavesTheParentSuccessfulAndCountsSum() throws Exception {
		lib("c.bsql", "SELECT 1;\nSELECT 2;\n");
		lib("p.bsql", "@c.bsql;\nSELECT 3;\n");
		ScriptRunResult r = run("@p.bsql;");
		Assertions.assertEquals(ScriptStatus.SUCCESS, r.getStatus());
		Assertions.assertEquals(4, r.getExecuted(), "2 parent statements + 2 child statements");
	}

	@Test
	void aChildCompletedWithErrorsDoesNotFailTheCallingStatementEvenUnderStop() throws Exception {
		lib("c.bsql", "ON ERROR CONTINUE;\nINSERT INTO nope VALUES (1);\nSELECT 1;\n");
		lib("p.bsql", "ON ERROR STOP;\n@c.bsql;\nINSERT INTO customer VALUES (1, 'after', 'FR');\n");
		ScriptRunResult r = run("@p.bsql;");
		Assertions.assertEquals(ScriptStatus.COMPLETED_WITH_ERRORS, r.getStatus(), output());
		Assertions.assertEquals(1, r.getFailed(), "the child's failure is counted, the calling statement succeeded");
		Assertions.assertEquals(6, r.getExecuted());
		Assertions.assertEquals(1L, count("CUSTOMER"));
	}

	@Test
	void aFailedChildUnderParentContinueIsCountedOnceInTheCallerPlusItsOwnFailures() throws Exception {
		lib("c.bsql", "ON ERROR STOP;\nINSERT INTO nope VALUES (1);\nSELECT 1;\n");
		lib("p.bsql", "@c.bsql;\nINSERT INTO customer VALUES (1, 'after', 'FR');\n");
		ScriptRunResult r = run("@p.bsql;");
		Assertions.assertEquals(ScriptStatus.COMPLETED_WITH_ERRORS, r.getStatus());
		Assertions.assertEquals(2, r.getFailed(), "child's own failure + the failed calling statement");
		Assertions.assertEquals(4, r.getExecuted());
		Assertions.assertEquals(1L, count("CUSTOMER"));
	}

	@Test
	void aFailedChildUnderParentStopFailsTheParent() throws Exception {
		lib("c.bsql", "ON ERROR STOP;\nINSERT INTO nope VALUES (1);\n");
		lib("p.bsql", "ON ERROR STOP;\n@c.bsql;\nINSERT INTO customer VALUES (1, 'never', 'FR');\n");
		ScriptRunResult r = run("@p.bsql;");
		Assertions.assertEquals(ScriptStatus.FAILED, r.getStatus());
		Assertions.assertEquals(2, r.getStopStatement());
		Assertions.assertEquals(0L, count("CUSTOMER"));
	}

	@Test
	void threeLevelsWithMixedPoliciesShareOneRunId() throws Exception {
		List<String> ids = new ArrayList<>();
		AtomicInteger n = new AtomicInteger();
		RunIds.setGeneratorForTests(() -> {
			String id = "RUN" + String.format("%05d", n.incrementAndGet());
			ids.add(id);
			return id;
		});
		lib("grandchild.bsql", "INSERT INTO nope VALUES (1);\nSELECT 1;\n");
		lib("child.bsql", "ON ERROR STOP;\n@grandchild.bsql;\nINSERT INTO nope VALUES (2);\nSELECT 'never';\n");
		lib("top.bsql", "@child.bsql;\nINSERT INTO customer VALUES (1, 'top continues', 'FR');\n");
		ScriptRunResult r = run("@top.bsql;");
		Assertions.assertEquals(List.of("RUN00001"), ids, "one Run ID per top-level run, shared by nested runs");
		Assertions.assertEquals("RUN00001", r.getRunId());
		Assertions.assertEquals(ScriptStatus.COMPLETED_WITH_ERRORS, r.getStatus(), output());
		// grandchild inherits the child's STOP: 1 executed, 1 failed, FAILED; child: ON ERROR + the failed call (counted once,
		// plus the grandchild's failure), STOP: 3 executed, 2 failed, FAILED; top (CONTINUE): the failed call + 1 INSERT
		Assertions.assertEquals(5, r.getExecuted(), output());
		Assertions.assertEquals(3, r.getFailed(), output());
		Assertions.assertEquals(1L, count("CUSTOMER"), "top continued");
		Assertions.assertFalse(output().contains("never"), output());
		Assertions.assertEquals(1, occurrences(output(), "[run RUN00001]"), "nested runs print no status line of their own");
		run("@top.bsql;");
		Assertions.assertEquals(List.of("RUN00001", "RUN00002"), ids, "the next top-level run has a new ID");
	}

	@Test
	void theRunIdIsInTheDiagnosticContextDuringTheRunOnlyAndThereIsNoStartLine() throws Exception {
		RunIds.setGeneratorForTests(() -> "MDCTEST1");
		List<String> seen = new ArrayList<>();
		Command probe = new Command("MDC PROBE") {
			@Override
			public void execute(String query) {
				seen.add(MDC.get(RunIds.MDC_KEY));
			}
		};
		interpreter.getCommands().put("MDC PROBE", probe);
		lib("mdc.bsql", "MDC PROBE;\n@inner.bsql;\n");
		lib("inner.bsql", "MDC PROBE;\n");
		Assertions.assertNull(MDC.get(RunIds.MDC_KEY));
		run("@mdc.bsql;");
		Assertions.assertEquals(List.of("MDCTEST1", "MDCTEST1"), seen, "set on the statement worker threads, nested runs included");
		Assertions.assertNull(MDC.get(RunIds.MDC_KEY), "removed when the run ends");
		Assertions.assertEquals(1, occurrences(output(), "MDCTEST1"), "only the final status line shows it: " + output());
		Assertions.assertFalse(output().toLowerCase().contains("starting"), output());
	}

	// ---- transactions (spec 15), autocommit on and off ----

	@Test
	void autocommitOnKeepsEarlierWorkAfterAnError() throws Exception {
		autocommit(true);
		lib("e1.bsql", "INSERT INTO customer VALUES (1, 'a', 'FR');\nINSERT INTO nope VALUES (1);\nINSERT INTO customer VALUES (2, 'b', 'FR');\n");
		run("@e1.bsql;");
		Assertions.assertEquals(2L, ((Number) committed("SELECT COUNT(*) FROM CUSTOMER")).longValue());
		Assertions.assertFalse(output().contains("were rolled back"), "nothing was pending: " + output());
	}

	@Test
	void autocommitOffRollsBackPendingWorkAndSaysSoThenContinuesInANewTransaction() throws Exception {
		autocommit(false);
		lib("e1.bsql", "INSERT INTO customer VALUES (1, 'a', 'FR');\nINSERT INTO nope VALUES (1);\nINSERT INTO customer VALUES (2, 'b', 'FR');\nCOMMIT;\n");
		ScriptRunResult r = run("@e1.bsql;");
		Assertions.assertEquals(ScriptStatus.COMPLETED_WITH_ERRORS, r.getStatus());
		Assertions.assertEquals(List.of(2), committedIds(), "row 1 was rolled back with the error, row 2 committed");
		Assertions.assertEquals(1, occurrences(output(), "WARNING: Pending changes since the last COMMIT on " + db.getPlatform().getId() + " were rolled back."), output());
		int error = output().indexOf("NOPE");
		int warning = output().indexOf("were rolled back");
		Assertions.assertTrue(error >= 0 && warning > error, "the warning follows the error: " + output());
	}

	@Test
	void noRollbackWarningWithoutPendingWork() throws Exception {
		autocommit(false);
		line("INSERT INTO nope VALUES (1);");
		Assertions.assertFalse(output().contains("were rolled back"), output());
		line("INSERT INTO customer VALUES (1, 'a', 'FR'); COMMIT;");
		clearOutput();
		line("INSERT INTO nope VALUES (1);");
		Assertions.assertFalse(output().contains("were rolled back"), "committed work is not pending: " + output());
	}

	@Test
	void theRollbackWarningAlsoAppliesAtThePromptAndToALetQuery() throws Exception {
		autocommit(false);
		line("INSERT INTO customer VALUES (1, 'a', 'FR');");
		line("LET n = SELECT COUNT(*) FROM pricee;");
		Assertions.assertTrue(output().contains("were rolled back"), output());
		Assertions.assertEquals(0L, count("CUSTOMER"));
	}

	@Test
	void aCommandErrorAndStopNeverRollBack() throws Exception {
		autocommit(false);
		lib("cmd.bsql", "ON ERROR STOP;\nINSERT INTO customer VALUES (1, 'pending', 'FR');\nLET x = abc;\n");
		run("@cmd.bsql;");
		Assertions.assertEquals(1L, count("CUSTOMER"), "still pending");
		Assertions.assertTrue(db.isHasUncommitted());
		Assertions.assertFalse(output().contains("were rolled back"), output());
		Assertions.assertTrue(output().contains("WARNING: Uncommitted changes are pending on " + db.getPlatform().getId()), "pending-changes notice: " + output());
	}

	@Test
	void thePendingChangesNoticeFollowsTheStatusLineOnlyWhenWorkIsPending() throws Exception {
		autocommit(false);
		lib("p.bsql", "INSERT INTO customer VALUES (1, 'a', 'FR');\n");
		run("@p.bsql;");
		int status = output().indexOf("p.bsql: SUCCESS");
		int notice = output().indexOf("Uncommitted changes are pending");
		Assertions.assertTrue(status >= 0 && notice > status, output());
		clearOutput();
		lib("c.bsql", "COMMIT;\n");
		run("@c.bsql;");
		Assertions.assertFalse(output().contains("Uncommitted changes are pending"), output());
		clearOutput();
		autocommit(true);
		lib("p2.bsql", "INSERT INTO customer VALUES (2, 'b', 'FR');\n");
		run("@p2.bsql;");
		Assertions.assertFalse(output().contains("Uncommitted changes are pending"), "autocommit on: never pending " + output());
	}

	@Test
	void explicitCommitAndRollbackRemainAuthoritative() throws Exception {
		autocommit(false);
		lib("t.bsql", "INSERT INTO customer VALUES (1, 'a', 'FR');\nROLLBACK;\nINSERT INTO customer VALUES (2, 'b', 'FR');\nCOMMIT;\n");
		run("@t.bsql;");
		Assertions.assertEquals(List.of(2), committedIds());
	}

	private List<Integer> committedIds() throws Exception {
		List<Integer> ids = new ArrayList<>();
		try (java.sql.Connection other = java.sql.DriverManager.getConnection(db.getPlatform().getUrl());
				java.sql.ResultSet rs = other.createStatement().executeQuery("SELECT ID FROM CUSTOMER ORDER BY ID")) {
			while (rs.next()) {
				ids.add(rs.getInt(1));
			}
		}
		return ids;
	}

	@Test
	void theStructuredResultIsReturnedNotInferredFromText() throws BroadSQLException, Exception {
		lib("quiet_fail.bsql", "OUTPUT QUIET;\nINSERT INTO nope VALUES (1);\n");
		ScriptRunResult r = run("@quiet_fail.bsql;");
		Assertions.assertEquals(ScriptStatus.COMPLETED_WITH_ERRORS, r.getStatus());
		Assertions.assertEquals(1, r.getFailed());
	}
}
