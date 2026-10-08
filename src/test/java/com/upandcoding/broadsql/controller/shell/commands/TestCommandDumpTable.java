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

	// ---- GitHub #154, section 7 ("feature-local failure"): DUMP is the one feature that actually needs
	// MaxRowXLSX, so an invalid value must fail DUMP specifically and clearly, without ever reaching a
	// silently-wrong threshold decision or a raw NumberFormatException. ----

	// Built directly (not via TestDatabaseConnections#defaultConsoleSettings, which calls
	// setMaxRowXlsx(int) - the explicit-override setter that would make setMaxRowXlsxRaw a no-op here).
	private ConsoleSettings settingsWithInvalidMaxRowXlsx(Path tempDir) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setAutoCommit(true);
		settings.setDefaultSeparator('\t');
		settings.setOnScreenSeparator('|');
		settings.setMaxRowsOnScreen(1000);
		settings.setMaxRowXlsxRaw("format");
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		return settings;
	}

	@Test
	void anInvalidMaxRowXlsxFailsDumpWithAClearConfigurationErrorRatherThanAnImplementationException(@TempDir Path tempDir) {
		ConsoleSettings settings = settingsWithInvalidMaxRowXlsx(tempDir);
		CommandDumpTable cmd = CommandTestSupport.create(CommandDumpTable.class, db, console, settings);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("DUMP CUSTOMER"));

		Assertions.assertTrue(ex.getMessage().contains("MaxRowXLSX"), "got: " + ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("format"), "got: " + ex.getMessage());
		Assertions.assertFalse(tempDir.resolve("CUSTOMER.ods").toFile().exists(), "nothing should have been extracted");
		Assertions.assertFalse(tempDir.resolve("CUSTOMER.txt").toFile().exists(), "nothing should have been extracted");
	}

	@Test
	void anInvalidMaxRowXlsxDoesNotPreventOrdinarySqlOnTheSameConnection(@TempDir Path tempDir) throws BroadSQLException, java.sql.SQLException {
		// The central requirement, proven at the feature level, not just at ConsoleSettings' own level
		// (see TestConsoleSettingsIniResilience for the Spring-context-wide proof): an invalid setting
		// disables only what actually depends on it.
		ConsoleSettings settings = settingsWithInvalidMaxRowXlsx(tempDir);
		db.setMaxRowsOnScreen(settings.getMaxRowsOnScreen());

		try (java.sql.Statement stmt = db.getDirectConnection().createStatement();
				java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM CUSTOMER")) {
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals(2, rs.getInt(1));
		}
	}

	@Test
	void aValidMaxRowXlsxRawValueContinuesToWorkExactlyAsBefore(@TempDir Path tempDir) throws BroadSQLException {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setAutoCommit(true);
		settings.setDefaultSeparator('\t');
		settings.setOnScreenSeparator('|');
		settings.setMaxRowsOnScreen(1000);
		settings.setMaxRowXlsxRaw("500000"); // parsed via the real INI-resolution path, not setMaxRowXlsx(int)
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		CommandDumpTable cmd = CommandTestSupport.create(CommandDumpTable.class, db, console, settings);

		Assertions.assertDoesNotThrow(() -> cmd.execute("DUMP CUSTOMER"));

		Assertions.assertTrue(tempDir.resolve("CUSTOMER.ods").toFile().exists());
		Assertions.assertNull(settings.getMaxRowXlsxInvalidValue());
	}
}
