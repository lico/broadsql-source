package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.scripts.CommandScriptShow;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandScriptShow {

	@Test
	void defaultsToScriptsSubfolderWhenNotConfiguredInIni() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandScriptShow cmd = CommandTestSupport.create(CommandScriptShow.class, console);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("SCRIPT SHOW DAILY"),
				"expected the default 'scripts' subfolder to be used, and reported missing since it doesn't exist under the test working directory");
		Assertions.assertTrue(ex.getMessage().contains("Invalid path to the scripts catalog"),
				"expected the invalid-path message, got: " + ex.getMessage());
	}

	@Test
	void reportsAFileThatIsNotInTheCatalog(@TempDir Path scriptsDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptShow cmd = CommandTestSupport.create(CommandScriptShow.class, null, console, settings);

		cmd.execute("SCRIPT SHOW DOESNOTEXIST");

		Assertions.assertTrue(console.getOutput().contains("The scripts catalog does not contain the requested file"),
				"expected the not-in-catalog message, got:\n" + console.getOutput());
	}

	@Test
	void showsTheContentOfAnExistingFile(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("DAILY.sql"), "CONNECT DBHR;\nDUMP MATABLE;\nBYE;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptShow cmd = CommandTestSupport.create(CommandScriptShow.class, null, console, settings);

		cmd.execute("SCRIPT SHOW DAILY");

		Assertions.assertTrue(console.getOutput().contains("DUMP MATABLE;"), "expected the file content, got:\n" + console.getOutput());
	}

	@Test
	void warnsOnInstanceMismatchButStillShowsContent(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("MYSAPONLY.sql"), "-- @instance: MYSAP\nBYE;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptShow cmd = CommandTestSupport.create(CommandScriptShow.class, null, console, settings);
		CommandTestSupport.wireInstanceAndEnvironment(cmd, "DB1", "JIRA", null);

		cmd.execute("SCRIPT SHOW MYSAPONLY");

		String output2 = console.getOutput();
		Assertions.assertTrue(output2.contains("tagged for instance MYSAP"), "expected the mismatch warning, got:\n" + output2);
		Assertions.assertTrue(output2.contains("BYE;"), "the content must still be shown despite the mismatch, got:\n" + output2);
	}

	@Test
	void warnsOnEnvironmentMismatchButStillShowsContent(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("PRODONLY.sql"), "-- @environment: PROD\nBYE;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptShow cmd = CommandTestSupport.create(CommandScriptShow.class, null, console, settings);
		CommandTestSupport.wireEnvironment(cmd, "DB1", "TEST");

		cmd.execute("SCRIPT SHOW PRODONLY");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("tagged for environment PROD"), "expected the mismatch warning, got:\n" + output);
		Assertions.assertTrue(output.contains("BYE;"), "the content must still be shown despite the mismatch, got:\n" + output);
	}
}
