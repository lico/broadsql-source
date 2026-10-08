package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.scripting.CommandJsRun;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandJsRun {

	private DatabaseConnection db;

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void defaultsToJsscriptsSubfolderWhenNotConfiguredInIni() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandJsRun cmd = CommandTestSupport.create(CommandJsRun.class, console);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("JS RUN DAILY.js"),
				"expected the default 'jsscripts' subfolder to be used, and reported missing since it doesn't exist under the test working directory");
		Assertions.assertTrue(ex.getMessage().contains("Invalid path to the JS scripts catalog"),
				"expected the invalid-path message, got: " + ex.getMessage());
	}

	@Test
	void reportsAFileThatIsNotInTheCatalog(@TempDir Path jsScriptsDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setJsScriptsPath(jsScriptsDir.toString());
		CommandJsRun cmd = CommandTestSupport.create(CommandJsRun.class, null, console, settings);

		cmd.execute("JS RUN DOESNOTEXIST.js");

		Assertions.assertTrue(console.getOutput().contains("The JS scripts catalog does not contain the requested file"),
				"expected the not-in-catalog message, got:\n" + console.getOutput());
	}

	@Test
	void extensionOptionalConventionDoesNotApplyToJsFiles(@TempDir Path jsScriptsDir) throws BroadSQLException, IOException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setJsScriptsPath(jsScriptsDir.toString());
		Files.writeString(jsScriptsDir.resolve("DAILY.js"), "println('ran');\n");
		CommandJsRun cmd = CommandTestSupport.create(CommandJsRun.class, null, console, settings);

		// Unlike SCRIPT RUN/LIB RUN, a bare name with no extension does not auto-resolve to .js -
		// FileCatalog's own .sql auto-append is deliberately left untouched (see CommandJsRun's Javadoc).
		cmd.execute("JS RUN DAILY");

		Assertions.assertTrue(console.getOutput().contains("The JS scripts catalog does not contain the requested file"),
				"expected the extension-less name to NOT resolve, got:\n" + console.getOutput());
	}

	@Test
	void runsTheResolvedScriptAgainstTheActiveConnection(@TempDir Path jsScriptsDir) throws BroadSQLException, IOException {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setJsScriptsPath(jsScriptsDir.toString());
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		Files.writeString(jsScriptsDir.resolve("DAILY.js"),
				"db.executeUpdate(\"INSERT INTO CUSTOMER VALUES (1, 'Alice')\");\n"
						+ "var rows = db.execute('SELECT COUNT(*) AS CNT FROM CUSTOMER');\n"
						+ "println('COUNT=' + rows.get(0)['CNT']);\n");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandJsRun cmd = CommandTestSupport.create(CommandJsRun.class, db, console, settings);

		cmd.execute("JS RUN DAILY.js");

		Assertions.assertTrue(console.getOutput().contains("COUNT=1"), "expected the script to have run, got:\n" + console.getOutput());
	}

	@Test
	void passesTrailingTokensAsArgs(@TempDir Path jsScriptsDir) throws BroadSQLException, IOException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setJsScriptsPath(jsScriptsDir.toString());
		Files.writeString(jsScriptsDir.resolve("GREET.js"), "println(args[0] + '-' + args[1]);\n");
		CommandJsRun cmd = CommandTestSupport.create(CommandJsRun.class, null, console, settings);

		cmd.execute("JS RUN GREET.js hello world");

		Assertions.assertTrue(console.getOutput().contains("hello-world"), "expected args to be bound, got:\n" + console.getOutput());
	}

	@Test
	void resolvesByAlias(@TempDir Path jsScriptsDir) throws BroadSQLException, IOException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setJsScriptsPath(jsScriptsDir.toString());
		Files.writeString(jsScriptsDir.resolve("DAILY.js"), "-- @alias: dly\nprintln('ran via alias');\n");
		CommandJsRun cmd = CommandTestSupport.create(CommandJsRun.class, null, console, settings);

		cmd.execute("JS RUN dly");

		Assertions.assertTrue(console.getOutput().contains("ran via alias"), "expected the alias to resolve and run, got:\n" + console.getOutput());
	}

	@Test
	void warnsOnEnvironmentMismatchButStillRuns(@TempDir Path jsScriptsDir) throws BroadSQLException, IOException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setJsScriptsPath(jsScriptsDir.toString());
		Files.writeString(jsScriptsDir.resolve("PRODONLY.js"), "-- @environment: PROD\nprintln('ran despite mismatch');\n");
		CommandJsRun cmd = CommandTestSupport.create(CommandJsRun.class, null, console, settings);
		CommandTestSupport.wireEnvironment(cmd, "DB1", "TEST");

		cmd.execute("JS RUN PRODONLY.js");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("tagged for environment PROD"), "expected the mismatch warning, got:\n" + output);
		Assertions.assertTrue(output.contains("ran despite mismatch"), "the script must still run despite the mismatch, got:\n" + output);
	}
}
