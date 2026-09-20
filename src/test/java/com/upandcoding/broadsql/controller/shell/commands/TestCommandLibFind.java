package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibFind;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandLibFind {

	@Test
	void reportsNoLibraryConfigured() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibFind cmd = CommandTestSupport.create(CommandLibFind.class, console);

		cmd.execute("LIB FIND");

		Assertions.assertTrue(console.getOutput().contains("No SQL library folder specified in the INI file"),
				"expected the no-library message, got:\n" + console.getOutput());
	}

	@Test
	void reportsNoSearchTermGiven(@TempDir Path libraryDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibFind cmd = CommandTestSupport.create(CommandLibFind.class, null, console, settings);

		cmd.execute("LIB FIND");

		Assertions.assertTrue(console.getOutput().contains("You must provide a search term"),
				"expected the missing-term message, got:\n" + console.getOutput());
	}

	@Test
	void findsAMatchInContentNotJustFileName(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("Q.sql"), "select country, sum(amount) from revenue group by country;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibFind cmd = CommandTestSupport.create(CommandLibFind.class, null, console, settings);

		cmd.execute("LIB FIND revenue");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Q.sql"), "expected the matching file, got:\n" + output);
		Assertions.assertTrue(output.contains("sum(amount) from revenue"), "expected the matching line as context, got:\n" + output);
	}

	@Test
	void reportsNoMatch(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("Q.sql"), "select 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibFind cmd = CommandTestSupport.create(CommandLibFind.class, null, console, settings);

		cmd.execute("LIB FIND doesnotexistanywhere");

		Assertions.assertTrue(console.getOutput().contains("No library entry matches"),
				"expected the no-match message, got:\n" + console.getOutput());
	}

	@Test
	void ignoresEnvironmentScoping(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("PRODONLY.sql"), "-- @environment: PROD\nselect * from revenue;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibFind cmd = CommandTestSupport.create(CommandLibFind.class, null, console, settings);
		CommandTestSupport.wireEnvironment(cmd, "DB1", "TEST");

		cmd.execute("LIB FIND revenue");

		Assertions.assertTrue(console.getOutput().contains("PRODONLY.sql"),
				"FIND must ignore environment scoping, got:\n" + console.getOutput());
	}
}
