package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibShow;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandLibShow {

	@Test
	void reportsNoLibraryConfigured() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibShow cmd = CommandTestSupport.create(CommandLibShow.class, console);

		cmd.execute("LIB SHOW");

		Assertions.assertTrue(console.getOutput().contains("No SQL library folder specified in the INI file"),
				"expected the no-library message, got:\n" + console.getOutput());
	}

	@Test
	void reportsNoSearchStringGiven(@TempDir Path libraryDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibShow cmd = CommandTestSupport.create(CommandLibShow.class, null, console, settings);

		cmd.execute("LIB SHOW");

		Assertions.assertTrue(console.getOutput().contains("No search string specified"),
				"expected the no-search-string message, got:\n" + console.getOutput());
	}

	@Test
	void reportsAFileThatIsNotInTheLibrary(@TempDir Path libraryDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibShow cmd = CommandTestSupport.create(CommandLibShow.class, null, console, settings);

		cmd.execute("LIB SHOW DOESNOTEXIST");

		Assertions.assertTrue(console.getOutput().contains("The SQL library does not contain the requested query file"),
				"expected the not-in-library message, got:\n" + console.getOutput());
	}

	@Test
	void showsTheContentOfAnExistingFile(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("COUNTRY.sql"), "SELECT * FROM COUNTRY;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibShow cmd = CommandTestSupport.create(CommandLibShow.class, null, console, settings);

		cmd.execute("LIB SHOW COUNTRY");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("SELECT * FROM COUNTRY;"), "expected the file content, got:\n" + output);
	}

	@Test
	void resolvesByAlias(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("REVENUE.sql"), "-- @alias: rev\nSELECT * FROM REVENUE;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibShow cmd = CommandTestSupport.create(CommandLibShow.class, null, console, settings);

		cmd.execute("LIB SHOW rev");

		Assertions.assertTrue(console.getOutput().contains("SELECT * FROM REVENUE;"),
				"expected the alias to resolve to the file's content, got:\n" + console.getOutput());
	}

	@Test
	void warnsOnEnvironmentMismatchButStillShowsContent(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("PRODONLY.sql"), "-- @environment: PROD\nSELECT 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibShow cmd = CommandTestSupport.create(CommandLibShow.class, null, console, settings);
		CommandTestSupport.wireEnvironment(cmd, "DB1", "TEST");

		cmd.execute("LIB SHOW PRODONLY");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("tagged for environment PROD"), "expected the mismatch warning, got:\n" + output);
		Assertions.assertTrue(output.contains("SELECT 1;"), "the content must still be shown despite the mismatch, got:\n" + output);
	}

	@Test
	void warnsOnInstanceMismatchButStillShowsContent(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("MYSAPONLY.sql"), "-- @instance: MYSAP\nSELECT 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibShow cmd = CommandTestSupport.create(CommandLibShow.class, null, console, settings);
		CommandTestSupport.wireInstanceAndEnvironment(cmd, "DB1", "JIRA", null);

		cmd.execute("LIB SHOW MYSAPONLY");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("tagged for instance MYSAP"), "expected the mismatch warning, got:\n" + output);
		Assertions.assertTrue(output.contains("SELECT 1;"), "the content must still be shown despite the mismatch, got:\n" + output);
	}
}
