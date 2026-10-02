package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.scripting.CommandJsList;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandJsList {

	@Test
	void defaultsToJsscriptsSubfolderWhenNotConfiguredInIni() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandJsList cmd = CommandTestSupport.create(CommandJsList.class, console);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("JS LIST"),
				"expected the default 'jsscripts' subfolder to be used, and reported missing since it doesn't exist under the test working directory");
		Assertions.assertTrue(ex.getMessage().contains("Invalid path to the JS scripts catalog"),
				"expected the invalid-path message, got: " + ex.getMessage());
	}

	@Test
	void reportsAnEmptyCatalog(@TempDir Path jsScriptsDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setJsScriptsPath(jsScriptsDir.toString());
		CommandJsList cmd = CommandTestSupport.create(CommandJsList.class, null, console, settings);

		cmd.execute("JS LIST");

		Assertions.assertTrue(console.getOutput().contains("The JS scripts catalog is empty"),
				"expected the empty-catalog message, got:\n" + console.getOutput());
	}

	@Test
	void listsEveryFileWithNoSearchTermAndNoParamsColumn(@TempDir Path jsScriptsDir) throws BroadSQLException, IOException {
		Files.writeString(jsScriptsDir.resolve("DAILY.js"), "println('ran');\n");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setJsScriptsPath(jsScriptsDir.toString());
		CommandJsList cmd = CommandTestSupport.create(CommandJsList.class, null, console, settings);

		cmd.execute("JS LIST");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("DAILY.js"), "expected DAILY.js in output, got:\n" + output);
		Assertions.assertFalse(output.contains("Params"), "JS scripts must not show the LIB-only Params column, got:\n" + output);
	}

	@Test
	void defaultViewHidesAnEntryTaggedForAnotherEnvironmentButAllShowsIt(@TempDir Path jsScriptsDir) throws BroadSQLException, IOException {
		Files.writeString(jsScriptsDir.resolve("PRODONLY.js"), "-- @environment: PROD\nprintln('ran');\n");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setJsScriptsPath(jsScriptsDir.toString());
		CommandJsList cmd = CommandTestSupport.create(CommandJsList.class, null, console, settings);
		CommandTestSupport.wireEnvironment(cmd, "DB1", "TEST");

		cmd.execute("JS LIST");
		Assertions.assertFalse(console.getOutput().contains("PRODONLY.js"),
				"a PROD-only entry must not show while connected to TEST, got:\n" + console.getOutput());

		console.clear();
		cmd.execute("JS LIST ALL");
		Assertions.assertTrue(console.getOutput().contains("PRODONLY.js"),
				"JS LIST ALL must show every environment, got:\n" + console.getOutput());
	}

	@Test
	void defaultViewHidesAnEntryTaggedForAnotherInstanceButAllShowsIt(@TempDir Path jsScriptsDir) throws BroadSQLException, IOException {
		Files.writeString(jsScriptsDir.resolve("MYSAPONLY.js"), "-- @instance: MYSAP\nprintln('ran');\n");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setJsScriptsPath(jsScriptsDir.toString());
		CommandJsList cmd = CommandTestSupport.create(CommandJsList.class, null, console, settings);
		CommandTestSupport.wireInstanceAndEnvironment(cmd, "DB1", "JIRA", null);

		cmd.execute("JS LIST");
		Assertions.assertFalse(console.getOutput().contains("MYSAPONLY.js"),
				"a MYSAP-only entry must not show while connected to JIRA, got:\n" + console.getOutput());

		console.clear();
		cmd.execute("JS LIST ALL");
		Assertions.assertTrue(console.getOutput().contains("MYSAPONLY.js"),
				"JS LIST ALL must show every instance, got:\n" + console.getOutput());
	}
}
