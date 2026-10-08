package com.upandcoding.broadsql.controller.shell.commands;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.upandcoding.broadsql.controller.shell.reader.JLineConsoleLineReader;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;
import com.upandcoding.broadsql.dao.DatabaseConnection;

/**
 * GitHub #196: CTRL+C stops a running {@code REPEAT} and BroadSQL stays usable, through the path a real interactive
 * console uses. The console reads its commands with a JLine reader over a real key-binding terminal; the interpreter
 * installs its interrupt handler on that terminal ({@code attachTerminalInterruptHandler}, part of {@code run()}); a
 * statement is read at the prompt first, so JLine has already restored its INT handler once (the step that used to
 * leave the JVM's default action, exit, in place); then CTRL+C is typed as a key while REPEAT runs, and the terminal
 * raises INT exactly as a system terminal does. Nothing calls {@code handleInterrupt()} directly.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TestRepeatCtrlCThroughTerminal extends ScriptingTestBase {

	private static final String LONG_QUERY = "SELECT COUNT(*) FROM SYSTEM_RANGE(1, 3000000000) A WHERE MOD(A.X, 7) = 3";
	private static final int CTRL_C = 3;

	private PipedOutputStream keyboard;
	private JLineConsoleLineReader reader;

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
		// a first command read and run at the prompt: JLine has restored its INT handler once
		prompt("SELECT REC('first') AS R;");
		Assertions.assertEquals(List.of("first"), RepeatProbe.calls());
		RepeatProbe.reset();
	}

	@AfterEach
	void tearDownTerminal() {
		reader.close();
	}

	/** Types {@code line} and Enter at the prompt, reads it as the input loop does, and executes it. */
	private void prompt(String line) throws IOException {
		keyboard.write((line + "\r").getBytes(StandardCharsets.UTF_8));
		keyboard.flush();
		String read = console.readCommandLine(false).trim();
		Assertions.assertTrue(read.endsWith(";"), read);
		interpreter.executeMultiStatementLine(StringUtils.substringBeforeLast(read, ";"));
	}

	private Thread promptInBackground(String line, AtomicReference<Throwable> failure) throws IOException {
		keyboard.write((line + "\r").getBytes(StandardCharsets.UTF_8));
		keyboard.flush();
		Thread runner = new Thread(() -> {
			try {
				String read = console.readCommandLine(false).trim();
				interpreter.executeMultiStatementLine(StringUtils.substringBeforeLast(read, ";"));
			} catch (Throwable t) {
				failure.set(t);
			}
		}, "repeat-terminal-runner");
		runner.start();
		return runner;
	}

	private void waitForOutput(String fragment) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 30_000;
		while (!output().contains(fragment)) {
			Assertions.assertTrue(System.currentTimeMillis() < deadline, "never saw '" + fragment + "': " + output());
			Thread.sleep(20);
		}
	}

	private void typeCtrlC() throws IOException {
		keyboard.write(CTRL_C);
		keyboard.flush();
	}

	/** REPEAT ended; BroadSQL did not take its shutdown path; the session is usable at once. */
	private void assertStoppedAndUsable(Thread runner, AtomicReference<Throwable> failure) throws Exception {
		runner.join(30_000);
		Assertions.assertFalse(runner.isAlive(), "CTRL+C must end the REPEAT: " + output());
		Assertions.assertNull(failure.get());
		Assertions.assertNull(interpreter.getRepeatGuard(), "REPEAT has stopped");
		Assertions.assertTrue(db.isConnected(), "the connection is still open: no shutdown path ran");
		Assertions.assertFalse(output().contains("Disconnected"), output());
		RepeatProbe.reset();
		prompt("SELECT REC('usable') AS R;");
		Assertions.assertEquals(List.of("usable"), RepeatProbe.calls(), "the next statement typed at the prompt runs: " + output());
	}

	@Test
	void ctrlCDuringTheWait() throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground("REPEAT BEGIN SELECT REC('w') AS R; END EVERY 30s;", failure);
		waitForOutput("Next execution in 30s...");
		long pressed = System.currentTimeMillis();
		typeCtrlC();
		assertStoppedAndUsable(runner, failure);
		Assertions.assertTrue(System.currentTimeMillis() - pressed < 10_000, "the wait is cancelled, not waited out");
		Assertions.assertTrue(output().contains("REPEAT cancelled by user after 1 complete iteration."), output());
	}

	@Test
	void ctrlCOfTheLastQueryFormDuringTheWait() throws Exception {
		prompt("SELECT REC('last') AS R;");
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground("REPEAT EVERY 30s;", failure);
		waitForOutput("Next execution in 30s...");
		typeCtrlC();
		assertStoppedAndUsable(runner, failure);
	}

	@Test
	void ctrlCDuringALongQueryCancelsItThroughJdbc() throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground("REPEAT BEGIN SELECT REC('q') AS R; " + LONG_QUERY + "; END EVERY 10s;", failure);
		java.lang.reflect.Field field = DatabaseConnection.class.getDeclaredField("currentStatement");
		field.setAccessible(true);
		long deadline = System.currentTimeMillis() + 20_000;
		while (!(output().contains("[2/2]") && field.get(db) != null)) {
			Assertions.assertTrue(System.currentTimeMillis() < deadline, "the long query never started: " + output());
			Thread.sleep(10);
		}
		Thread.sleep(200);
		typeCtrlC();
		assertStoppedAndUsable(runner, failure);
		Assertions.assertTrue(output().contains("REPEAT cancelled by user after 0 complete iterations."), output());
	}

	@Test
	void ctrlCDuringARepeatedAtScript() throws Exception {
		lib("m.sql", "SELECT REC('m1') AS R;\nSELECT REC('m2') AS R;\n");
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground("REPEAT @m.sql EVERY 30s;", failure);
		waitForOutput("Next execution in 30s...");
		typeCtrlC();
		assertStoppedAndUsable(runner, failure);
	}

	@Test
	void ctrlCDuringARepeatedLibRunScript() throws Exception {
		lib("m.sql", "SELECT REC('m1') AS R;\nSELECT REC('m2') AS R;\n");
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground("REPEAT LIB RUN m.sql EVERY 30s;", failure);
		waitForOutput("Next execution in 30s...");
		typeCtrlC();
		assertStoppedAndUsable(runner, failure);
	}

	@Test
	void ctrlCDuringARepeatStartedByAScript() throws Exception {
		lib("host.sql", "SELECT REC('h') AS R;\nREPEAT EVERY 30s;\nSELECT REC('after') AS R;\n");
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = promptInBackground("@host.sql;", failure);
		waitForOutput("Next execution in 30s...");
		typeCtrlC();
		runner.join(30_000);
		Assertions.assertEquals(ScriptStatus.CANCELLED, lastRun().getStatus(), output());
		Assertions.assertFalse(RepeatProbe.calls().contains("after"), "the rest of the script is cancelled");
		assertStoppedAndUsable(runner, failure);
	}

	@Test
	void ctrlCAtThePromptStillOnlyAbandonsTheLine() throws Exception {
		// the prompt is reading first, then the keys arrive, as at a real terminal
		AtomicReference<String> read = new AtomicReference<>();
		Thread reading = new Thread(() -> read.set(console.readCommandLine(false)), "prompt-reader");
		reading.start();
		Thread.sleep(300);
		keyboard.write("SELECT REC('abandoned')".getBytes(StandardCharsets.UTF_8));
		keyboard.flush();
		Thread.sleep(200);
		typeCtrlC();
		reading.join(10_000);
		Assertions.assertFalse(reading.isAlive(), "CTRL+C ends the read");
		Assertions.assertEquals("", read.get());
		Assertions.assertFalse(CommandCancellation.isRunCancelled(), "no command was running: nothing to cancel");
		prompt("SELECT REC('next') AS R;");
		Assertions.assertEquals(List.of("next"), RepeatProbe.calls());
	}
}
