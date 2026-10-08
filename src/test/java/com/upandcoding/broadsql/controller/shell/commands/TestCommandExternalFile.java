package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExternalFile;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandExternalFile {

	private DatabaseConnection db;
	private CapturingShellConsole console;
	private ConsoleSettings settings;

	@BeforeEach
	void setUp() throws BroadSQLException {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
		LastQueryResultHolder.set(null);
	}

	@Test
	void runsEveryStatementInTheScript(@TempDir Path dir) throws BroadSQLException, IOException {
		Path script = dir.resolve("script.sql");
		Files.writeString(script, "INSERT INTO CUSTOMER VALUES (1, 'Alice');\nSELECT COUNT(*) FROM CUSTOMER;\n");
		CommandExternalFile cmd = CommandTestSupport.create(CommandExternalFile.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console, db));

		cmd.execute("@" + script);

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("1 row(s) created."), "expected the insert to have run, got:\n" + output);
		Assertions.assertTrue(output.contains("COUNT(*)"), "expected the select's result, got:\n" + output);
	}

	@Test
	void aScriptsSelectPopulatesLastQueryResult(@TempDir Path dir) throws BroadSQLException, IOException {
		db.executeUpdateQuery("INSERT INTO CUSTOMER VALUES (1, 'Alice')");
		Path script = dir.resolve("script.sql");
		Files.writeString(script, "SELECT NAME FROM CUSTOMER;\n");
		CommandExternalFile cmd = CommandTestSupport.create(CommandExternalFile.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console, db));
		LastQueryResultHolder.set(null);

		cmd.execute("@" + script);

		LastQueryResult last = LastQueryResultHolder.get();
		Assertions.assertNotNull(last, "a SELECT run from a script (@file/SCRIPT RUN's shared code path) must populate LastQueryResultHolder");
		Assertions.assertEquals("Alice", last.rows().get(0)[0]);
	}

	@Test
	void aSemicolonInsideAQuotedStringValueDoesNotSplitTheStatement(@TempDir Path dir) throws BroadSQLException, IOException {
		// SPRINT XT02B, section 15: the script-file splitter is now quote-aware (StatementSplitter),
		// fixing a real bug where a semicolon inside a quoted value was mis-split into two statements.
		Path script = dir.resolve("script.sql");
		Files.writeString(script, "INSERT INTO CUSTOMER VALUES (1, 'a;b');\nSELECT COUNT(*) FROM CUSTOMER;\n");
		CommandExternalFile cmd = CommandTestSupport.create(CommandExternalFile.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console, db));

		cmd.execute("@" + script);

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("1 row(s) created."), "expected the insert (with its embedded ';') to run as one statement, got:\n" + output);
		Assertions.assertFalse(output.toLowerCase().contains("error"), "a quoted ';' must not be mis-split into a second, invalid statement:\n" + output);
	}

	@Test
	void reportsAMissingFileNamingTheResolvedPath(@TempDir Path dir) throws BroadSQLException {
		CommandExternalFile cmd = CommandTestSupport.create(CommandExternalFile.class, db, console, settings);
		CommandInterpreter interpreter = CommandTestSupport.createCommandInterpreter(settings, console, db);
		cmd.setConsoleCommandInterpreter(interpreter);
		Path missing = dir.resolve("definitely-does-not-exist.sql");

		// SPRINT 0110A: a Script that cannot be found is a FAILED run (spec 12.5), reported, not thrown
		cmd.execute("@" + missing);

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Script not found") && output.contains(missing.toString()), output);
		Assertions.assertTrue(output.contains(": FAILED (0 statements, 0 failed)"), output);
		Assertions.assertTrue(interpreter.getScriptContext().currentStatement().getExplicitFailed(), "the calling statement failed");
	}

	@Test
	void rejectsAMissingFileNameArgument() {
		CommandExternalFile cmd = CommandTestSupport.create(CommandExternalFile.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console, db));

		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("@"));

		Assertions.assertTrue(e.getMessage().contains("You must provide a script name or path"), e.getMessage());
	}
}
