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
 * Covers {@code CommandPull}'s {@code AS JSON}/{@code AS MD}/{@code AS HTML} destinations end to end
 * (see docs/PULL_TO_TEXT.md), through the real {@code PULL} command.
 */
class TestCommandPullFlatFormats {

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

	private CommandPull pullCommand(Path tempDir) {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		return CommandTestSupport.create(CommandPull.class, db, console, settings);
	}

	private String readFile(File file) throws Exception {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	@Test
	void pullsANewJsonFile(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir);

		cmd.execute("PULL CUSTOMER TO REPORT_CUSTOMERS AS JSON");

		File file = tempDir.resolve("REPORT_CUSTOMERS.json").toFile();
		Assertions.assertTrue(file.exists(), "expected REPORT_CUSTOMERS.json to be created, got console:\n" + console.getOutput());
		String content = readFile(file);
		Assertions.assertTrue(content.contains("\"NAME\": \"Alice\""), "got:\n" + content);
		Assertions.assertTrue(console.getOutput().contains("2 row(s) pulled into"), "got:\n" + console.getOutput());
	}

	@Test
	void pullsANewMarkdownFile(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir);

		cmd.execute("PULL CUSTOMER TO REPORT_CUSTOMERS AS MD");

		File file = tempDir.resolve("REPORT_CUSTOMERS.md").toFile();
		Assertions.assertTrue(file.exists());
		String content = readFile(file);
		Assertions.assertTrue(content.contains("| ID | NAME |"), "got:\n" + content);
	}

	@Test
	void pullsANewHtmlFile(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir);

		cmd.execute("PULL CUSTOMER TO REPORT_CUSTOMERS AS HTML");

		File file = tempDir.resolve("REPORT_CUSTOMERS.html").toFile();
		Assertions.assertTrue(file.exists());
		String content = readFile(file);
		Assertions.assertTrue(content.trim().startsWith("<table>"), "got:\n" + content);
		Assertions.assertFalse(content.contains("<html"), "expected a bare fragment, no <html> wrapper");
	}

	@Test
	void aSecondPullFullyOverwritesEachFlatFormat(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir);

		cmd.execute("PULL CUSTOMER TO REPORT_CUSTOMERS AS JSON");
		cmd.execute("PULL (SELECT ID, NAME FROM CUSTOMER WHERE ID = 1) TO REPORT_CUSTOMERS AS JSON");

		String content = readFile(tempDir.resolve("REPORT_CUSTOMERS.json").toFile());
		Assertions.assertFalse(content.contains("Bob"), "expected the second, smaller pull to fully replace the file - Bob must be gone");
	}

	@Test
	void rejectsADottedDestinationForJson() {
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, db, console);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> cmd.execute("PULL CUSTOMER TO REPORT.CUSTOMERS AS JSON"));

		Assertions.assertTrue(ex.getMessage().contains("no dot"), "got: " + ex.getMessage());
	}
}
