package com.upandcoding.broadsql.controller.shell.commands;

import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.scripts.CommandScriptDel;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandScriptDel {

	// Same reasoning as TestCommandLibDel: the interactive y/n confirmation cannot be exercised here
	// (console.inputField(...) requires a real console, unavailable under this repo's test runner) - only
	// the guard-clause paths before that prompt is reached are testable; the archive mechanics themselves
	// (FileCatalog.archive(...), no console involved) are covered directly by TestFileCatalog.

	@Test
	void defaultsToScriptsSubfolderWhenNotConfiguredInIni() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandScriptDel cmd = CommandTestSupport.create(CommandScriptDel.class, console);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("SCRIPT DEL DAILY"),
				"expected the default 'scripts' subfolder to be used, and reported missing since it doesn't exist under the test working directory");
		Assertions.assertTrue(ex.getMessage().contains("Invalid path to the scripts catalog"),
				"expected the invalid-path message, got: " + ex.getMessage());
	}

	@Test
	void reportsAFileThatIsNotInTheCatalog(@TempDir Path scriptsDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptDel cmd = CommandTestSupport.create(CommandScriptDel.class, null, console, settings);

		cmd.execute("SCRIPT DEL DOESNOTEXIST");

		Assertions.assertTrue(console.getOutput().contains("The scripts catalog does not contain the requested file"),
				"expected the not-in-catalog message, got:\n" + console.getOutput());
	}
}
