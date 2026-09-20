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
import com.upandcoding.broadsql.controller.shell.commands.core.scripts.CommandScriptRun;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandScriptRun {

	private DatabaseConnection db;

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void defaultsToScriptsSubfolderWhenNotConfiguredInIni() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandScriptRun cmd = CommandTestSupport.create(CommandScriptRun.class, console);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("SCRIPT RUN DAILY"),
				"expected the default 'scripts' subfolder to be used, and reported missing since it doesn't exist under the test working directory");
		Assertions.assertTrue(ex.getMessage().contains("Invalid path to the scripts catalog"),
				"expected the invalid-path message, got: " + ex.getMessage());
	}

	@Test
	void reportsAFileThatIsNotInTheCatalog(@TempDir Path scriptsDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		CommandScriptRun cmd = CommandTestSupport.create(CommandScriptRun.class, null, console, settings);

		cmd.execute("SCRIPT RUN DOESNOTEXIST");

		Assertions.assertTrue(console.getOutput().contains("The scripts catalog does not contain the requested file"),
				"expected the not-in-catalog message, got:\n" + console.getOutput());
	}

	@Test
	void runsEveryStatementInTheResolvedScript(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		Files.writeString(scriptsDir.resolve("DAILY.sql"), "INSERT INTO CUSTOMER VALUES (1, 'Alice');\nSELECT COUNT(*) FROM CUSTOMER;\n");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandScriptRun cmd = CommandTestSupport.create(CommandScriptRun.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console, db));

		cmd.execute("SCRIPT RUN DAILY");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("1 row(s) created."), "expected the insert to have run, got:\n" + output);
		Assertions.assertTrue(output.contains("COUNT(*)"), "expected the select's result, got:\n" + output);
	}

	@Test
	void resolvesByAlias(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		Files.writeString(scriptsDir.resolve("DAILY.sql"), "-- @alias: dly\nSELECT COUNT(*) FROM CUSTOMER;\n");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandScriptRun cmd = CommandTestSupport.create(CommandScriptRun.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console, db));

		cmd.execute("SCRIPT RUN dly");

		Assertions.assertTrue(console.getOutput().contains("COUNT(*)"), "expected the alias to resolve and run, got:\n" + console.getOutput());
	}

	@Test
	void warnsOnEnvironmentMismatchButStillRuns(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		Files.writeString(scriptsDir.resolve("PRODONLY.sql"), "-- @environment: PROD\nSELECT COUNT(*) FROM CUSTOMER;\n");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandScriptRun cmd = CommandTestSupport.create(CommandScriptRun.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console, db));
		CommandTestSupport.wireEnvironment(cmd, "DB1", "TEST");

		cmd.execute("SCRIPT RUN PRODONLY");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("tagged for environment PROD"), "expected the mismatch warning, got:\n" + output);
		Assertions.assertTrue(output.contains("COUNT(*)"), "the script must still run despite the mismatch, got:\n" + output);
	}

	@Test
	void warnsOnInstanceMismatchButStillRuns(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsPath(scriptsDir.toString());
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		Files.writeString(scriptsDir.resolve("MYSAPONLY.sql"), "-- @instance: MYSAP\nSELECT COUNT(*) FROM CUSTOMER;\n");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandScriptRun cmd = CommandTestSupport.create(CommandScriptRun.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console, db));
		CommandTestSupport.wireInstanceAndEnvironment(cmd, "DB1", "JIRA", null);

		cmd.execute("SCRIPT RUN MYSAPONLY");

		String output2 = console.getOutput();
		Assertions.assertTrue(output2.contains("tagged for instance MYSAP"), "expected the mismatch warning, got:\n" + output2);
		Assertions.assertTrue(output2.contains("COUNT(*)"), "the script must still run despite the mismatch, got:\n" + output2);
	}
}
