package com.upandcoding.broadsql.controller.shell.commands;

import java.lang.reflect.Field;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.RunOutcome;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunContext;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunCoordinator;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;
import com.upandcoding.broadsql.dao.DatabaseConnection;

/**
 * SPRINT 0110A: CTRL+C cancels the whole top-level run (spec section 13): during a SQL statement (a real JDBC cancel
 * of a long H2 query), during a {@code LET} query, during a nested Script, between two statements, under STOP and
 * CONTINUE, in an Editor Run, and at the prompt; completed assignments remain, the cancelled {@code LET} leaves its
 * variable unchanged, and the transaction rules apply.
 */
class TestScriptCancellation extends ScriptingTestBase {

	private static final String LONG_QUERY = "SELECT COUNT(*) FROM SYSTEM_RANGE(1, 3000000000) A WHERE MOD(A.X, 7) = 3";

	/** CTRL+C during the statement: the same thing the signal handler does. */
	private void pressCtrlCWhenAStatementIsRunning() throws Exception {
		Field field = DatabaseConnection.class.getDeclaredField("currentStatement");
		field.setAccessible(true);
		long deadline = System.currentTimeMillis() + 20_000;
		while (System.currentTimeMillis() < deadline) {
			Statement running = (Statement) field.get(db);
			if (running != null) {
				Thread.sleep(200); // let the query actually start executing
				interpreter.handleInterrupt();
				return;
			}
			Thread.sleep(10);
		}
		Assertions.fail("the long statement never started: " + output());
	}

	/** Runs {@code callText} on a background thread (as the terminal does) and presses CTRL+C during its long statement. */
	private ScriptRunResult runAndCancel(String callText) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = new Thread(() -> {
			try {
				line(callText);
			} catch (Throwable t) {
				failure.set(t);
			}
		});
		runner.start();
		pressCtrlCWhenAStatementIsRunning();
		runner.join(60_000);
		Assertions.assertFalse(runner.isAlive(), "the run must end after CTRL+C");
		Assertions.assertNull(failure.get());
		return lastRun();
	}

	@Test
	void ctrlCDuringASqlStatementCancelsTheWholeRun() throws Exception {
		lib("long.bsql", "LET before = 1;\nINSERT INTO customer VALUES (1, 'before', 'FR');\n" + LONG_QUERY + ";\nINSERT INTO customer VALUES (2, 'after', 'FR');\nLET after = 1;\n");
		ScriptRunResult r = runAndCancel("@long.bsql;");
		Assertions.assertEquals(ScriptStatus.CANCELLED, r.getStatus(), output());
		Assertions.assertEquals(1L, count("CUSTOMER"), "no later statement ran");
		Assertions.assertEquals(1L, value("before"), "completed assignments remain");
		Assertions.assertNull(var("after"));
		Assertions.assertTrue(output().contains("Cancelled by user."), output());
		Assertions.assertTrue(output().contains("long.bsql: CANCELLED (3 statements, 0 failed) [run "), output());
	}

	@Test
	void ctrlCDuringALetQueryLeavesTheVariableUnchanged() throws Exception {
		lib("let.bsql", "LET x = SELECT 1;\nLET x = " + LONG_QUERY + ";\nLET y = 1;\n");
		line("LET x = 'before';");
		ScriptRunResult r = runAndCancel("@let.bsql;");
		Assertions.assertEquals(ScriptStatus.CANCELLED, r.getStatus(), output());
		Assertions.assertEquals(1L, value("x"), "the first assignment completed, the cancelled one changed nothing");
		Assertions.assertNull(var("y"));
	}

	@Test
	void ctrlCInANestedScriptEndsEveryLevel() throws Exception {
		lib("step1.bsql", "LET in_step1 = 1;\n" + LONG_QUERY + ";\nLET step1_end = 1;\n");
		lib("step2.bsql", "LET step2 = 1;\n");
		lib("top.bsql", "@step1.bsql;\n@step2.bsql;\nLET top_end = 1;\n");
		ScriptRunResult r = runAndCancel("@top.bsql;");
		Assertions.assertEquals(ScriptStatus.CANCELLED, r.getStatus(), output());
		Assertions.assertEquals(1L, value("in_step1"));
		Assertions.assertNull(var("step1_end"));
		Assertions.assertNull(var("step2"), "step2 never ran");
		Assertions.assertNull(var("top_end"));
		Assertions.assertEquals(1, occurrences(output(), "CANCELLED ("), "one status line, for the top-level run: " + output());
		Assertions.assertFalse(context().isInsideScript());
	}

	@ParameterizedTest
	@ValueSource(strings = { "CONTINUE", "STOP" })
	void cancellationIgnoresTheErrorPolicy(String policy) throws Exception {
		lib("p.bsql", "ON ERROR " + policy + ";\n" + LONG_QUERY + ";\nLET after = 1;\n");
		Assertions.assertEquals(ScriptStatus.CANCELLED, runAndCancel("@p.bsql;").getStatus(), output());
		Assertions.assertNull(var("after"));
	}

	@Test
	void ctrlCBetweenTwoStatementsCancelsBeforeTheNextOne() throws Exception {
		interpreter.getCommands().put("BETWEEN PROBE", new Command("BETWEEN PROBE") {
			@Override
			public void execute(String query) {
				// the state left by CTRL+C pressed after this statement finished: the run is cancelled, no statement is
				CommandCancellation.request();
				CommandCancellation.reset();
			}
		});
		lib("b.bsql", "LET first = 1;\nBETWEEN PROBE;\nLET second = 1;\n");
		ScriptRunResult r = run("@b.bsql;");
		Assertions.assertEquals(ScriptStatus.CANCELLED, r.getStatus(), output());
		Assertions.assertEquals(1L, value("first"));
		Assertions.assertNull(var("second"));
	}

	@Test
	void theHandlerCancelsTheRunWhenNoCommandThreadIsRunning() {
		context().setRunActive(true);
		try {
			interpreter.handleInterrupt();
			Assertions.assertTrue(CommandCancellation.isRunCancelled(), "between statements, CTRL+C is not idle");
		} finally {
			context().setRunActive(false);
			CommandCancellation.resetRun();
		}
		interpreter.handleInterrupt();
		Assertions.assertFalse(CommandCancellation.isRunCancelled(), "idle at the prompt: nothing happens");
	}

	@Test
	void ctrlCAtThePromptSkipsTheRestOfTheLine() throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = new Thread(() -> {
			try {
				line("LET a = 1; " + LONG_QUERY + "; LET b = 1;");
			} catch (Throwable t) {
				failure.set(t);
			}
		});
		runner.start();
		pressCtrlCWhenAStatementIsRunning();
		runner.join(60_000);
		Assertions.assertEquals(1L, value("a"));
		Assertions.assertNull(var("b"), "the remaining statements of the line are not executed");
	}

	@Test
	void aCancelledSqlStatementRollsBackPendingWorkWithTheWarning() throws Exception {
		autocommit(false);
		lib("tx.bsql", "INSERT INTO customer VALUES (1, 'pending', 'FR');\n" + LONG_QUERY + ";\n");
		ScriptRunResult r = runAndCancel("@tx.bsql;");
		Assertions.assertEquals(ScriptStatus.CANCELLED, r.getStatus());
		Assertions.assertEquals(0L, count("CUSTOMER"), "the database reported the cancel as an error: the existing rollback applied");
		Assertions.assertTrue(output().contains("were rolled back"), output());
	}

	@Test
	void cancellationBetweenStatementsRollsNothingBackAndShowsThePendingNotice() throws Exception {
		autocommit(false);
		interpreter.getCommands().put("BETWEEN PROBE", new Command("BETWEEN PROBE") {
			@Override
			public void execute(String query) {
				CommandCancellation.request();
				CommandCancellation.reset();
			}
		});
		lib("tx.bsql", "INSERT INTO customer VALUES (1, 'pending', 'FR');\nBETWEEN PROBE;\nCOMMIT;\n");
		Assertions.assertEquals(ScriptStatus.CANCELLED, run("@tx.bsql;").getStatus());
		Assertions.assertEquals(1L, count("CUSTOMER"), "still pending, not rolled back");
		Assertions.assertTrue(output().contains("Uncommitted changes are pending"), output());
	}

	@Test
	void anEditorRunIsCancelledLikeAnyRun() throws Exception {
		interpreter.getCommands().put("CANCEL PROBE", new Command("CANCEL PROBE") {
			@Override
			public void execute(String query) {
				CommandCancellation.request();
			}
		});
		lib("ed.bsql", "LET e1 = 1;\nCANCEL PROBE;\nLET e2 = 1;\n");
		ScriptRunContext context = new ScriptRunContext(db, interpreter, null, null, settings, db.getPlatform().getId());
		RunOutcome outcome = new ScriptRunCoordinator().run("ed.bsql", context, "");
		Assertions.assertEquals(ScriptStatus.CANCELLED, outcome.scriptStatus(), outcome.capturedOutput());
		Assertions.assertTrue(outcome.hasErrors());
		Assertions.assertTrue(outcome.capturedOutput().contains("Cancelled by user."), outcome.capturedOutput());
		Assertions.assertNull(var("e2"));
	}
}
