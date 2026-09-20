package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibRestore;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandLibRestore {

	@Test
	void reportsNoLibraryConfigured() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibRestore cmd = CommandTestSupport.create(CommandLibRestore.class, console);

		cmd.execute("LIB RESTORE");

		Assertions.assertTrue(console.getOutput().contains("No SQL library folder specified in the INI file"),
				"expected the no-library message, got:\n" + console.getOutput());
	}

	@Test
	void reportsNoNameGiven(@TempDir Path libraryDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibRestore cmd = CommandTestSupport.create(CommandLibRestore.class, null, console, settings);

		cmd.execute("LIB RESTORE");

		Assertions.assertTrue(console.getOutput().contains("You must provide the name"),
				"expected the missing-name message, got:\n" + console.getOutput());
	}

	@Test
	void restoresTheNamedEntry(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("Q.sql"), "select 1;");
		new FileCatalog(libraryDir.toString()).archive("Q.sql");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibRestore cmd = CommandTestSupport.create(CommandLibRestore.class, null, console, settings);

		cmd.execute("LIB RESTORE Q.sql");

		Assertions.assertTrue(Files.exists(libraryDir.resolve("Q.sql")), "expected the entry to be restored");
	}

	@Test
	void reportsWhenNothingArchivedMatches(@TempDir Path libraryDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibRestore cmd = CommandTestSupport.create(CommandLibRestore.class, null, console, settings);

		cmd.execute("LIB RESTORE NOTHERE.sql");

		Assertions.assertTrue(console.getOutput().contains("Nothing archived matches"),
				"expected the not-found message, got:\n" + console.getOutput());
	}
}
