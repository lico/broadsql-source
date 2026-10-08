package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandDumpTable;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandExport;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * SPRINT 1005C (#169): the Connections Definition File is a protected system database. Against a real,
 * file-backed, AES-encrypted CDF registered as {@code $CDF}, every generic write path ({@code DUMP} and
 * {@code PULL ... AS H2} in every mode, {@code EXPORT <file>}) is refused before anything is opened or written,
 * by name and by physical database (an alias connection, a differently written URL); the CDF keeps its tables
 * and rows and still loads; ordinary H2 destinations and BroadSQL's own CDF management keep working.
 */
class TestCommandDumpCdfProtection {

	private static final String PROTECTED = "a protected BroadSQL system database";

	@TempDir
	Path tmp;

	private DatabaseDefinitionsVault vault;
	private String cdfPath;
	private DatabaseConnection sourceDb;
	private CapturingShellConsole console;
	private ConsoleSettings settings;

	@BeforeEach
	void setUp() throws Exception {
		vault = TestDatabaseConnections.newFileBackedVault();
		cdfPath = vault.getFileName();
		TestDatabaseConnections.insertConnectionBypassingValidation(vault, h2(SpringPropertiesConfig.CDF_ID, "jdbc:h2:" + cdfPath + ";CIPHER=AES"));
		// The same physical file, written differently: file: prefix, a ./ segment, other settings
		File cdfFile = new File(cdfPath);
		String aliasPath = cdfFile.getParent() + File.separator + "." + File.separator + cdfFile.getName();
		TestDatabaseConnections.insertConnectionBypassingValidation(vault, h2("MY_ALIAS", "jdbc:h2:file:" + aliasPath + ";IFEXISTS=TRUE;CIPHER=AES"));
		TestDatabaseConnections.insertConnectionBypassingValidation(vault,
				h2("OTHER_H2", "jdbc:h2:" + tmp.resolve("other").toString() + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"));

		sourceDb = TestDatabaseConnections.connectInMemory("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(20))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice')");
		console = new CapturingShellConsole();
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tmp.toString() + File.separator);
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(sourceDb);
		for (String suffix : new String[] { ".mv.db", ".trace.db" }) {
			new File(cdfPath + suffix).delete();
		}
	}

	private static DatabaseDefinition h2(String id, String url) {
		DatabaseDefinition def = new DatabaseDefinition(id);
		def.setDbType(SpringPropertiesConfig.DBTYPE_H2);
		def.setDbDriver("org.h2.Driver");
		def.setDbName(id);
		def.setUrl(url);
		def.setUserName("ADMIN");
		def.setUserPassword("testpwd testpwd");
		return def;
	}

	private void dump(String line) throws BroadSQLException {
		CommandDumpTable cmd = CommandTestSupport.create(CommandDumpTable.class, sourceDb, console, settings);
		cmd.setDatabaseConnectionsVault(vault);
		cmd.execute(line);
	}

	private void pull(String line) throws BroadSQLException {
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, sourceDb, console, settings);
		cmd.setDatabaseConnectionsVault(vault);
		cmd.execute(line);
	}

	/** The CDF's tables and its CONNECTIONS rows, read directly from the encrypted file. */
	private List<String> cdfSnapshot() throws SQLException {
		List<String> snapshot = new ArrayList<>();
		try (Connection conn = DriverManager.getConnection("jdbc:h2:" + cdfPath + ";CIPHER=AES;IFEXISTS=TRUE", "ADMIN", "testpwd testpwd");
				Statement stmt = conn.createStatement()) {
			try (ResultSet rs = stmt.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY 1")) {
				while (rs.next()) {
					snapshot.add("table " + rs.getString(1));
				}
			}
			try (ResultSet rs = stmt.executeQuery("SELECT ID, URL, STATUS_ID FROM CONNECTIONS ORDER BY ID")) {
				while (rs.next()) {
					snapshot.add(rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3));
				}
			}
		}
		return snapshot;
	}

	private void assertRefused(Executable action) throws Exception {
		List<String> before = cdfSnapshot();
		byte[] fileBefore = Files.readAllBytes(Path.of(cdfPath + ".mv.db"));
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, action::run);
		Assertions.assertTrue(ex.getMessage().contains(PROTECTED), "got: " + ex.getMessage());
		Assertions.assertFalse(ex.getMessage().contains(cdfPath), "the message must not expose the CDF's location: " + ex.getMessage());
		Assertions.assertFalse(ex.getMessage().contains("testpwd"), "the message must not expose a password");
		// Bytes first: reading the snapshot opens the database, which H2 itself rewrites on open and close
		Assertions.assertArrayEquals(fileBefore, Files.readAllBytes(Path.of(cdfPath + ".mv.db")), "the CDF file must not even be opened for writing");
		Assertions.assertEquals(before, cdfSnapshot(), "the CDF must be unchanged");
	}

	@FunctionalInterface
	private interface Executable {
		void run() throws Exception;
	}

	// =================================================================================================
	// Refused
	// =================================================================================================

	@Test
	void dumpToTheCdfIsRefused() throws Exception {
		assertRefused(() -> dump("DUMP (SELECT 1 AS X) TO $CDF.CONNECTIONS AS H2"));
		assertRefused(() -> dump("DUMP (SELECT 1 AS X) TO $CDF.NEW_TABLE AS H2"));
	}

	@Test
	void legacyPullToTheCdfIsRefused() throws Exception {
		assertRefused(() -> pull("PULL (SELECT 1 AS X) TO $CDF.CONNECTIONS AS H2"));
		assertRefused(() -> pull("PULL CUSTOMER TO $CDF.CUSTOMER AS H2"));
	}

	@Test
	void everyAppendModeIsRefusedToo() throws Exception {
		assertRefused(() -> dump("DUMP (SELECT 'X' AS ID, 'u' AS URL) TO $CDF.CONNECTIONS AS H2 MODE APPEND"));
		assertRefused(() -> dump("DUMP CUSTOMER TO $CDF.CONNECTIONS AS H2 MODE APPEND KEY(ID)"));
	}

	@Test
	void anAliasConnectionToTheSameDatabaseIsRefused() throws Exception {
		assertRefused(() -> dump("DUMP (SELECT 1 AS X) TO MY_ALIAS.CONNECTIONS AS H2"));
		assertRefused(() -> pull("PULL (SELECT 1 AS X) TO MY_ALIAS.T AS H2 MODE APPEND"));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> dump("DUMP (SELECT 1 AS X) TO MY_ALIAS.T AS H2"));
		Assertions.assertTrue(ex.getMessage().startsWith("DUMP refused: connection 'MY_ALIAS' is the BroadSQL Connections Definition File ($CDF)"),
				ex.getMessage());
	}

	@Test
	void theCdfNameInAnotherCaseIsRefusedAndNotRegistered() throws Exception {
		assertRefused(() -> dump("DUMP (SELECT 1 AS X) TO $cdf.T AS H2"));

		vault.load();
		Assertions.assertFalse(vault.contains("$cdf"), "no connection may have been registered");
	}

	@Test
	void exportToTheCdfFileIsRefused() throws Exception {
		CommandExport export = CommandTestSupport.create(CommandExport.class, sourceDb, console, settings);
		export.setDatabaseConnectionsVault(vault);

		assertRefused(() -> export.getTargetFileName("EXPORT " + cdfPath + ".mv.db"));
		File cdfFile = new File(cdfPath);
		assertRefused(() -> export.getTargetFileName("EXPORT " + cdfFile.getParent() + File.separator + "." + File.separator + cdfFile.getName() + ".mv.db"));
	}

	@Test
	void anExportToTheCdfFileNeverWritesTheNextResult() throws Exception {
		CommandInterpreter interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, sourceDb);
		sourceDb.setCmdLineConsole(console);
		CommandTestSupport.shareVault(interpreter, vault);
		List<String> before = cdfSnapshot();
		byte[] fileBefore = Files.readAllBytes(Path.of(cdfPath + ".mv.db"));

		interpreter.setQuery("EXPORT " + cdfPath + ".mv.db");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, interpreter::executeCommand);
		Assertions.assertTrue(ex.getMessage().contains(PROTECTED), ex.getMessage());
		interpreter.setQuery("SELECT * FROM CUSTOMER");
		interpreter.executeCommand();

		Assertions.assertTrue(console.getOutput().contains("Alice"), "export mode stayed off: the result went to the screen\n" + console.getOutput());
		Assertions.assertArrayEquals(fileBefore, Files.readAllBytes(Path.of(cdfPath + ".mv.db")));
		Assertions.assertEquals(before, cdfSnapshot());
	}

	// =================================================================================================
	// Still working
	// =================================================================================================

	@Test
	void theCdfStillLoadsAfterARefusal() throws Exception {
		Assertions.assertThrows(BroadSQLException.class, () -> dump("DUMP (SELECT 1 AS X) TO $CDF.CONNECTIONS AS H2"));

		DatabaseDefinitionsVault reopened = new DatabaseDefinitionsVault(cdfPath, "testpwd");
		reopened.load();
		Assertions.assertTrue(reopened.contains(SpringPropertiesConfig.CDF_ID));
		Assertions.assertTrue(reopened.contains("MY_ALIAS"));
	}

	@Test
	void ordinaryH2DestinationsStillWork() throws Exception {
		dump("DUMP CUSTOMER TO OTHER_H2.CUSTOMER AS H2");
		Assertions.assertTrue(console.getOutput().contains("1 row(s) exported to OTHER_H2.CUSTOMER"), console.getOutput());

		dump("DUMP CUSTOMER TO NEWDB.CUSTOMER AS H2 MODE APPEND");
		Assertions.assertTrue(console.getOutput().contains("New H2 connection 'NEWDB' created"), console.getOutput());
		Assertions.assertTrue(new File(tmp.toFile(), "NEWDB.mv.db").exists());
	}

	@Test
	void broadSqlsOwnCdfManagementStillWorks() throws Exception {
		Assertions.assertThrows(BroadSQLException.class, () -> dump("DUMP (SELECT 1 AS X) TO $CDF.CONNECTIONS AS H2"));

		DatabaseDefinition added = h2("ADDED", "jdbc:h2:mem:added");
		added.setUserPassword("");
		added.setEnvironment(vault.resolveLocalEnvironmentId());
		vault.saveDatabaseDefinition(added);
		vault.load();

		Assertions.assertTrue(vault.contains("ADDED"));
		Assertions.assertTrue(cdfSnapshot().stream().anyMatch(row -> row.startsWith("ADDED ")));
	}
}
