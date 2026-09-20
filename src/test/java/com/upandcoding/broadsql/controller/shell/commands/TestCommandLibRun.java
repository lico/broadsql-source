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
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibRun;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandLibRun {

	private DatabaseConnection db;

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void reportsNoLibraryConfigured() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibRun cmd = CommandTestSupport.create(CommandLibRun.class, console);

		cmd.execute("LIB RUN");

		Assertions.assertTrue(console.getOutput().contains("No SQL library folder specified in the INI file"),
				"expected the no-library message, got:\n" + console.getOutput());
	}

	@Test
	void reportsAFileThatIsNotInTheLibrary(@TempDir Path libraryDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibRun cmd = CommandTestSupport.create(CommandLibRun.class, null, console, settings);

		cmd.execute("LIB RUN DOESNOTEXIST");

		Assertions.assertTrue(console.getOutput().contains("The SQL library does not contain the requested query file"),
				"expected the not-in-library message, got:\n" + console.getOutput());
	}

	@Test
	void runsAStoredSelectQuery(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice'), (2, 'Bob')");
		Files.writeString(libraryDir.resolve("COUNT.sql"), "SELECT COUNT(*) FROM CUSTOMER;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibRun cmd = CommandTestSupport.create(CommandLibRun.class, db, console, settings);

		cmd.execute("LIB RUN COUNT");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("2"), "expected the count 2 in output, got:\n" + output);
	}

	@Test
	void substitutesPositionalParameters(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice'), (2, 'Bob')");
		Files.writeString(libraryDir.resolve("BYID.sql"), "SELECT NAME FROM CUSTOMER WHERE ID = %1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibRun cmd = CommandTestSupport.create(CommandLibRun.class, db, console, settings);

		cmd.execute("LIB RUN BYID 1");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Alice"), "expected Alice in output, got:\n" + output);
	}

	@Test
	void reportsMissingParameters(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		Files.writeString(libraryDir.resolve("BYID.sql"), "SELECT NAME FROM CUSTOMER WHERE ID = %1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibRun cmd = CommandTestSupport.create(CommandLibRun.class, db, console, settings);

		cmd.execute("LIB RUN BYID");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("You did not enter enough parameters"),
				"expected the missing-parameters message, got:\n" + output);
	}

	@Test
	void runsAStoredUpdateStatement(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		Files.writeString(libraryDir.resolve("INS.sql"), "INSERT INTO CUSTOMER VALUES (1, 'Alice');");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibRun cmd = CommandTestSupport.create(CommandLibRun.class, db, console, settings);

		cmd.execute("LIB RUN INS");

		db.executeSelectQuery("SELECT COUNT(*) FROM CUSTOMER");
		Assertions.assertTrue(console.getOutput().contains("1"), "expected the row to have been inserted, got:\n" + console.getOutput());
	}

	@Test
	void resolvesByAlias(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice')");
		Files.writeString(libraryDir.resolve("COUNT.sql"), "-- @alias: cnt\nSELECT COUNT(*) FROM CUSTOMER;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibRun cmd = CommandTestSupport.create(CommandLibRun.class, db, console, settings);

		cmd.execute("LIB RUN cnt");

		Assertions.assertTrue(console.getOutput().contains("1"), "expected the alias to resolve and run, got:\n" + console.getOutput());
	}

	@Test
	void warnsOnEnvironmentMismatchButStillRuns(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))", "INSERT INTO CUSTOMER VALUES (1, 'Alice')");
		Files.writeString(libraryDir.resolve("PRODONLY.sql"), "-- @environment: PROD\nSELECT COUNT(*) FROM CUSTOMER;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibRun cmd = CommandTestSupport.create(CommandLibRun.class, db, console, settings);
		CommandTestSupport.wireEnvironment(cmd, "DB1", "TEST");

		cmd.execute("LIB RUN PRODONLY");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("tagged for environment PROD"), "expected the mismatch warning, got:\n" + output);
		Assertions.assertTrue(output.contains("1"), "the query must still run despite the mismatch, got:\n" + output);
	}

	@Test
	void warnsOnInstanceMismatchButStillRuns(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))", "INSERT INTO CUSTOMER VALUES (1, 'Alice')");
		Files.writeString(libraryDir.resolve("MYSAPONLY.sql"), "-- @instance: MYSAP\nSELECT COUNT(*) FROM CUSTOMER;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibRun cmd = CommandTestSupport.create(CommandLibRun.class, db, console, settings);
		CommandTestSupport.wireInstanceAndEnvironment(cmd, "DB1", "JIRA", null);

		cmd.execute("LIB RUN MYSAPONLY");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("tagged for instance MYSAP"), "expected the mismatch warning, got:\n" + output);
		Assertions.assertTrue(output.contains("1"), "the query must still run despite the mismatch, got:\n" + output);
	}

	@Test
	void reportsTooManySuppliedParametersButStillRuns(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice')");
		Files.writeString(libraryDir.resolve("BYID.sql"), "SELECT NAME FROM CUSTOMER WHERE ID = %1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibRun cmd = CommandTestSupport.create(CommandLibRun.class, db, console, settings);

		cmd.execute("LIB RUN BYID 1 EXTRA");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("extra parameter(s) ignored"), "expected the over-supply message, got:\n" + output);
		Assertions.assertTrue(output.contains("Alice"), "the query must still run using the parameters it does declare, got:\n" + output);
	}
}
