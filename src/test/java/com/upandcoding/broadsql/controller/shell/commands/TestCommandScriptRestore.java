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
import com.upandcoding.broadsql.controller.shell.commands.core.scripts.CommandScriptRestore;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandScriptRestore {

	@Test
	void restoresTheNamedEntry(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("Q.sql"), "BYE;");
		new FileCatalog(scriptsDir.toString()).archive("Q.sql");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptRestore cmd = CommandTestSupport.create(CommandScriptRestore.class, null, console, settings);

		cmd.execute("SCRIPT RESTORE Q.sql");

		Assertions.assertTrue(Files.exists(scriptsDir.resolve("Q.sql")), "expected the entry to be restored");
	}

	@Test
	void reportsWhenNothingArchivedMatches(@TempDir Path scriptsDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptRestore cmd = CommandTestSupport.create(CommandScriptRestore.class, null, console, settings);

		cmd.execute("SCRIPT RESTORE NOTHERE.sql");

		Assertions.assertTrue(console.getOutput().contains("Nothing archived matches"),
				"expected the not-found message, got:\n" + console.getOutput());
	}
}
