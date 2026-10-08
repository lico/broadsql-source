package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.miachm.sods.SpreadSheet;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * GitHub #152 - end-to-end coverage of {@code CommandPull}'s optional {@code AS <format>} and optional
 * {@code .<destination>}, wired through the real {@code DefaultFileFormat} INI setting via
 * {@link ConsoleSettings} (unlike {@link com.upandcoding.broadsql.dao.pull.TestPullCommandParserDefaults},
 * which drives {@code PullCommandParser} directly with a caller-supplied token). Sibling to
 * {@link TestCommandPullSpreadsheet} for file assertions.
 */
class TestCommandPullDefaults {

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

	private CommandPull pullCommand(Path tempDir, String defaultFileFormatRaw) {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		if (defaultFileFormatRaw != null) {
			settings.setDefaultFileFormatRaw(defaultFileFormatRaw);
		}
		return CommandTestSupport.create(CommandPull.class, db, console, settings);
	}

	@Test
	void implicitFormatAndImplicitSheetUseTheConfiguredDefaultAndData(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir, "XLSX");

		cmd.execute("PULL CUSTOMER TO REPORT");

		File file = tempDir.resolve("REPORT.xlsx").toFile();
		Assertions.assertTrue(file.exists(), "expected REPORT.xlsx, got console:\n" + console.getOutput());
		try (FileInputStream in = new FileInputStream(file); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Sheet data = wb.getSheet("DATA");
			Assertions.assertNotNull(data, "expected a DATA tab");
			Assertions.assertEquals(2, data.getLastRowNum()); // header + 2 rows -> last row index 2
		}
	}

	@Test
	void implicitSheetWithExplicitFormatDefaultsToData(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir, "ODS"); // configured default irrelevant - AS is explicit

		cmd.execute("PULL CUSTOMER TO REPORT AS XLSX");

		File file = tempDir.resolve("REPORT.xlsx").toFile();
		Assertions.assertTrue(file.exists());
		try (FileInputStream in = new FileInputStream(file); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Assertions.assertNotNull(wb.getSheet("DATA"));
		}
	}

	@Test
	void explicitSheetWithImplicitFormatUsesTheConfiguredDefault(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir, "XLSX");

		cmd.execute("PULL CUSTOMER TO REPORT.MYSHEET");

		File file = tempDir.resolve("REPORT.xlsx").toFile();
		Assertions.assertTrue(file.exists());
		try (FileInputStream in = new FileInputStream(file); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Assertions.assertNotNull(wb.getSheet("MYSHEET"));
		}
	}

	@Test
	void explicitFormatOverridesTheConfiguredDefault(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir, "XLSX");

		cmd.execute("PULL CUSTOMER TO REPORT AS ODS");

		File file = tempDir.resolve("REPORT.ods").toFile();
		Assertions.assertTrue(file.exists());
		Assertions.assertFalse(tempDir.resolve("REPORT.xlsx").toFile().exists());
		SpreadSheet spreadSheet = new SpreadSheet(file);
		Assertions.assertNotNull(spreadSheet.getSheet("DATA"));
	}

	@Test
	void anAbsentDefaultFileFormatSilentlyFallsBackToOdsExactlyLikeDumpAndExport(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir, null); // DefaultFileFormat never set in the INI

		cmd.execute("PULL CUSTOMER TO REPORT");

		Assertions.assertTrue(tempDir.resolve("REPORT.ods").toFile().exists(),
				"expected the existing ODS fallback, got console:\n" + console.getOutput());
	}

	@Test
	void aPresentButUnrecognizedDefaultFileFormatFailsClearlyWhenAsIsOmitted(@TempDir Path tempDir) {
		CommandPull cmd = pullCommand(tempDir, "BOGUS");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("PULL CUSTOMER TO REPORT"));

		Assertions.assertTrue(ex.getMessage().contains("DefaultFileFormat"), "got: " + ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("BOGUS"), "got: " + ex.getMessage());
		Assertions.assertFalse(tempDir.resolve("REPORT.ods").toFile().exists(), "nothing should have been written");
	}

	@Test
	void aPresentButUnrecognizedDefaultFileFormatDoesNotBlockAnExplicitAs(@TempDir Path tempDir) throws Exception {
		CommandPull cmd = pullCommand(tempDir, "BOGUS");

		cmd.execute("PULL CUSTOMER TO REPORT AS XLSX");

		File file = tempDir.resolve("REPORT.xlsx").toFile();
		Assertions.assertTrue(file.exists(), "an explicit AS <format> must still work despite an invalid default, got console:\n" + console.getOutput());
	}

	@Test
	void implicitDestinationAlsoDefaultsToDataForAnH2Target(@TempDir Path tempDir) throws BroadSQLException, SQLException {
		CommandPull cmd = pullCommand(tempDir, "XLSX"); // irrelevant here - AS H2 is explicit
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		DatabaseDefinition targetDef = TestDatabaseConnections.newInMemoryTarget("TARGETDB");
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("TARGETDB", targetDef);
		vault.setDatabaseConnections(connections);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("PULL CUSTOMER TO TARGETDB AS H2");

		Assertions.assertTrue(console.getOutput().contains("2 row(s) pulled into TARGETDB.DATA"),
				"expected the DATA table default, got console:\n" + console.getOutput());
		try (Connection targetConn = DriverManager.getConnection(targetDef.getUrl());
				Statement stmt = targetConn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM \"DATA\"")) {
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals(2, rs.getInt(1));
		}
	}
}
