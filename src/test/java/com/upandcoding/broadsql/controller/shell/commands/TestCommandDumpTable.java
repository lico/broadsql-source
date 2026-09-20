package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandDumpTable;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandDumpTable {

	private DatabaseConnection db;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice'), (2, 'Bob')");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void extractsTheFullTableToAFile(@TempDir Path tempDir) throws BroadSQLException {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		CommandDumpTable cmd = CommandTestSupport.create(CommandDumpTable.class, db, console, settings);

		cmd.execute("DUMP CUSTOMER");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("2 records extracted"), "expected the extraction summary, got:\n" + output);
		File extracted = tempDir.resolve("CUSTOMER.ods").toFile();
		Assertions.assertTrue(extracted.exists(), "expected the extracted file to exist: " + extracted);
	}

	@Test
	void reportsAnErrorForATableThatDoesNotExist() {
		CommandDumpTable cmd = CommandTestSupport.create(CommandDumpTable.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DUMP DOESNOTEXIST"));

		Assertions.assertTrue(console.getOutput().contains("does not exist"),
				"expected the table-not-found error, got:\n" + console.getOutput());
	}

	@Test
	void rejectsAMissingTableName() {
		CommandDumpTable cmd = CommandTestSupport.create(CommandDumpTable.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DUMP"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_GAL_01),
				"expected the missing-table-name error, got:\n" + console.getOutput());
	}
}
