package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.commands.CommandTestSupport;
import com.upandcoding.broadsql.controller.shell.output.CapturingConsole;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.controller.shell.output.ConsoleLogger;
import com.upandcoding.broadsql.controller.shell.style.ColorMode;
import com.upandcoding.broadsql.controller.shell.style.TerminalStyle;
import com.upandcoding.broadsql.controller.shell.style.TerminalStyleHolder;
import com.upandcoding.broadsql.controller.shell.style.Theme;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * SPRINT 3009A correction pass: the Editor's Run output. Run through the real interpreter against a real H2
 * database, with the log file on and terminal colors on, exactly the setup that failed manually:
 * <ul>
 * <li>no {@code NullPointerException}: the capture console now carries the application console's log file writer
 * (before, the interpreter switched the log on for the capture, which had none);</li>
 * <li>no ANSI escape sequence in the Output text: the capture renders plain text where a terminal console renders
 * colors;</li>
 * <li>a SQL error is shown as text in the output and the run returns normally.</li>
 * </ul>
 */
class TestEditorRunOutput {

	private static final String ESC = "\u001B";

	@AfterEach
	void plainAgain() {
		TerminalStyleHolder.set(null);
	}

	private static void colorsOn() {
		TerminalStyleHolder.set(TerminalStyle.resolve(ColorMode.ON, Theme.named(Theme.DEFAULT_DARK), null));
	}

	@Test
	void theCaptureRendersPlainTextWhereTheTerminalConsoleRendersColors() {
		colorsOn();
		CapturingShellConsole terminal = new CapturingShellConsole();
		CapturingConsole capture = new CapturingConsole();
		for (var console : List.of(terminal, capture)) {
			console.setPrompt("WORLD> ");
			console.error("Table \"TOTO\" not found [42102-224] SQLState 42S02", true);
			console.warn("careful", false);
			console.successln("done");
			console.println("plain line 12 | 34");
		}
		Assertions.assertTrue(terminal.getOutput().contains(ESC + "["), "control: the terminal console is colored: " + terminal.getOutput());
		String captured = capture.getOutput();
		Assertions.assertFalse(captured.contains(ESC), "no escape sequence reaches the Editor: " + captured);
		Assertions.assertEquals("WORLD> ERROR: Table \"TOTO\" not found [42102-224] SQLState 42S02\nWARNING: careful\ndone\nWORLD> plain line 12 | 34\n",
				captured.replace("\r\n", "\n"), "every character of the content is kept, prompt included");
	}

	@Test
	void aStandInCaptureCarriesTheConsolesLoggerPromptAndLogSwitch() {
		CapturingShellConsole original = new CapturingShellConsole();
		ConsoleLogger logger = new ConsoleLogger();
		original.consoleLogger = logger;
		original.setPrompt("WORLD> ");
		original.setPrintToLogFile(true);

		CapturingConsole capture = CapturingConsole.standingInFor(original);

		Assertions.assertSame(logger, capture.consoleLogger);
		Assertions.assertEquals("WORLD> ", capture.getPrompt());
		Assertions.assertTrue(capture.isPrintToLogFile());
		Assertions.assertNull(CapturingConsole.standingInFor(null).consoleLogger, "nothing to adopt: a plain capture");
	}

	/** The application console as BroadSQL builds it: Spring injects its log file writer. */
	private static ScriptRunContext loggedContext(DatabaseConnection db, ConsoleSettings settings) {
		CapturingShellConsole appConsole = new CapturingShellConsole();
		ConsoleLogger logger = new ConsoleLogger();
		logger.consoleSettings = settings;
		logger.database = db;
		logger.console = appConsole;
		appConsole.consoleLogger = logger;
		CommandInterpreter interpreter = CommandTestSupport.createCommandInterpreter(settings, appConsole, db);
		return new ScriptRunContext(db, interpreter, null, null, settings, db.getPlatform().getId());
	}

	private static ConsoleSettings loggingSettings(Path lib, Path logs) {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(lib.toString());
		settings.setLogDefaultActivated(true);
		settings.setLogFolderName(logs.toString());
		return settings;
	}

	@Test
	void aBadStatementWithTheLogOnShowsTheSqlErrorAsPlainTextWithoutAnyException(@TempDir Path lib, @TempDir Path logs) throws IOException, BroadSQLException {
		colorsOn();
		DatabaseConnection db = TestDatabaseConnections.connectInMemory("CREATE TABLE COUNTRY (A INT)");
		try {
			Files.writeString(lib.resolve("bad.sql"), "-- @status: stable\nselect count(*)\nfrom COUNTRY ORDER FROM WHAT OR WHERE\nSELECT TOTO WHERE FROM SELECT\nwhere 1 = 1;\n");
			ScriptRunContext context = loggedContext(db, loggingSettings(lib, logs));

			RunOutcome outcome = Assertions.assertDoesNotThrow(() -> new ScriptRunCoordinator().run("bad.sql", context, ""));

			Assertions.assertEquals(RunOutcome.Status.EXECUTED, outcome.status());
			Assertions.assertTrue(outcome.hasErrors(), "the failed statement is counted, so the status bar does not say it simply completed");
			String output = outcome.capturedOutput();
			Assertions.assertTrue(output.contains("ERROR"), output);
			Assertions.assertTrue(output.toLowerCase().contains("syntax"), "the database's own message is shown: " + output);
			Assertions.assertFalse(output.contains(ESC), output);
			Assertions.assertFalse(output.contains("NullPointerException"), output);
			try (Stream<Path> files = Files.list(logs)) {
				Assertions.assertTrue(files.findAny().isPresent(), "the log file was written, through the application console's logger");
			}
		} finally {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void aValidScriptWithTheLogOnShowsItsResult(@TempDir Path lib, @TempDir Path logs) throws IOException, BroadSQLException {
		colorsOn();
		DatabaseConnection db = TestDatabaseConnections.connectInMemory("CREATE TABLE COUNTRY (A INT)", "INSERT INTO COUNTRY VALUES (42)");
		try {
			Files.writeString(lib.resolve("ok.sql"), "select count(*) as N, max(A) as M from COUNTRY;\n");
			RunOutcome outcome = new ScriptRunCoordinator().run("ok.sql", loggedContext(db, loggingSettings(lib, logs)), "");

			Assertions.assertNull(outcome.error());
			Assertions.assertTrue(outcome.capturedOutput().contains("42"), outcome.capturedOutput());
			Assertions.assertFalse(outcome.capturedOutput().contains(ESC), outcome.capturedOutput());
			Assertions.assertFalse(outcome.hasErrors());
		} finally {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void anUnexpectedFailureIsReportedInTheOutputAndNeverEscapes(@TempDir Path lib) throws BroadSQLException {
		DatabaseConnection db = TestDatabaseConnections.connectInMemory();
		try {
			ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
			CommandInterpreter interpreter = CommandTestSupport.createCommandInterpreter(settings, new CapturingShellConsole(), db);
			// No settings in the context: resolving the Script fails with a NullPointerException inside run().
			ScriptRunContext broken = new ScriptRunContext(db, interpreter, null, null, null, db.getPlatform().getId());

			RunOutcome outcome = Assertions.assertDoesNotThrow(() -> new ScriptRunCoordinator().run("x.sql", broken, ""));

			Assertions.assertNotNull(outcome.error());
			Assertions.assertTrue(outcome.capturedOutput().contains("Unexpected error"), outcome.capturedOutput());
			Assertions.assertSame(interpreter.getConsole().getClass(), CapturingShellConsole.class, "the interpreter's console is restored");
		} finally {
			TestDatabaseConnections.close(db);
		}
	}
}
