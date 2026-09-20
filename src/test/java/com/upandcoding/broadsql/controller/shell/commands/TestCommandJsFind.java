package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.scripting.CommandJsFind;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandJsFind {

	@Test
	void defaultsToJsscriptsSubfolderWhenNotConfiguredInIni() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandJsFind cmd = CommandTestSupport.create(CommandJsFind.class, console);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("JS FIND revenue"),
				"expected the default 'jsscripts' subfolder to be used, and reported missing since it doesn't exist under the test working directory");
		Assertions.assertTrue(ex.getMessage().contains("Invalid path to the JS scripts catalog"),
				"expected the invalid-path message, got: " + ex.getMessage());
	}

	@Test
	void findsAMatchInContentNotJustFileName(@TempDir Path jsScriptsDir) throws BroadSQLException, IOException {
		Files.writeString(jsScriptsDir.resolve("Q.js"), "var conn = connect('REVENUE_DB');\nprintln('done');\n");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setJsScriptsPath(jsScriptsDir.toString());
		CommandJsFind cmd = CommandTestSupport.create(CommandJsFind.class, null, console, settings);

		cmd.execute("JS FIND revenue");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Q.js"), "expected the matching file, got:\n" + output);
		Assertions.assertTrue(output.contains("REVENUE_DB"), "expected the matching line as context, got:\n" + output);
	}

	@Test
	void noMatchReportsClearly(@TempDir Path jsScriptsDir) throws BroadSQLException, IOException {
		Files.writeString(jsScriptsDir.resolve("Q.js"), "println('nothing interesting');\n");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setJsScriptsPath(jsScriptsDir.toString());
		CommandJsFind cmd = CommandTestSupport.create(CommandJsFind.class, null, console, settings);

		cmd.execute("JS FIND revenue");

		Assertions.assertTrue(console.getOutput().contains("No JS script matches 'revenue'"),
				"expected the no-match message, got:\n" + console.getOutput());
	}
}
