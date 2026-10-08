package com.upandcoding.broadsql.controller.shell.commands;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.upandcoding.broadsql.controller.shell.reader.JLineConsoleLineReader;
import com.upandcoding.broadsql.dao.DatabaseConnection;

/**
 * Generic CTRL+C cancellation of a running database request, outside REPEAT: CTRL+C typed as a key on a real JLine
 * key-binding terminal (after a first prompt read, as in a real session) while a JDBC statement executes. Each
 * scenario checks both sides: BroadSQL called {@code Statement.cancel()} on the running statement
 * ({@link RecordingJdbc}), and the H2 session no longer executes it ({@code INFORMATION_SCHEMA.SESSIONS}, read on a
 * second connection); then the prompt returns, BroadSQL did not take its shutdown path, and the same connection runs
 * the next statement. Nothing calls {@code handleInterrupt()} directly.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TestCtrlCCancelsRunningStatement extends ScriptingTestBase {

	private static final String LONG_QUERY = "SELECT COUNT(*) FROM SYSTEM_RANGE(1, 3000000000) A WHERE MOD(A.X, 7) = 3";
	private static final String LONG_QUERY_MARK = "3000000000";
	private static final int CTRL_C = 3;

	private PipedOutputStream keyboard;
	private JLineConsoleLineReader reader;
	private RecordingJdbc jdbc;

	@BeforeEach
	void setUpTerminal() throws Exception {
		keyboard = new PipedOutputStream();
		PipedInputStream in = new PipedInputStream(keyboard, 4096);
		reader = JLineConsoleLineReader.createForTestingWithRealKeyBindings(in, new ByteArrayOutputStream(), null, null, interpreter.getCommands(), db,
				"windows-vtp");
		console.setLineReader(reader);
		interpreter.attachTerminalInterruptHandler();
		RepeatProbe.reset();
		try (Statement statement = db.getDirectConnection().createStatement()) {
			statement.execute("CREATE ALIAS REC FOR '" + RepeatProbe.class.getName() + ".rec'");
		}
		jdbc = new RecordingJdbc();
		db.setDirectConnection(jdbc.wrap(db.getDirectConnection()));
		// a first command read and run at the prompt: JLine has restored its INT handler once
		prompt("SELECT REC('first') AS R;");
		Assertions.assertEquals(List.of("first"), RepeatProbe.calls());
		RepeatProbe.reset();
	}

	@AfterEach
	void tearDownTerminal() {
		reader.close();
	}

	// ---- the terminal ----

	private String readPromptLine(String line) throws IOException {
		keyboard.write((line + "\r").getBytes(StandardCharsets.UTF_8));
		keyboard.flush();
		String read = console.readCommandLine(false).trim();
		Assertions.assertTrue(read.endsWith(";") || read.equals("/"), read);
		return read;
	}

	/** Types {@code line} and Enter at the prompt, reads it as the input loop does, and executes it. */
	private void prompt(String line) throws IOException {
		interpreter.executeMultiStatementLine(StringUtils.substringBeforeLast(readPromptLine(line), ";"));
	}

	private Thread inBackground(String name, ThrowingRunnable body, AtomicReference<Throwable> failure) {
		Thread runner = new Thread(() -> {
			try {
				body.run();
			} catch (Throwable t) {
				failure.set(t);
			}
		}, name);
		runner.start();
		return runner;
	}

	private Thread promptInBackground(String line, AtomicReference<Throwable> failure) {
		return inBackground("ctrl-c-runner", () -> prompt(line), failure);
	}

	private void typeCtrlC() throws IOException {
		keyboard.write(CTRL_C);
		keyboard.flush();
	}

	interface ThrowingRunnable {
		void run() throws Exception;
	}

	// ---- the database side ----

	/** Statements other sessions of the test database are executing that contain {@code mark} (read on a second connection). */
	private long executingElsewhere(String mark) throws Exception {
		try (Connection observer = DriverManager.getConnection(db.getPlatform().getUrl());
				Statement statement = observer.createStatement();
				ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID <> SESSION_ID() "
						+ "AND EXECUTING_STATEMENT LIKE '%" + mark + "%'")) {
			rs.next();
			return rs.getLong(1);
		}
	}

	/** Waits until the database is executing the long query: CTRL+C then arrives during execution, not before it. */
	private void waitUntilTheDatabaseExecutesTheLongQuery() throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (executingElsewhere(LONG_QUERY_MARK) == 0) {
			Assertions.assertTrue(System.currentTimeMillis() < deadline, "the long query never started: " + output());
			Thread.sleep(20);
		}
		Thread.sleep(300);
		Assertions.assertEquals(1, executingElsewhere(LONG_QUERY_MARK), "still executing when CTRL+C is pressed");
	}

	/** CTRL+C during the long query: cancelled through JDBC on both sides, the prompt back, the session usable. */
	private void ctrlCCancelsTheLongQuery(Thread runner, AtomicReference<Throwable> failure) throws Exception {
		waitUntilTheDatabaseExecutesTheLongQuery();
		long pressed = System.currentTimeMillis();
		typeCtrlC();
		runner.join(30_000);
		Assertions.assertFalse(runner.isAlive(), "CTRL+C must end the command: " + output());
		Assertions.assertNull(failure.get());
		Assertions.assertTrue(System.currentTimeMillis() - pressed < 10_000, "cancelled, not waited out");
		Assertions.assertTrue(jdbc.cancels.get() >= 1, "BroadSQL called Statement.cancel() on the running statement");
		Assertions.assertEquals(0, executingElsewhere(LONG_QUERY_MARK), "the database no longer executes the query");
		Assertions.assertTrue(output().contains("57014"), "the database reported the cancellation (H2 57014): " + output());
		assertUsable();
	}

	/** BroadSQL did not take its shutdown path, and the next statement typed at the prompt runs on the same connection. */
	private void assertUsable() throws Exception {
		Assertions.assertTrue(db.isConnected(), "the connection is still open: no shutdown path ran");
		Assertions.assertFalse(output().contains("Disconnected"), output());
		RepeatProbe.reset();
		prompt("SELECT REC('usable') AS R;");
		Assertions.assertEquals(List.of("usable"), RepeatProbe.calls(), "the next statement typed at the prompt runs: " + output());
	}

	// ---- B: during database execution ----

	@Test
	void aLongSelectAtThePromptIsCancelledThroughJdbc() throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground(LONG_QUERY + ";", failure);
		ctrlCCancelsTheLongQuery(runner, failure);
	}

	@Test
	void theLastQueryRerunWithSlashIsCancelledThroughJdbc() throws Exception {
		interpreter.lastSQLQuery = LONG_QUERY;
		AtomicReference<Throwable> failure = new AtomicReference<>();
		// what the input loop does with a line holding "/" alone
		Thread runner = inBackground("slash-runner", () -> interpreter.handleSlashRerun(readPromptLine("/")), failure);
		ctrlCCancelsTheLongQuery(runner, failure);
	}

	@Test
	void aLongQueryInAnAtScriptIsCancelledAndTheScriptStops() throws Exception {
		lib("long.sql", "SELECT REC('before') AS R;\n" + LONG_QUERY + ";\nSELECT REC('after') AS R;\n");
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground("@long.sql;", failure);
		ctrlCCancelsTheLongQuery(runner, failure);
		Assertions.assertTrue(output().contains("long.sql: CANCELLED ("), "the Script run is CANCELLED: " + output());
		Assertions.assertFalse(jdbc.executed.stream().anyMatch(sql -> sql.contains("'after'")), "the rest of the Script is not sent: " + jdbc.executed);
	}

	@Test
	void aLongQueryInALibRunScriptIsCancelledAndTheScriptStops() throws Exception {
		lib("long.sql", "SELECT REC('before') AS R;\n" + LONG_QUERY + ";\nSELECT REC('after') AS R;\n");
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground("LIB RUN long.sql;", failure);
		ctrlCCancelsTheLongQuery(runner, failure);
		Assertions.assertTrue(output().contains("long.sql: CANCELLED ("), "the Script run is CANCELLED: " + output());
		Assertions.assertFalse(jdbc.executed.stream().anyMatch(sql -> sql.contains("'after'")), "the rest of the Script is not sent: " + jdbc.executed);
	}

	@Test
	void aLongQueryRepeatedByRepeatIsCancelledDuringExecution() throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground("REPEAT BEGIN " + LONG_QUERY + "; END EVERY 10s;", failure);
		ctrlCCancelsTheLongQuery(runner, failure);
		Assertions.assertNull(interpreter.getRepeatGuard(), "REPEAT has stopped");
		Assertions.assertTrue(output().contains("REPEAT cancelled by user after 0 complete iterations."), output());
	}

	// ---- A: before execution ----

	@Test
	void ctrlCWhileTheStatementIsBeingPreparedSendsNothingAndKeepsPendingWork() throws Exception {
		autocommit(false);
		prompt("INSERT INTO CUSTOMER VALUES (1, 'pending', 'FR');");
		CountDownLatch release = jdbc.holdNextStatementCreation();
		jdbc.executed.clear();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground("SELECT REC('never') AS R;", failure);
		Assertions.assertTrue(jdbc.creationHeld.await(20, TimeUnit.SECONDS), "BroadSQL never started preparing the statement");
		typeCtrlC();
		long deadline = System.currentTimeMillis() + 10_000;
		while (!CommandCancellation.isRequested()) {
			Assertions.assertTrue(System.currentTimeMillis() < deadline, "the CTRL+C key never reached BroadSQL's handler");
			Thread.sleep(10);
		}
		release.countDown();
		runner.join(30_000);
		Assertions.assertFalse(runner.isAlive(), output());
		Assertions.assertNull(failure.get());
		Assertions.assertTrue(jdbc.executed.isEmpty(), "nothing was sent to the database: " + jdbc.executed);
		Assertions.assertTrue(RepeatProbe.calls().isEmpty(), "the query never ran");
		Assertions.assertTrue(output().contains(DatabaseConnection.CANCELLED_BEFORE_SENT), output());
		Assertions.assertFalse(output().contains("were rolled back"), "no database error, no rollback: " + output());
		Assertions.assertEquals(1L, count("CUSTOMER"), "the pending INSERT is kept");
		assertUsable();
		db.rollback();
	}

	// ---- C/D: while rows are read and displayed ----

	/** Table lines displayed so far (rows, header and borders). */
	private int displayedRows() {
		return occurrences(output(), "\n|");
	}

	@Test
	void ctrlCWhileRowsAreReadStopsTheDisplayAndKeepsPendingWork() throws Exception {
		autocommit(false);
		prompt("INSERT INTO CUSTOMER VALUES (1, 'pending', 'FR');");
		db.setMaxRowsOnScreen(0);
		jdbc.slowFetch(5);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground("SELECT X FROM SYSTEM_RANGE(1, 100000);", failure);
		long deadline = System.currentTimeMillis() + 20_000;
		while (displayedRows() < 60) {
			Assertions.assertTrue(System.currentTimeMillis() < deadline, "rows never appeared: " + output());
			Thread.sleep(10);
		}
		typeCtrlC();
		runner.join(30_000);
		jdbc.slowFetch(0);
		Assertions.assertFalse(runner.isAlive(), output());
		Assertions.assertNull(failure.get());
		Assertions.assertTrue(output().contains("Command interrupted"), output());
		Assertions.assertTrue(displayedRows() < 10_000, "the display stopped long before the 100000 rows");
		Assertions.assertFalse(output().contains("were rolled back"), "no database error, no rollback: " + output());
		Assertions.assertEquals(1L, count("CUSTOMER"), "the pending INSERT is kept");
		assertUsable();
		db.rollback();
	}

	// ---- races ----

	@Test
	void ctrlCBetweenTwoScriptStatementsIsNotLostWhenTheNextOneStarts() throws Exception {
		// a Script run is active and between two statements: its executor has already checked for CTRL+C, the next
		// statement is about to start when the key arrives
		context().setRunActive(true);
		try {
			typeCtrlC();
			long deadline = System.currentTimeMillis() + 10_000;
			while (!CommandCancellation.isRunCancelled()) {
				Assertions.assertTrue(System.currentTimeMillis() < deadline, "the CTRL+C key never reached BroadSQL's handler");
				Thread.sleep(10);
			}
			jdbc.executed.clear();
			interpreter.setQuery("SELECT REC('next statement') AS R");
			interpreter.executeCommand();
		} finally {
			context().setRunActive(false);
		}
		Assertions.assertTrue(jdbc.executed.isEmpty(), "the statement that was starting is not sent: " + jdbc.executed);
		Assertions.assertTrue(RepeatProbe.calls().isEmpty());
		Assertions.assertTrue(CommandCancellation.isRequested(), "the statement is reported cancelled to the executor");
		CommandCancellation.resetRun();
		assertUsable();
	}

	@Test
	void cancellingOneStatementDoesNotCancelTheNextUnrelatedQuery() throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground(LONG_QUERY + ";", failure);
		ctrlCCancelsTheLongQuery(runner, failure);
		int cancelsSoFar = jdbc.cancels.get();
		clearOutput();
		// a query that takes a while on its own: it must run to its result, not inherit the earlier CTRL+C
		prompt("SELECT COUNT(*) AS N FROM SYSTEM_RANGE(1, 20000000) A WHERE MOD(A.X, 7) = 3;");
		Assertions.assertTrue(output().contains("2857143"), "the next query completed with its result: " + output());
		Assertions.assertFalse(output().contains("57014"), output());
		Assertions.assertFalse(output().contains("Command interrupted"), output());
		Assertions.assertEquals(cancelsSoFar, jdbc.cancels.get(), "no cancel reached the next statement");
	}
}
