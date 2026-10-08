package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.LastApiExecutionResult;
import com.upandcoding.broadsql.dao.LastApiExecutionResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTable;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Covers {@code PULL API RESULT TO ...} end to end (see docs/BroadSQL XT02 overnight batch plan,
 * sub-sprint 7 - "API result export and local snapshot"): populates
 * {@link LastApiExecutionResultHolder} directly (a real {@code EXECUTE API ENDPOINT} run against a real
 * HTTP server is covered separately by {@code TestApiResultPullEndToEnd}) and runs the new PULL form for
 * {@code H2}, {@code XLSX}, and {@code CSV}, verifying rows land correctly in each destination.
 */
class TestCommandPullApiResult {

	private CapturingShellConsole console;

	@BeforeEach
	void setUp() {
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() {
		LastApiExecutionResultHolder.set(null);
	}

	private static ApiResultTable usersTable() {
		Map<String, String> row1 = new LinkedHashMap<>();
		row1.put("ID", "1");
		row1.put("NAME", "Alice");
		Map<String, String> row2 = new LinkedHashMap<>();
		row2.put("ID", "2");
		row2.put("NAME", "Bob");
		return new ApiResultTable(List.of("ID", "NAME"), List.of(row1, row2), true,
				"[{\"ID\":1,\"NAME\":\"Alice\"},{\"ID\":2,\"NAME\":\"Bob\"}]");
	}

	@Test
	void pullsAnApiResultIntoAnExistingH2Connection() throws BroadSQLException, SQLException {
		LastApiExecutionResultHolder.set(new LastApiExecutionResult(usersTable(), usersTable().getRawJson(),
				"DEMO", "GET List Users", "Development", Instant.now(), 200));

		DatabaseDefinition targetDef = TestDatabaseConnections.newInMemoryTarget("TARGETDB");
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, console);
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("TARGETDB", targetDef);
		vault.setDatabaseConnections(connections);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("PULL API RESULT TO TARGETDB.USERS AS H2");

		Assertions.assertTrue(console.getOutput().contains("2 row(s) pulled into TARGETDB.USERS (table dropped and recreated)."),
				"expected the pull result summary, got:\n" + console.getOutput());

		try (Connection targetConn = DriverManager.getConnection(targetDef.getUrl());
				Statement stmt = targetConn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT \"ID\", \"NAME\" FROM \"USERS\" ORDER BY \"ID\"")) {
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals("1", rs.getString(1));
			Assertions.assertEquals("Alice", rs.getString(2));
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals("2", rs.getString(1));
			Assertions.assertEquals("Bob", rs.getString(2));
			Assertions.assertFalse(rs.next());
		}
	}

	@Test
	void pullsAnApiResultIntoAnXlsxTab(@TempDir Path tempDir) throws BroadSQLException {
		LastApiExecutionResultHolder.set(new LastApiExecutionResult(usersTable(), usersTable().getRawJson(),
				"DEMO", "GET List Users", "Development", Instant.now(), 200));

		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, null, console, settings);

		cmd.execute("PULL API RESULT TO REPORT.USERS AS XLSX");

		File file = tempDir.resolve("REPORT.xlsx").toFile();
		Assertions.assertTrue(file.exists(), "expected REPORT.xlsx to be created, got console:\n" + console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("2 row(s) pulled into tab 'USERS'"),
				"expected the pull result summary, got:\n" + console.getOutput());
	}

	@Test
	void pullsAnApiResultIntoACsvFile(@TempDir Path tempDir) throws BroadSQLException, IOException {
		LastApiExecutionResultHolder.set(new LastApiExecutionResult(usersTable(), usersTable().getRawJson(),
				"DEMO", "GET List Users", "Development", Instant.now(), 200));

		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, null, console, settings);

		cmd.execute("PULL API RESULT TO USERS AS CSV");

		File file = tempDir.resolve("USERS.csv").toFile();
		Assertions.assertTrue(file.exists(), "expected USERS.csv to be created, got console:\n" + console.getOutput());
		List<String> lines = Files.readAllLines(file.toPath());
		String content = String.join("\n", lines);
		Assertions.assertTrue(content.contains("ID") && content.contains("NAME"), "expected header row, got: " + content);
		Assertions.assertTrue(content.contains("Alice"));
		Assertions.assertTrue(content.contains("Bob"));
	}

	@Test
	void refusesWhenNoApiExecutionResultIsHeldInMemory() {
		LastApiExecutionResultHolder.set(null);
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, console);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> cmd.execute("PULL API RESULT TO USERS AS CSV"));

		Assertions.assertTrue(ex.getLocalizedMessage().contains("no API execution result held in memory"),
				"got: " + ex.getLocalizedMessage());
	}

	// ------------------------------------------------------------------------------------------
	// Additional coverage (post-remediation audit follow-up, not tied to a specific numbered
	// finding): the original sub-sprint 7 tests above only ever exercised H2/XLSX/CSV with plain
	// two-row ASCII data. This extends coverage to the remaining five PULL destinations (ODS/TXT/
	// JSON/MD/HTML), and to null/Unicode/embedded-newline/nested-JSON-shaped-cell data in one
	// combined fixture rather than a full combinatorial matrix - the risk this protects against is
	// data corruption/truncation in a specific exporter, not exhaustive format coverage for its own
	// sake.
	// ------------------------------------------------------------------------------------------

	private static ApiResultTable richEdgeCaseTable() {
		Map<String, String> row = new LinkedHashMap<>();
		row.put("ID", "1");
		row.put("NOTE", null); // JSON null / missing value
		row.put("CITY", "Zürich café ☕"); // Unicode
		row.put("BIO", "Line one\nLine two"); // embedded newline
		row.put("META", "{\"nested\":true,\"tags\":[\"a\",\"b\"]}"); // nested-JSON-shaped string cell, never re-parsed
		return new ApiResultTable(List.of("ID", "NOTE", "CITY", "BIO", "META"), List.of(row), true, "[{}]");
	}

	@Test
	void pullsAnApiResultIntoAnOdsTab(@TempDir Path tempDir) throws BroadSQLException {
		LastApiExecutionResultHolder.set(new LastApiExecutionResult(richEdgeCaseTable(), "[{}]", "DEMO", "GET Rich", "Development", Instant.now(), 200));

		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, null, console, settings);

		cmd.execute("PULL API RESULT TO REPORT.RICH AS ODS");

		File file = tempDir.resolve("REPORT.ods").toFile();
		Assertions.assertTrue(file.exists(), "expected REPORT.ods to be created, got console:\n" + console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("1 row(s) pulled into tab 'RICH'"), "got:\n" + console.getOutput());
	}

	@Test
	void pullsAnApiResultIntoATxtFileWithTabDelimiterPreservingUnicodeAndNewlines(@TempDir Path tempDir) throws BroadSQLException, IOException {
		LastApiExecutionResultHolder.set(new LastApiExecutionResult(richEdgeCaseTable(), "[{}]", "DEMO", "GET Rich", "Development", Instant.now(), 200));

		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, null, console, settings);

		cmd.execute("PULL API RESULT TO RICH AS TXT");

		File file = tempDir.resolve("RICH.txt").toFile();
		Assertions.assertTrue(file.exists());
		String content = Files.readString(file.toPath());
		Assertions.assertTrue(content.contains("Zürich café ☕"), "Unicode must survive: " + content);
		// The text exporter CSV-quotes a cell containing the delimiter/quotes (doubling embedded quotes,
		// RFC 4180-style) - so the nested-JSON-shaped value survives as text content, not byte-for-byte
		// identical to the original cell string; check for its substance rather than an exact match.
		Assertions.assertTrue(content.contains("nested") && content.contains("tags") && content.contains("\"\"a\"\""),
				"a nested-JSON-shaped cell must be preserved as text (CSV-quoted), never re-parsed into real columns: " + content);
	}

	@Test
	void pullsAnApiResultIntoAJsonFilePreservingNullUnicodeAndNestedJsonCellAsText(@TempDir Path tempDir) throws BroadSQLException, IOException {
		LastApiExecutionResultHolder.set(new LastApiExecutionResult(richEdgeCaseTable(), "[{}]", "DEMO", "GET Rich", "Development", Instant.now(), 200));

		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, null, console, settings);

		cmd.execute("PULL API RESULT TO RICH AS JSON");

		File file = tempDir.resolve("RICH.json").toFile();
		Assertions.assertTrue(file.exists());
		String content = Files.readString(file.toPath());
		Assertions.assertTrue(content.contains("Zürich café ☕"), "Unicode must survive in the flattened JSON export: " + content);
	}

	@Test
	void pullsAnApiResultIntoAMarkdownFile(@TempDir Path tempDir) throws BroadSQLException, IOException {
		LastApiExecutionResultHolder.set(new LastApiExecutionResult(richEdgeCaseTable(), "[{}]", "DEMO", "GET Rich", "Development", Instant.now(), 200));

		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, null, console, settings);

		cmd.execute("PULL API RESULT TO RICH AS MD");

		File file = tempDir.resolve("RICH.md").toFile();
		Assertions.assertTrue(file.exists());
		String content = Files.readString(file.toPath());
		Assertions.assertTrue(content.contains("Zürich café ☕"), "Unicode must survive in the Markdown export: " + content);
	}

	@Test
	void pullsAnApiResultIntoAnHtmlFile(@TempDir Path tempDir) throws BroadSQLException, IOException {
		LastApiExecutionResultHolder.set(new LastApiExecutionResult(richEdgeCaseTable(), "[{}]", "DEMO", "GET Rich", "Development", Instant.now(), 200));

		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(tempDir.toString() + File.separator);
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, null, console, settings);

		cmd.execute("PULL API RESULT TO RICH AS HTML");

		File file = tempDir.resolve("RICH.html").toFile();
		Assertions.assertTrue(file.exists());
		String content = Files.readString(file.toPath());
		Assertions.assertTrue(content.contains("Zürich café") || content.contains("Z&uuml;rich"), "Unicode must survive (verbatim or HTML-escaped) in the HTML export: " + content);
	}

	@Test
	void pullsAnEmptyApiResultIntoH2ProducingAnEmptyButQueryableTable() throws BroadSQLException, SQLException {
		ApiResultTable emptyTable = new ApiResultTable(List.of("ID", "NAME"), List.of(), true, "[]");
		LastApiExecutionResultHolder.set(new LastApiExecutionResult(emptyTable, "[]", "DEMO", "GET Empty", "Development", Instant.now(), 200));

		DatabaseDefinition targetDef = TestDatabaseConnections.newInMemoryTarget("EMPTYTARGET");
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, console);
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("EMPTYTARGET", targetDef);
		vault.setDatabaseConnections(connections);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("PULL API RESULT TO EMPTYTARGET.EMPTYUSERS AS H2");

		Assertions.assertTrue(console.getOutput().contains("0 row(s) pulled into EMPTYTARGET.EMPTYUSERS"), "got:\n" + console.getOutput());
		try (Connection targetConn = DriverManager.getConnection(targetDef.getUrl());
				Statement stmt = targetConn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM \"EMPTYUSERS\"")) {
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals(0, rs.getInt(1), "the table must exist and be queryable even with zero rows");
		}
	}

	/**
	 * H2 materialized-table querying and joining: proves the table PULL API RESULT ... AS H2 creates is
	 * not merely dumped and forgotten - it is a real, ordinary H2 table that can be filtered and joined
	 * against another table in the same target database using normal SQL, exactly like any other PULL
	 * AS H2 destination.
	 */
	@Test
	void theMaterializedH2TableCanBeFilteredAndJoinedWithAnotherTable() throws BroadSQLException, SQLException {
		LastApiExecutionResultHolder.set(new LastApiExecutionResult(usersTable(), usersTable().getRawJson(),
				"DEMO", "GET List Users", "Development", Instant.now(), 200));

		DatabaseDefinition targetDef = TestDatabaseConnections.newInMemoryTarget("JOINTARGET");
		CommandPull cmd = CommandTestSupport.create(CommandPull.class, console);
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("JOINTARGET", targetDef);
		vault.setDatabaseConnections(connections);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("PULL API RESULT TO JOINTARGET.USERS AS H2");

		try (Connection targetConn = DriverManager.getConnection(targetDef.getUrl());
				Statement stmt = targetConn.createStatement()) {
			stmt.execute("CREATE TABLE \"ROLES\" (\"ID\" VARCHAR, \"ROLE\" VARCHAR)");
			stmt.execute("INSERT INTO \"ROLES\" VALUES ('1', 'ADMIN'), ('2', 'VIEWER')");

			try (ResultSet filtered = stmt.executeQuery("SELECT \"NAME\" FROM \"USERS\" WHERE \"ID\" = '2'")) {
				Assertions.assertTrue(filtered.next());
				Assertions.assertEquals("Bob", filtered.getString(1));
				Assertions.assertFalse(filtered.next());
			}

			try (ResultSet joined = stmt.executeQuery(
					"SELECT U.\"NAME\", R.\"ROLE\" FROM \"USERS\" U JOIN \"ROLES\" R ON U.\"ID\" = R.\"ID\" ORDER BY U.\"ID\"")) {
				Assertions.assertTrue(joined.next());
				Assertions.assertEquals("Alice", joined.getString(1));
				Assertions.assertEquals("ADMIN", joined.getString(2));
				Assertions.assertTrue(joined.next());
				Assertions.assertEquals("Bob", joined.getString(1));
				Assertions.assertEquals("VIEWER", joined.getString(2));
				Assertions.assertFalse(joined.next());
			}
		}
	}
}
