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
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibUndo;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandLibUndo {

	@Test
	void reportsNoLibraryConfigured() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibUndo cmd = CommandTestSupport.create(CommandLibUndo.class, console);

		cmd.execute("LIB UNDO");

		Assertions.assertTrue(console.getOutput().contains("No SQL library folder specified in the INI file"),
				"expected the no-library message, got:\n" + console.getOutput());
	}

	@Test
	void reportsAnEmptyArchive(@TempDir Path libraryDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibUndo cmd = CommandTestSupport.create(CommandLibUndo.class, null, console, settings);

		cmd.execute("LIB UNDO");

		Assertions.assertTrue(console.getOutput().contains("Nothing to undo"),
				"expected the empty-archive message, got:\n" + console.getOutput());
	}

	@Test
	void restoresTheArchivedEntry(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("Q.sql"), "select 1;");
		new FileCatalog(libraryDir.toString()).archive("Q.sql");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibUndo cmd = CommandTestSupport.create(CommandLibUndo.class, null, console, settings);

		cmd.execute("LIB UNDO");

		Assertions.assertTrue(Files.exists(libraryDir.resolve("Q.sql")), "expected the entry to be restored");
		Assertions.assertTrue(console.getOutput().contains("Restored"), "expected a confirmation, got:\n" + console.getOutput());
	}
}
