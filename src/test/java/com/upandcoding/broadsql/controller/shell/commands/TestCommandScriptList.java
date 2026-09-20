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
import com.upandcoding.broadsql.controller.shell.commands.core.scripts.CommandScriptList;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandScriptList {

	@Test
	void defaultsToScriptsSubfolderWhenNotConfiguredInIni() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandScriptList cmd = CommandTestSupport.create(CommandScriptList.class, console);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("SCRIPT LIST"),
				"expected the default 'scripts' subfolder to be used, and reported missing since it doesn't exist under the test working directory");
		Assertions.assertTrue(ex.getMessage().contains("Invalid path to the scripts catalog"),
				"expected the invalid-path message, got: " + ex.getMessage());
	}

	@Test
	void reportsAnEmptyCatalog(@TempDir Path scriptsDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptList cmd = CommandTestSupport.create(CommandScriptList.class, null, console, settings);

		cmd.execute("SCRIPT LIST");

		Assertions.assertTrue(console.getOutput().contains("The scripts catalog is empty"),
				"expected the empty-catalog message, got:\n" + console.getOutput());
	}

	@Test
	void listsEveryFileWithNoSearchTermAndNoParamsColumn(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("DAILY.sql"), "CONNECT DBHR;\nDUMP MATABLE;\nBYE;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptList cmd = CommandTestSupport.create(CommandScriptList.class, null, console, settings);

		cmd.execute("SCRIPT LIST");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("DAILY.sql"), "expected DAILY.sql in output, got:\n" + output);
		Assertions.assertFalse(output.contains("Params"), "scripts must not show the LIB-only Params column, got:\n" + output);
	}

	@Test
	void defaultViewHidesAnEntryTaggedForAnotherEnvironmentButAllShowsIt(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("PRODONLY.sql"), "-- @environment: PROD\nBYE;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptList cmd = CommandTestSupport.create(CommandScriptList.class, null, console, settings);
		CommandTestSupport.wireEnvironment(cmd, "DB1", "TEST");

		cmd.execute("SCRIPT LIST");
		Assertions.assertFalse(console.getOutput().contains("PRODONLY.sql"),
				"a PROD-only entry must not show while connected to TEST, got:\n" + console.getOutput());

		console.clear();
		cmd.execute("SCRIPT LIST ALL");
		Assertions.assertTrue(console.getOutput().contains("PRODONLY.sql"),
				"SCRIPT LIST ALL must show every environment, got:\n" + console.getOutput());
	}

	@Test
	void defaultViewHidesAnEntryTaggedForAnotherInstanceButAllShowsIt(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("MYSAPONLY.sql"), "-- @instance: MYSAP\nBYE;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptList cmd = CommandTestSupport.create(CommandScriptList.class, null, console, settings);
		CommandTestSupport.wireInstanceAndEnvironment(cmd, "DB1", "JIRA", null);

		cmd.execute("SCRIPT LIST");
		Assertions.assertFalse(console.getOutput().contains("MYSAPONLY.sql"),
				"a MYSAP-only entry must not show while connected to JIRA, got:\n" + console.getOutput());

		console.clear();
		cmd.execute("SCRIPT LIST ALL");
		Assertions.assertTrue(console.getOutput().contains("MYSAPONLY.sql"),
				"SCRIPT LIST ALL must show every instance, got:\n" + console.getOutput());
	}

	@Test
	void hidesSubfolderEntriesByDefaultButListsSubfoldersShowsThem(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("TOPLEVEL.sql"), "BYE;");
		Files.createDirectories(scriptsDir.resolve("sub"));
		Files.writeString(scriptsDir.resolve("sub/NESTED.sql"), "BYE;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptList cmd = CommandTestSupport.create(CommandScriptList.class, null, console, settings);

		cmd.execute("SCRIPT LIST");
		String output = console.getOutput();
		Assertions.assertTrue(output.contains("TOPLEVEL.sql"), "expected the top-level entry, got:\n" + output);
		Assertions.assertFalse(output.contains("NESTED.sql"), "a sub-folder entry must not show by default, got:\n" + output);
		Assertions.assertTrue(output.contains("ListSubfolders"), "expected a hint about ListSubfolders, got:\n" + output);

		console.clear();
		settings.setListSubfolders(true);
		cmd.execute("SCRIPT LIST");
		output = console.getOutput();
		Assertions.assertTrue(output.contains("TOPLEVEL.sql"), "expected the top-level entry, got:\n" + output);
		Assertions.assertTrue(output.contains("NESTED.sql"), "ListSubfolders=true must show sub-folder entries too, got:\n" + output);
	}

	@Test
	void listsArchivedEntries(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("OLD.sql"), "BYE;");
		new FileCatalog(scriptsDir.toString()).archive("OLD.sql");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptList cmd = CommandTestSupport.create(CommandScriptList.class, null, console, settings);

		cmd.execute("SCRIPT LIST ARCHIVES");

		Assertions.assertTrue(console.getOutput().contains("OLD.sql"), "expected the archived entry, got:\n" + console.getOutput());
	}
}
