package com.upandcoding.broadsql.dao.pull.json;

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
 * Direct tests of {@link PullToJsonExporter#writeFile}, bypassing {@code CommandPull} - covers the
 * array-of-objects shape, the exact-number-or-string-fallback rule for precise decimals, ISO-8601 dates,
 * JSON string escaping, {@code NULL} handling, always-a-full-overwrite, and the {@code BLOB} fail-fast
 * guard. There is no JSON parsing library on this project's classpath (a closed-source, dependency-
 * conscious product - see docs/PULL_TO_TEXT.md), so assertions check the exact written text directly,
 * the same approach already used to verify the ODS exporter's raw XML content.
 */
class TestPullToJsonExporter {

	private DatabaseConnection db;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(80), BALANCE DECIMAL(10,2), ACTIVE BOOLEAN, JOINED DATE)",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice', 100.50, TRUE, '2024-01-15'), (2, 'Bob', NULL, FALSE, '2024-02-20')",
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

	private String readFile(String filePath) throws Exception {
		return new String(Files.readAllBytes(Path.of(filePath)), StandardCharsets.UTF_8);
	}

	@Test
	void writesAnArrayOfObjectsWithTheCorrectValues(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.json").toString();

		int rowsWritten = new PullToJsonExporter().writeFile(filePath, query("SELECT * FROM CUSTOMER WHERE ID = 1"));

		Assertions.assertEquals(1, rowsWritten);
		String content = readFile(filePath);
		Assertions.assertTrue(content.startsWith("[\n"), "expected the file to start with a JSON array, got:\n" + content);
		Assertions.assertTrue(content.trim().endsWith("]"), "expected the file to end with the array's closing bracket, got:\n" + content);
		Assertions.assertTrue(content.contains("\"ID\": 1"), "got:\n" + content);
		Assertions.assertTrue(content.contains("\"NAME\": \"Alice\""), "got:\n" + content);
		Assertions.assertTrue(content.contains("\"BALANCE\": 100.50"), "expected the exact plain-string form of the DECIMAL(10,2) value, got:\n" + content);
		Assertions.assertTrue(content.contains("\"ACTIVE\": true"), "got:\n" + content);
		Assertions.assertTrue(content.contains("\"JOINED\": \"2024-01-15\""), "got:\n" + content);
	}

	@Test
	void nullProducesALiteralJsonNullNotTheStringNull(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.json").toString();

		new PullToJsonExporter().writeFile(filePath, query("SELECT ID, BALANCE FROM CUSTOMER WHERE ID = 2"));

		String content = readFile(filePath);
		Assertions.assertTrue(content.contains("\"BALANCE\": null"), "expected an unquoted JSON null, got:\n" + content);
	}

	@Test
	void decimalThatDoesNotRoundTripThroughDoubleFallsBackToAString(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.json").toString();

		new PullToJsonExporter().writeFile(filePath, query("SELECT AMOUNT FROM PRECISE"));

		String content = readFile(filePath);
		Assertions.assertTrue(content.contains("\"AMOUNT\": \"123456789012345.1234567890\""),
				"expected the exact value as a quoted JSON string (would lose precision as a JSON number), got:\n" + content);
	}

	@Test
	void escapesQuotesAndBackslashesInStringValues(@TempDir Path tempDir) throws Exception {
		db.executeUpdateQuery("INSERT INTO CUSTOMER VALUES (3, 'She said \"hi\" \\ bye', 0, TRUE, NULL)");
		String filePath = tempDir.resolve("REPORT.json").toString();

		new PullToJsonExporter().writeFile(filePath, query("SELECT NAME FROM CUSTOMER WHERE ID = 3"));

		String content = readFile(filePath);
		Assertions.assertTrue(content.contains("\"NAME\": \"She said \\\"hi\\\" \\\\ bye\""), "got:\n" + content);
	}

	@Test
	void aSecondWriteFullyOverwritesThePreviousContent(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.json").toString();

		new PullToJsonExporter().writeFile(filePath, query("SELECT ID, NAME FROM CUSTOMER ORDER BY ID"));
		int rowsWritten = new PullToJsonExporter().writeFile(filePath, query("SELECT ID, NAME FROM CUSTOMER WHERE ID = 1"));

		Assertions.assertEquals(1, rowsWritten);
		String content = readFile(filePath);
		Assertions.assertFalse(content.contains("Bob"), "expected the second, smaller result to fully replace the first - Bob must be gone");
		Assertions.assertTrue(content.contains("Alice"));
	}

	@Test
	void rejectsABlobColumnBeforeTouchingTheFile(@TempDir Path tempDir) throws Exception {
		String filePath = tempDir.resolve("REPORT.json").toString();
		ResultSet rs = query("SELECT * FROM HASBLOB");

		Assertions.assertThrows(BroadSQLException.class, () -> new PullToJsonExporter().writeFile(filePath, rs));

		Assertions.assertFalse(new File(filePath).isFile(), "the file must not be created when a column type is rejected");
	}
}
