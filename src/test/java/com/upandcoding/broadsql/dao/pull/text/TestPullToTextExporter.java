package com.upandcoding.broadsql.dao.pull.text;

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
 * Direct tests of {@link PullToTextExporter#writeFile}, bypassing {@code CommandPull} - covers the
 * behaviors documented in docs/PULL_TO_TEXT.md: RFC 4180-style quoting, {@code NULL} handling, exact
 * (non-double-rounded) precision for numeric text, always a full overwrite (no append, no per-file
 * metadata), and the {@code BLOB} fail-fast guard.
 */
class TestPullToTextExporter {

	private DatabaseConnection db;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(80), BALANCE DECIMAL(10,2))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice', 100.50), (2, 'Bob', NULL)",
				"CREATE TABLE HASBLOB (ID INT PRIMARY KEY, DATA BLOB)",
				"CREATE TABLE PRECISE (ID INT PRIMARY KEY, AMOUNT DECIMAL(30,10))",
				"INSERT INTO PRECISE VALUES (1, 123456789012345.123456789)");
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

	/** Reads the file as UTF-8 text and strips the leading BOM, if any, so assertions can compare plain strings. */
	private String readFile(String filePath) throws Exception {
		String content = new String(Files.readAllBytes(Path.of(filePath)), StandardCharsets.UTF_8);
		return content.startsWith("﻿") ? content.substring(1) : content;
	}

	@Test
	void writesAHeaderAndDataRowsWithTheGivenSeparator(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.csv").toString();

		int rowsWritten = new PullToTextExporter().writeFile(filePath, ';', query("SELECT ID, NAME FROM CUSTOMER ORDER BY ID"));

		Assertions.assertEquals(2, rowsWritten);
		String content = readFile(filePath);
		String[] lines = content.split("\r\n");
		Assertions.assertEquals("ID;NAME", lines[0], "expected the header row");
		Assertions.assertEquals("1;Alice", lines[1]);
		Assertions.assertEquals("2;Bob", lines[2]);
	}

	@Test
	void writesWithATabSeparatorForTxt(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.txt").toString();

		new PullToTextExporter().writeFile(filePath, '\t', query("SELECT ID, NAME FROM CUSTOMER ORDER BY ID"));

		String content = readFile(filePath);
		String[] lines = content.split("\r\n");
		Assertions.assertEquals("ID\tNAME", lines[0]);
		Assertions.assertEquals("1\tAlice", lines[1]);
	}

	@Test
	void quotesAFieldContainingTheSeparatorAQuoteOrANewline(@TempDir Path tempDir) throws Exception {
		db.executeUpdateQuery("INSERT INTO CUSTOMER VALUES (3, 'Smith; \"Jr.\"' || CHAR(10) || 'line2', 0)");
		String filePath = tempDir.resolve("REPORT.csv").toString();

		new PullToTextExporter().writeFile(filePath, ';', query("SELECT NAME FROM CUSTOMER WHERE ID = 3"));

		String content = readFile(filePath);
		Assertions.assertTrue(content.contains("\"Smith; \"\"Jr.\"\"\nline2\""),
				"expected the field wrapped in quotes with embedded quotes doubled, got:\n" + content);
	}

	@Test
	void nullProducesAnEmptyFieldNotTheWordNull(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.csv").toString();

		new PullToTextExporter().writeFile(filePath, ';', query("SELECT ID, NAME, BALANCE FROM CUSTOMER ORDER BY ID"));

		String content = readFile(filePath);
		String[] lines = content.split("\r\n");
		Assertions.assertEquals("2;Bob;", lines[2], "expected Bob's NULL balance to render as an empty field, not the word null");
	}

	@Test
	void decimalIsWrittenWithItsExactValueNoDoubleRounding(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.csv").toString();

		new PullToTextExporter().writeFile(filePath, ';', query("SELECT AMOUNT FROM PRECISE"));

		String content = readFile(filePath);
		Assertions.assertTrue(content.contains("123456789012345.1234567890"),
				"expected the exact decimal text (text has no double-precision concern at all), got:\n" + content);
	}

	@Test
	void aSecondWriteFullyOverwritesThePreviousContent(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.csv").toString();

		new PullToTextExporter().writeFile(filePath, ';', query("SELECT ID, NAME FROM CUSTOMER ORDER BY ID"));
		int rowsWritten = new PullToTextExporter().writeFile(filePath, ';', query("SELECT ID, NAME FROM CUSTOMER WHERE ID = 1"));

		Assertions.assertEquals(1, rowsWritten);
		String content = readFile(filePath);
		String[] lines = content.split("\r\n");
		Assertions.assertEquals(2, lines.length, "expected only a header row and Alice's row - the file must be fully replaced, not appended to");
		Assertions.assertFalse(content.contains("Bob"), "expected the second, smaller result to fully replace the first - Bob must be gone");
		Assertions.assertTrue(content.contains("Alice"), "expected Alice, from the second write, still present");
	}

	@Test
	void rejectsABlobColumnBeforeTouchingTheFile(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.csv").toString();
		ResultSet rs = query("SELECT * FROM HASBLOB");

		Assertions.assertThrows(BroadSQLException.class, () -> new PullToTextExporter().writeFile(filePath, ';', rs));

		Assertions.assertFalse(new File(filePath).isFile(), "the file must not be created when a column type is rejected");
	}
}
