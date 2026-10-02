package com.upandcoding.broadsql.controller.shell.sql.error;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the resolver against real H2 syntax errors - see its class Javadoc for how the
 * {@code [*]} marker and its surrounding escaping were established empirically against H2
 * 2.3.232 (this project's bundled version, pom.xml) before writing any of this.
 */
class TestH2SqlErrorLocationResolver {

	private Connection connection;
	private final H2SqlErrorLocationResolver resolver = new H2SqlErrorLocationResolver();

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
	void resolvesAPositionOnASingleLineSyntaxError() {
		String sql = "SELECT * FORM CUSTOMER";
		SQLException e = fails(sql);
		Optional<SqlErrorLocation> location = resolver.resolve(sql, e);
		Assertions.assertTrue(location.isPresent());
		Assertions.assertEquals(1, location.get().getLine());
		Assertions.assertEquals(sql.indexOf("FORM") + 1, location.get().getColumn());
	}

	@Test
	void resolvesAPositionOnAMultilineLfSyntaxError() {
		String sql = "SELECT CUSTOMER_ID,\n       NAME,\n       FROM CUSTOMER";
		SQLException e = fails(sql);
		Optional<SqlErrorLocation> location = resolver.resolve(sql, e);
		Assertions.assertTrue(location.isPresent());
		Assertions.assertEquals(3, location.get().getLine());
		Assertions.assertEquals(sql.split("\n")[2].indexOf("FROM") + 1, location.get().getColumn());
	}

	@Test
	void resolvesAPositionOnAMultilineCrlfSyntaxError() {
		String sql = "SELECT CUSTOMER_ID,\r\n       NAME,\r\n       FROM CUSTOMER";
		SQLException e = fails(sql);
		Optional<SqlErrorLocation> location = resolver.resolve(sql, e);
		Assertions.assertTrue(location.isPresent());
		Assertions.assertEquals(3, location.get().getLine());
		Assertions.assertEquals(sql.split("\r\n")[2].indexOf("FROM") + 1, location.get().getColumn());
	}

	@Test
	void resolvesAPositionAtTheVeryEndOfTheStatement() {
		String sql = "SELECT * FROM CUSTOMER WHERE";
		SQLException e = fails(sql);
		Optional<SqlErrorLocation> location = resolver.resolve(sql, e);
		Assertions.assertTrue(location.isPresent());
		Assertions.assertEquals(1, location.get().getLine());
		Assertions.assertEquals(sql.length() + 1, location.get().getColumn());
	}

	@Test
	void returnsEmptyWhenTheDatabaseErrorCarriesNoPosition() {
		String sql = "SELECT * FROM NOSUCHTABLE";
		SQLException e = fails(sql);
		Optional<SqlErrorLocation> location = resolver.resolve(sql, e);
		Assertions.assertTrue(location.isEmpty());
	}

	@Test
	void returnsEmptyForANonH2Exception() {
		SQLException generic = new SQLException("some other driver error", "42000", 123);
		Assertions.assertTrue(resolver.resolve("SELECT 1", generic).isEmpty());
	}

	@Test
	void returnsEmptyWhenSubmittedSqlIsNull() {
		SQLException e = fails("SELECT * FORM CUSTOMER");
		Assertions.assertTrue(resolver.resolve(null, e).isEmpty());
	}

	@Test
	void decodedSpanRoundTripsHexEscapedControlCharacters() {
		// "\000a" is H2's own escaping of a single U+000A newline (see DbException.quote()) -
		// verifies the unescaper independently of a real exception's exact message wording.
		String message = "\"A\\000aB[*]C\"";
		H2SqlErrorLocationResolver.DecodedSpan span = H2SqlErrorLocationResolver.decodeQuotedSpan(message, 1);
		Assertions.assertNotNull(span);
		Assertions.assertEquals("A\nB[*]C", span.decoded);
	}

	@Test
	void decodedSpanRoundTripsDoubledQuoteAndBackslashEscapes() {
		String message = "\"say \"\"hi\"\" and \\\\ done\"";
		H2SqlErrorLocationResolver.DecodedSpan span = H2SqlErrorLocationResolver.decodeQuotedSpan(message, 1);
		Assertions.assertNotNull(span);
		Assertions.assertEquals("say \"hi\" and \\ done", span.decoded);
	}

	@Test
	void decodedSpanFailsClosedOnAMalformedEscape() {
		String message = "\"broken \\zz escape\"";
		H2SqlErrorLocationResolver.DecodedSpan span = H2SqlErrorLocationResolver.decodeQuotedSpan(message, 1);
		Assertions.assertNull(span);
	}

	@Test
	void decodedSpanFailsClosedOnAnUnterminatedSpan() {
		String message = "\"never closed";
		H2SqlErrorLocationResolver.DecodedSpan span = H2SqlErrorLocationResolver.decodeQuotedSpan(message, 1);
		Assertions.assertNull(span);
	}
}
