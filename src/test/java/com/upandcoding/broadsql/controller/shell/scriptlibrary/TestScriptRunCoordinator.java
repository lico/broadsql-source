package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.commands.CommandTestSupport;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * Exercises {@link ScriptRunCoordinator} against a real, connected in-memory H2 database (the same
 * {@code TestDatabaseConnections}/{@code CommandTestSupport} fixtures every other command test in this
 * codebase already uses) - proves the delegate is actually reaching the real execution infrastructure,
 * not a parallel path, per spec section 22.
 */
class TestScriptRunCoordinator {

	private final ScriptRunCoordinator coordinator = new ScriptRunCoordinator();

	/** The editor's Run always has the shell's real interpreter (it comes from the EDIT command); tests build the same wiring. */
	private static ScriptRunContext contextWithInterpreter(DatabaseConnection db, ConsoleSettings settings) {
		CommandInterpreter interpreter = CommandTestSupport.createCommandInterpreter(settings, new CapturingShellConsole(), db);
		return new ScriptRunContext(db, interpreter, null, null, settings, db.getPlatform().getId());
	}

	@Test
	void noActiveConnectionRefusesToRun() {
		RunOutcome outcome = coordinator.run("q.sql", ScriptRunContext.empty(), "");

		Assertions.assertEquals(RunOutcome.Status.NO_ACTIVE_CONNECTION, outcome.status());
	}

	@Test
	void runningASqlLibraryEntryExecutesAgainstTheRealConnectionAndCapturesTheResult(@TempDir Path libRoot) throws IOException, BroadSQLException {
		DatabaseConnection db = TestDatabaseConnections.connectInMemory("CREATE TABLE T (A INT)", "INSERT INTO T VALUES (42)");
		try {
			Files.writeString(libRoot.resolve("q.sql"), "select * from t;");
			ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
			settings.setScriptsLibraryPath(libRoot.toString());
			ScriptRunContext context = contextWithInterpreter(db, settings);

			RunOutcome outcome = coordinator.run("q.sql", context, "");

			Assertions.assertEquals(RunOutcome.Status.EXECUTED, outcome.status());
			Assertions.assertNull(outcome.error());
			Assertions.assertTrue(outcome.capturedOutput().contains("42"), outcome.capturedOutput());
		} finally {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void namedArgumentsReachTheScriptAsTypedBinds(@TempDir Path libRoot) throws IOException, BroadSQLException {
		DatabaseConnection db = TestDatabaseConnections.connectInMemory("CREATE TABLE T (A INT)", "INSERT INTO T VALUES (1)", "INSERT INTO T VALUES (2)");
		try {
			Files.writeString(libRoot.resolve("q.sql"), "-- @params: a\nselect * from t where a = ${a};");
			ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
			settings.setScriptsLibraryPath(libRoot.toString());
			ScriptRunContext context = contextWithInterpreter(db, settings);

			RunOutcome outcome = coordinator.run("q.sql", context, "a=2");

			Assertions.assertEquals(RunOutcome.Status.EXECUTED, outcome.status());
			Assertions.assertEquals(ScriptStatus.SUCCESS, outcome.scriptStatus(), outcome.capturedOutput());
			Assertions.assertFalse(outcome.hasErrors());
			Assertions.assertTrue(outcome.capturedOutput().contains("|2"), outcome.capturedOutput());
			Assertions.assertFalse(outcome.capturedOutput().contains("|1 "), outcome.capturedOutput());
		} finally {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void runningAScriptGoesThroughTheNormalCommandInterpreterReEntryAndCapturesPerStatementOutput(@TempDir Path scriptsRoot) throws Exception {
		DatabaseConnection db = TestDatabaseConnections.connectInMemory("CREATE TABLE T (A INT)", "INSERT INTO T VALUES (7)");
		try {
			Files.writeString(scriptsRoot.resolve("s.bsql"), "select * from t;");
			ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
			settings.setScriptsLibraryPath(scriptsRoot.toString());
			CapturingShellConsole placeholderConsole = new CapturingShellConsole();
			CommandInterpreter interpreter = CommandTestSupport.createCommandInterpreter(settings, placeholderConsole, db);
			ScriptRunContext context = new ScriptRunContext(db, interpreter, null, null, settings, db.getPlatform().getId());

			RunOutcome outcome = coordinator.run("s.bsql", context, "");

			Assertions.assertEquals(RunOutcome.Status.EXECUTED, outcome.status());
			Assertions.assertTrue(outcome.capturedOutput().contains("7"), outcome.capturedOutput());
		} finally {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void aMissingLibraryEntryReportsAnErrorRatherThanThrowingUncaught(@TempDir Path libRoot) throws BroadSQLException {
		DatabaseConnection db = TestDatabaseConnections.connectInMemory();
		try {
			ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
			settings.setScriptsLibraryPath(libRoot.toString());
			ScriptRunContext context = contextWithInterpreter(db, settings);

			RunOutcome outcome = coordinator.run("doesnotexist.sql", context, "");

			Assertions.assertEquals(RunOutcome.Status.EXECUTED, outcome.status());
			Assertions.assertTrue(outcome.capturedOutput().toLowerCase().contains("not found"), outcome.capturedOutput());
			Assertions.assertEquals(ScriptStatus.FAILED, outcome.scriptStatus());
			Assertions.assertTrue(outcome.hasErrors());
		} finally {
			TestDatabaseConnections.close(db);
		}
	}
}
