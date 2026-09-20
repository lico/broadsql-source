package com.upandcoding.broadsql.controller.shell.commands;

import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibDel;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandLibDel {

	@Test
	void reportsNoLibraryConfigured() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibDel cmd = CommandTestSupport.create(CommandLibDel.class, console);

		cmd.execute("LIB DEL");

		Assertions.assertTrue(console.getOutput().contains("No SQL library folder specified in the INI file"),
				"expected the no-library message, got:\n" + console.getOutput());
	}

	@Test
	void reportsAFileThatIsNotInTheLibrary(@TempDir Path libraryDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibDel cmd = CommandTestSupport.create(CommandLibDel.class, null, console, settings);

		cmd.execute("LIB DEL DOESNOTEXIST");

		Assertions.assertTrue(console.getOutput().contains("The SQL library does not contain the requested query file"),
				"expected the not-in-library message, got:\n" + console.getOutput());
	}

	// `LIB DEL` now asks for an interactive y/n confirmation before archiving (see
	// docs/SQL_LIBRARY_AND_SCRIPTS.md, section 6), and console.inputField(...) requires a real console
	// (System.console(), null under this repo's test runner) - the same reason CommandEdit's Notepad
	// round-trip is untested (see TestCommandEdit's own Javadoc, docs/TESTS_STRATEGY.md). Only the two
	// guard-clause paths below (before the confirmation prompt is ever reached) are testable here; the
	// archive mechanics themselves (FileCatalog.archive(...), no console involved) are covered directly
	// by TestFileCatalog.
}
