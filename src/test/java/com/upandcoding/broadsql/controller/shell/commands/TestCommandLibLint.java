package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibLint;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandLibLint {

	@Test
	void reportsNoLibraryConfigured() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibLint cmd = CommandTestSupport.create(CommandLibLint.class, console);

		cmd.execute("LIB LINT");

		Assertions.assertTrue(console.getOutput().contains("No SQL library folder specified in the INI file"),
				"expected the no-library message, got:\n" + console.getOutput());
	}

	@Test
	void reportsNoIssuesForACleanLibrary(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("Q.sql"), "select * from t where a = %1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibLint cmd = CommandTestSupport.create(CommandLibLint.class, null, console, settings);

		cmd.execute("LIB LINT");

		Assertions.assertTrue(console.getOutput().contains("no issues found"), "expected a clean report, got:\n" + console.getOutput());
	}

	@Test
	void reportsNonContiguousParams(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("Q.sql"), "select * from t where a = %1 and b = %3;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibLint cmd = CommandTestSupport.create(CommandLibLint.class, null, console, settings);

		cmd.execute("LIB LINT");

		Assertions.assertTrue(console.getOutput().contains("non-contiguous"), "expected the gap finding, got:\n" + console.getOutput());
	}

	@Test
	void reportsDuplicateAlias(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("A.sql"), "-- @alias: rev\nselect 1;");
		Files.writeString(libraryDir.resolve("B.sql"), "-- @alias: rev\nselect 2;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibLint cmd = CommandTestSupport.create(CommandLibLint.class, null, console, settings);

		cmd.execute("LIB LINT");

		Assertions.assertTrue(console.getOutput().contains("rev"), "expected the duplicate-alias finding, got:\n" + console.getOutput());
	}

	@Test
	void reportsUnknownInstanceId(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("Q.sql"), "-- @instance: NOPE\nselect 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibLint cmd = CommandTestSupport.create(CommandLibLint.class, null, console, settings);
		// A vault with at least one registered instance - the "unknown id" check only fires when there's
		// a known-instances list to check against (see CommandLibLint.execute()).
		cmd.setDatabaseConnectionsVault(TestDatabaseConnections.newFileBackedVault("MYSAP"));

		cmd.execute("LIB LINT");

		Assertions.assertTrue(console.getOutput().contains("@instance 'NOPE'"), "expected the unknown-instance finding, got:\n" + console.getOutput());
	}

	@Test
	void singleEntryLintOnlyReportsThatEntry(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("BAD.sql"), "select * from t where a = %1 and b = %3;");
		Files.writeString(libraryDir.resolve("GOOD.sql"), "select * from t where a = %1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibLint cmd = CommandTestSupport.create(CommandLibLint.class, null, console, settings);

		cmd.execute("LIB LINT GOOD");

		Assertions.assertTrue(console.getOutput().contains("no issues found"),
				"linting GOOD alone must not surface BAD's issue, got:\n" + console.getOutput());
	}
}
