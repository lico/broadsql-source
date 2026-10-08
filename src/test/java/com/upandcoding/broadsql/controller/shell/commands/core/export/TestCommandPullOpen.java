package com.upandcoding.broadsql.controller.shell.commands.core.export;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.CommandTestSupport;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * Covers {@code PULL ... AS XLSX/ODS OPEN} (see docs/PULL_TO_SPREADSHEET.md, "OPEN") - the OPEN
 * keyword's execution semantics, exercised through {@link CommandPull#fileOpener}, a fake substituted
 * here instead of ever launching a real desktop application (per this feature's own test requirements -
 * see docs/TECHNICAL_CHANGE.md). Lives in this package (rather than alongside
 * {@code TestCommandPullSpreadsheet}) specifically so it can reach {@link CommandPull#fileOpener}, which
 * is package-private on purpose.
 */
class TestCommandPullOpen {

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

	/** Records every file it was asked to open - never actually launches anything. */
	private static final class RecordingFileOpener implements FileOpener {
		final List<File> opened = new ArrayList<>();

		@Override
		public void open(File file) {
			opened.add(file);
		}
	}

	/** Always fails, as if desktop integration were unavailable. */
	private static final class FailingFileOpener implements FileOpener {
		@Override
		public void open(File file) throws IOException {
			throw new IOException("no desktop integration available in this environment");
		}
	}

	@Test
	void plainExportWithoutOpenNeverCallsTheFileOpener(@TempDir Path tempDir) {
		CommandPull cmd = pullCommand(tempDir);
		RecordingFileOpener opener = new RecordingFileOpener();
		cmd.fileOpener = opener;

		Assertions.assertDoesNotThrow(() -> cmd.execute("PULL CUSTOMER TO REPORT.CUSTOMERS AS XLSX"));

		Assertions.assertTrue(opener.opened.isEmpty(), "OPEN was not requested - the file opener must never be called");
	}

	@Test
	void exportWithOpenCallsTheFileOpenerWithTheExportedFile(@TempDir Path tempDir) {
		CommandPull cmd = pullCommand(tempDir);
		RecordingFileOpener opener = new RecordingFileOpener();
		cmd.fileOpener = opener;

		Assertions.assertDoesNotThrow(() -> cmd.execute("PULL CUSTOMER TO REPORT.CUSTOMERS AS XLSX OPEN"));

		Assertions.assertEquals(1, opener.opened.size(), "expected exactly one open request");
		File expected = tempDir.resolve("REPORT.xlsx").toFile();
		Assertions.assertEquals(expected.getAbsoluteFile(), opener.opened.get(0).getAbsoluteFile());
	}

	@Test
	void exportWithOpenOnOdsAlsoCallsTheFileOpener(@TempDir Path tempDir) {
		CommandPull cmd = pullCommand(tempDir);
		RecordingFileOpener opener = new RecordingFileOpener();
		cmd.fileOpener = opener;

		Assertions.assertDoesNotThrow(() -> cmd.execute("PULL CUSTOMER TO REPORT.CUSTOMERS AS ODS OPEN"));

		Assertions.assertEquals(1, opener.opened.size(), "expected exactly one open request");
		File expected = tempDir.resolve("REPORT.ods").toFile();
		Assertions.assertEquals(expected.getAbsoluteFile(), opener.opened.get(0).getAbsoluteFile());
	}

	@Test
	void aFailedExportNeverInvokesOpen(@TempDir Path tempDir) {
		CommandPull cmd = pullCommand(tempDir);
		RecordingFileOpener opener = new RecordingFileOpener();
		cmd.fileOpener = opener;

		// An invalid tab name (contains '/') makes the parser itself reject the statement before any
		// export is attempted - the export never runs, so OPEN must never be requested either.
		Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("PULL CUSTOMER TO REPORT.BAD/NAME AS XLSX OPEN"));

		Assertions.assertTrue(opener.opened.isEmpty(), "a rejected/failed PULL must never invoke OPEN");
	}

	@Test
	void aFailedOpenDoesNotInvalidateTheSuccessfulExport(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir);
		cmd.fileOpener = new FailingFileOpener();

		Assertions.assertDoesNotThrow(() -> cmd.execute("PULL CUSTOMER TO REPORT.CUSTOMERS AS XLSX OPEN"));

		File file = tempDir.resolve("REPORT.xlsx").toFile();
		Assertions.assertTrue(file.exists(), "the exported file must still exist even though OPEN failed");
		String output = console.getOutput();
		Assertions.assertTrue(output.contains("row(s) pulled into tab"), "expected the normal success message, got:\n" + output);
		Assertions.assertTrue(output.contains("could not be opened"), "expected a secondary OPEN-failure warning, got:\n" + output);
		Assertions.assertTrue(output.contains("no desktop integration available"), "expected the underlying reason in the warning, got:\n" + output);
	}

	@Test
	void openIsRejectedForFormatsOtherThanXlsxAndOds(@TempDir Path tempDir) {
		CommandPull cmd = pullCommand(tempDir);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> cmd.execute("PULL CUSTOMER TO REPORT_CUSTOMERS AS CSV OPEN"));
		Assertions.assertTrue(ex.getMessage().contains("Unexpected text"), "expected a parse error naming the unexpected OPEN, got: " + ex.getMessage());
	}
}
