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
	void reportsAMissingFile() {
		CommandExternalFile cmd = CommandTestSupport.create(CommandExternalFile.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console, db));

		Assertions.assertDoesNotThrow(() -> cmd.execute("@C:\\definitely\\does\\not\\exist.sql"));

		Assertions.assertTrue(console.getOutput().contains("File does not exist"),
				"expected the missing-file error, got:\n" + console.getOutput());
	}

	@Test
	void rejectsAMissingFileNameArgument() {
		CommandExternalFile cmd = CommandTestSupport.create(CommandExternalFile.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console, db));

		Assertions.assertDoesNotThrow(() -> cmd.execute("@"));

		Assertions.assertTrue(console.getOutput().contains("You must provide a file name"),
				"expected the missing-file-name error, got:\n" + console.getOutput());
	}
}
