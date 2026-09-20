package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.scripts.CommandScriptFind;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandScriptFind {

	@Test
	void defaultsToScriptsSubfolderWhenNotConfiguredInIni() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandScriptFind cmd = CommandTestSupport.create(CommandScriptFind.class, console);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("SCRIPT FIND revenue"),
				"expected the default 'scripts' subfolder to be used, and reported missing since it doesn't exist under the test working directory");
		Assertions.assertTrue(ex.getMessage().contains("Invalid path to the scripts catalog"),
				"expected the invalid-path message, got: " + ex.getMessage());
	}

	@Test
	void findsAMatchInContentNotJustFileName(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("Q.sql"), "CONNECT DBHR;\nDUMP REVENUE;\nBYE;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptFind cmd = CommandTestSupport.create(CommandScriptFind.class, null, console, settings);

		cmd.execute("SCRIPT FIND revenue");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Q.sql"), "expected the matching file, got:\n" + output);
		Assertions.assertTrue(output.contains("DUMP REVENUE;"), "expected the matching line as context, got:\n" + output);
	}
}
