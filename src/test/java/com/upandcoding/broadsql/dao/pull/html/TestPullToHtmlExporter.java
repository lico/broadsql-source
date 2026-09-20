package com.upandcoding.broadsql.dao.pull.html;

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
 * Direct tests of {@link PullToHtmlExporter#writeFile}, bypassing {@code CommandPull} - covers the bare
 * {@code <table>} fragment shape (no {@code <html>}/{@code <head>}/{@code <body>} wrapper, per the
 * explicit product decision), HTML entity escaping, {@code NULL} handling, always-a-full-overwrite, and
 * the {@code BLOB} fail-fast guard.
 */
class TestPullToHtmlExporter {

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
	void writesABareTableFragmentWithNoHtmlWrapper(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.html").toString();

		int rowsWritten = new PullToHtmlExporter().writeFile(filePath, query("SELECT ID, NAME FROM CUSTOMER ORDER BY ID"));

		Assertions.assertEquals(2, rowsWritten);
		String content = readFile(filePath);
		Assertions.assertTrue(content.trim().startsWith("<table>"), "expected a bare <table> fragment, got:\n" + content);
		Assertions.assertFalse(content.contains("<html"), "expected no <html> wrapper - a fragment, per the product decision, got:\n" + content);
		Assertions.assertFalse(content.contains("<body"), "expected no <body> wrapper, got:\n" + content);
		Assertions.assertTrue(content.contains("<th"), "expected a styled header cell, got:\n" + content);
		Assertions.assertTrue(content.contains(">ID<") || content.contains(">ID</th>"), "got:\n" + content);
		Assertions.assertTrue(content.contains(">Alice<"), "got:\n" + content);
		Assertions.assertTrue(content.contains(">Bob<"), "got:\n" + content);
	}

	@Test
	void nullProducesAnEmptyCell(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.html").toString();

		new PullToHtmlExporter().writeFile(filePath, query("SELECT ID, BALANCE FROM CUSTOMER WHERE ID = 2"));

		String content = readFile(filePath);
		Assertions.assertTrue(content.contains("></td>"), "expected an empty cell for the NULL balance, got:\n" + content);
	}

	@Test
	void escapesAmpersandLessThanAndGreaterThan(@TempDir Path tempDir) throws Exception {
		db.executeUpdateQuery("INSERT INTO CUSTOMER VALUES (3, 'A & B <tag> > end', 0)");
		String filePath = tempDir.resolve("REPORT.html").toString();

		new PullToHtmlExporter().writeFile(filePath, query("SELECT NAME FROM CUSTOMER WHERE ID = 3"));

		String content = readFile(filePath);
		Assertions.assertTrue(content.contains("A &amp; B &lt;tag&gt; &gt; end"), "got:\n" + content);
		Assertions.assertFalse(content.contains("<tag>"), "the raw, unescaped tag must not appear in the output");
	}

	@Test
	void aSecondWriteFullyOverwritesThePreviousContent(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.html").toString();

		new PullToHtmlExporter().writeFile(filePath, query("SELECT ID, NAME FROM CUSTOMER ORDER BY ID"));
		int rowsWritten = new PullToHtmlExporter().writeFile(filePath, query("SELECT ID, NAME FROM CUSTOMER WHERE ID = 1"));

		Assertions.assertEquals(1, rowsWritten);
		String content = readFile(filePath);
		Assertions.assertFalse(content.contains("Bob"), "expected the second, smaller result to fully replace the first - Bob must be gone");
		Assertions.assertTrue(content.contains("Alice"));
	}

	@Test
	void rejectsABlobColumnBeforeTouchingTheFile(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.html").toString();
		ResultSet rs = query("SELECT * FROM HASBLOB");

		Assertions.assertThrows(BroadSQLException.class, () -> new PullToHtmlExporter().writeFile(filePath, rs));

		Assertions.assertFalse(new File(filePath).isFile(), "the file must not be created when a column type is rejected");
	}
}
