package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * Covers {@code CommandPull}'s {@code AS CSV}/{@code AS TXT} destination end to end (see
 * docs/PULL_TO_TEXT.md): the default and custom {@code CsvSeparator}, {@code AS TXT}'s fixed tab
 * separator, and that a second pull to the same name fully overwrites the file rather than appending
 * or leaving stale rows behind.
 */
class TestCommandPullText {

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

	private CommandPull pullCommand(Path tempDir, ConsoleSettings settings) {
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		return CommandTestSupport.create(CommandPull.class, db, console, settings);
	}

	private String readFile(File file) throws Exception {
		String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		return content.startsWith("﻿") ? content.substring(1) : content;
	}

	@Test
	void pullsANewCsvFileWithTheDefaultSemicolonSeparator(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir, TestDatabaseConnections.defaultConsoleSettings());

		cmd.execute("PULL CUSTOMER TO REPORT_CUSTOMERS AS CSV");

		File file = tempDir.resolve("REPORT_CUSTOMERS.csv").toFile();
		Assertions.assertTrue(file.exists(), "expected REPORT_CUSTOMERS.csv to be created, got console:\n" + console.getOutput());
		String content = readFile(file);
		Assertions.assertTrue(content.contains("ID;NAME"), "expected the default semicolon separator, got:\n" + content);
		Assertions.assertTrue(console.getOutput().contains("2 row(s) pulled into"), "got:\n" + console.getOutput());
	}

	@Test
	void pullsANewCsvFileWithACustomSeparator(@TempDir Path tempDir) throws Exception {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setCsvSeparator(',');
		CommandPull cmd = pullCommand(tempDir, settings);

		cmd.execute("PULL CUSTOMER TO REPORT_CUSTOMERS AS CSV");

		String content = readFile(tempDir.resolve("REPORT_CUSTOMERS.csv").toFile());
		Assertions.assertTrue(content.contains("ID,NAME"), "expected the configured comma separator, got:\n" + content);
	}

	@Test
	void pullsANewTxtFileWithATabSeparator(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir, TestDatabaseConnections.defaultConsoleSettings());

		cmd.execute("PULL CUSTOMER TO REPORT_CUSTOMERS AS TXT");

		File file = tempDir.resolve("REPORT_CUSTOMERS.txt").toFile();
		Assertions.assertTrue(file.exists());
		String content = readFile(file);
		Assertions.assertTrue(content.contains("ID\tNAME"), "expected a tab separator regardless of CsvSeparator, got:\n" + content);
	}

	@Test
	void aSecondPullFullyOverwritesTheFile(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir, TestDatabaseConnections.defaultConsoleSettings());

		cmd.execute("PULL CUSTOMER TO REPORT_CUSTOMERS AS CSV");
		cmd.execute("PULL (SELECT ID, NAME FROM CUSTOMER WHERE ID = 1) TO REPORT_CUSTOMERS AS CSV");

		String content = readFile(tempDir.resolve("REPORT_CUSTOMERS.csv").toFile());
		Assertions.assertFalse(content.contains("Bob"), "expected the second, smaller pull to fully replace the file - Bob must be gone");
		Assertions.assertTrue(content.contains("Alice"), "expected Alice, from the second pull, still present");
	}

	@Test
	void rejectsADottedDestination() {
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, db, console);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> cmd.execute("PULL CUSTOMER TO REPORT.CUSTOMERS AS CSV"));

		Assertions.assertTrue(ex.getMessage().contains("no dot"), "got: " + ex.getMessage());
	}
}
