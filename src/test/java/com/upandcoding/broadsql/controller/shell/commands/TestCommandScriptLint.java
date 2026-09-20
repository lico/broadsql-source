package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.scripts.CommandScriptLint;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandScriptLint {

	@Test
	void reportsNoIssuesForACleanCatalog(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("Q.sql"), "BYE;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptLint cmd = CommandTestSupport.create(CommandScriptLint.class, null, console, settings);

		cmd.execute("SCRIPT LINT");

		Assertions.assertTrue(console.getOutput().contains("no issues found"), "expected a clean report, got:\n" + console.getOutput());
	}

	@Test
	void reportsUnknownInstanceId(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("Q.sql"), "-- @instance: NOPE\nBYE;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptLint cmd = CommandTestSupport.create(CommandScriptLint.class, null, console, settings);
		cmd.setDatabaseConnectionsVault(TestDatabaseConnections.newFileBackedVault("MYSAP"));

		cmd.execute("SCRIPT LINT");

		Assertions.assertTrue(console.getOutput().contains("@instance 'NOPE'"), "expected the unknown-instance finding, got:\n" + console.getOutput());
	}

	@Test
	void reportsDuplicateAlias(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("A.sql"), "-- @alias: daily\nBYE;");
		Files.writeString(scriptsDir.resolve("B.sql"), "-- @alias: daily\nBYE;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptLint cmd = CommandTestSupport.create(CommandScriptLint.class, null, console, settings);

		cmd.execute("SCRIPT LINT");

		Assertions.assertTrue(console.getOutput().contains("daily"), "expected the duplicate-alias finding, got:\n" + console.getOutput());
	}
}
