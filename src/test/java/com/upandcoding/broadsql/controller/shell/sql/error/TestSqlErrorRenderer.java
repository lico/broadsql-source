package com.upandcoding.broadsql.controller.shell.sql.error;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.SqlExecutionException;

class TestSqlErrorRenderer {

	private Connection connection;

	@BeforeEach
	void setUp() throws Exception {
		Class.forName("org.h2.Driver");
		connection = DriverManager.getConnection("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
		try (Statement st = connection.createStatement()) {
			st.execute("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		}
	}

	@AfterEach
	void tearDown() throws SQLException {
		connection.close();
	}

	private SQLException fails(String sql) {
		try (Statement st = connection.createStatement()) {
			st.execute(sql);
			throw new AssertionError("expected a SQLException for: " + sql);
		} catch (SQLException e) {
			return e;
		}
	}

	@Test
	void rendersConnectionSqlStateAndErrorCode() {
		SQLException e = fails("SELECT * FROM NOSUCHTABLE");
		String rendered = SqlErrorRenderer.render(new SqlExecutionException("SELECT * FROM NOSUCHTABLE", e, "MADBQA", "SELECT"));
		Assertions.assertTrue(rendered.contains("SQL ERROR"));
		Assertions.assertTrue(rendered.contains("Connection : MADBQA"));
		Assertions.assertTrue(rendered.contains("SQLState   : " + e.getSQLState()));
		Assertions.assertTrue(rendered.contains("Error code : " + e.getErrorCode()));
	}

	@Test
	void showsTheExactSubmittedSqlNotAReformattedCopy() {
		String sql = "SELECT   *   FROM   NOSUCHTABLE";
		SQLException e = fails(sql);
		String rendered = SqlErrorRenderer.render(new SqlExecutionException(sql, e, "CONN", "SELECT"));
		Assertions.assertTrue(rendered.contains(sql), "expected the exact submitted SQL, got:\n" + rendered);
	}

	@Test
	void preservesTheAuthoritativeDatabaseMessageText() {
		SQLException e = fails("SELECT * FROM NOSUCHTABLE");
		String rendered = SqlErrorRenderer.render(new SqlExecutionException("SELECT * FROM NOSUCHTABLE", e, "CONN", "SELECT"));
		Assertions.assertTrue(rendered.contains("NOSUCHTABLE"));
	}

	@Test
	void rendersACaretAtTheResolvedPositionForASyntaxError() {
		String sql = "SELECT * FORM CUSTOMER";
		SQLException e = fails(sql);
		String rendered = SqlErrorRenderer.render(new SqlExecutionException(sql, e, "CONN", "SELECT"));
		Assertions.assertTrue(rendered.contains("^"), "expected a caret, got:\n" + rendered);
		Assertions.assertTrue(rendered.contains(sql));
	}

	@Test
	void doesNotDuplicateH2sOwnEscapedSqlEchoInsideTheMessage() {
		String sql = "SELECT CUSTOMER_ID,\n       NAME,\n       FROM CUSTOMER";
		SQLException e = fails(sql);
		String rendered = SqlErrorRenderer.render(new SqlExecutionException(sql, e, "CONN", "SELECT"));
		Assertions.assertFalse(rendered.contains("\\000a"), "H2's own escaped echo must not leak into the rendered block:\n" + rendered);
	}

	@Test
	void rendersNoCaretWhenNoPositionIsAvailable() {
		String sql = "SELECT * FROM NOSUCHTABLE";
		SQLException e = fails(sql);
		String rendered = SqlErrorRenderer.render(new SqlExecutionException(sql, e, "CONN", "SELECT"));
		Assertions.assertFalse(rendered.contains("^"), "no caret should be shown when the driver gives no reliable position:\n" + rendered);
	}

	@Test
	void skipsTheCaretButKeepsALineColumnNoteWhenTheLineContainsATab() {
		String sql = "SELECT *\tFORM CUSTOMER";
		SQLException e = fails(sql);
		String rendered = SqlErrorRenderer.render(new SqlExecutionException(sql, e, "CONN", "SELECT"));
		Assertions.assertFalse(rendered.contains("^"), "a tab on the error line makes a caret potentially misleading:\n" + rendered);
		Assertions.assertTrue(rendered.contains("line 1"), rendered);
	}

	@Test
	void showsDistinctChainedExceptionsAndSuppressesDuplicates() throws SQLException {
		SQLException primary = new SQLException("primary message", "42000", 1);
		SQLException chainedDistinct = new SQLException("second message", "42001", 2);
		SQLException duplicateOfPrimary = new SQLException("primary message", "42000", 1);
		primary.setNextException(chainedDistinct);
		chainedDistinct.setNextException(duplicateOfPrimary);

		String rendered = SqlErrorRenderer.render(new SqlExecutionException("SELECT 1", primary, "CONN", "SELECT"));

		Assertions.assertTrue(rendered.contains("second message"));
		long occurrences = rendered.lines().filter(l -> l.contains("primary message")).count();
		Assertions.assertEquals(1, occurrences, "the duplicate chained exception must be suppressed:\n" + rendered);
	}

	@Test
	void handlesMultilineSqlWithCrlfLineNumbering() {
		String sql = "SELECT ID,\r\nNAME\r\nFROM NOSUCHTABLE";
		SQLException e = fails(sql);
		String rendered = SqlErrorRenderer.render(new SqlExecutionException(sql, e, "CONN", "SELECT"));
		Assertions.assertTrue(rendered.contains("1 | SELECT ID,"));
		Assertions.assertTrue(rendered.contains("2 | NAME"));
		Assertions.assertTrue(rendered.contains("3 | FROM NOSUCHTABLE"));
	}
}
