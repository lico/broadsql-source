package com.upandcoding.broadsql.dao.pull.markdown;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * Direct tests of {@link PullToMarkdownExporter#writeFile}, bypassing {@code CommandPull} - covers the
 * GFM table shape (header, separator, data rows), pipe-character escaping, embedded-newline-to-{@code <br>}
 * conversion, {@code NULL} handling, always-a-full-overwrite, and the {@code BLOB} fail-fast guard.
 */
class TestPullToMarkdownExporter {

	private DatabaseConnection db;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(80), BALANCE DECIMAL(10,2))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice', 100.50), (2, 'Bob', NULL)",
				"CREATE TABLE HASBLOB (ID INT PRIMARY KEY, DATA BLOB)");
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private ResultSet query(String sql) throws SQLException {
		Connection conn = db.getDirectConnection();
		Statement stmt = conn.createStatement();
		return stmt.executeQuery(sql);
	}

	private String readFile(String filePath) throws Exception {
		return new String(Files.readAllBytes(Path.of(filePath)), StandardCharsets.UTF_8);
	}

	@Test
	void writesAHeaderSeparatorAndDataRows(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.md").toString();

		int rowsWritten = new PullToMarkdownExporter().writeFile(filePath, query("SELECT ID, NAME FROM CUSTOMER ORDER BY ID"));

		Assertions.assertEquals(2, rowsWritten);
		String content = readFile(filePath);
		String[] lines = content.split("\n");
		Assertions.assertEquals("| ID | NAME |", lines[0].trim());
		Assertions.assertEquals("| --- | --- |", lines[1].trim());
		Assertions.assertEquals("| 1 | Alice |", lines[2].trim());
		Assertions.assertEquals("| 2 | Bob |", lines[3].trim());
	}

	@Test
	void nullProducesABlankCell(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.md").toString();

		new PullToMarkdownExporter().writeFile(filePath, query("SELECT ID, BALANCE FROM CUSTOMER WHERE ID = 2"));

		String content = readFile(filePath);
		Assertions.assertTrue(content.contains("| 2 |  |"), "expected a blank cell for the NULL balance, got:\n" + content);
	}

	@Test
	void escapesAPipeAndConvertsAnEmbeddedNewlineToBr(@TempDir Path tempDir) throws Exception {
		db.executeUpdateQuery("INSERT INTO CUSTOMER VALUES (3, 'Smith | Jr.' || CHAR(10) || 'line2', 0)");
		String filePath = tempDir.resolve("REPORT.md").toString();

		new PullToMarkdownExporter().writeFile(filePath, query("SELECT NAME FROM CUSTOMER WHERE ID = 3"));

		String content = readFile(filePath);
		Assertions.assertTrue(content.contains("Smith \\| Jr.<br>line2"), "got:\n" + content);
	}

	@Test
	void aSecondWriteFullyOverwritesThePreviousContent(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.md").toString();

		new PullToMarkdownExporter().writeFile(filePath, query("SELECT ID, NAME FROM CUSTOMER ORDER BY ID"));
		int rowsWritten = new PullToMarkdownExporter().writeFile(filePath, query("SELECT ID, NAME FROM CUSTOMER WHERE ID = 1"));

		Assertions.assertEquals(1, rowsWritten);
		String content = readFile(filePath);
		Assertions.assertFalse(content.contains("Bob"), "expected the second, smaller result to fully replace the first - Bob must be gone");
		Assertions.assertTrue(content.contains("Alice"));
	}

	@Test
	void rejectsABlobColumnBeforeTouchingTheFile(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.md").toString();
		ResultSet rs = query("SELECT * FROM HASBLOB");

		Assertions.assertThrows(BroadSQLException.class, () -> new PullToMarkdownExporter().writeFile(filePath, rs));

		Assertions.assertFalse(new File(filePath).isFile(), "the file must not be created when a column type is rejected");
	}
}
