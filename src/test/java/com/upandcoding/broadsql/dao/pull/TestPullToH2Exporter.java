package com.upandcoding.broadsql.dao.pull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Covers {@link PullToH2Exporter#checkKeyNullability} (new, see docs/EXPORT_TO_H2.md, "APPEND KEY(...)
 * opened up to joined queries") and the duplicate-result-column-label check in {@code resolveColumnPlan}
 * (shared by {@link PullToH2Exporter#overwrite}/{@link PullToH2Exporter#append}), both exercised against
 * a real H2 2.3.232 database rather than mocked - per this project's "verify empirically, not just by
 * reading code" convention (CLAUDE.md), since the entire point of {@code checkKeyNullability} is how a
 * <i>real</i> JDBC driver actually answers {@code isNullable()}, which cannot be trusted from the JDBC
 * spec's wording alone. In particular, {@link #anOuterJoinsUnmatchedKeyIsNotCaughtByTheNullabilityCheck}
 * pins down the empirically confirmed limitation documented on {@code checkKeyNullability} itself: H2
 * reports a {@code LEFT JOIN}'s unmatched-side key column as "cannot be NULL" even though the actual row
 * has a real {@code NULL} in it. This is a known, accepted gap (see docs/EXPORT_TO_H2.md), not a bug to
 * fix - the test exists so a future H2 upgrade that changes this behavior either way gets noticed.
 */
class TestPullToH2Exporter {

	private Connection source;
	private PullToH2Exporter exporter;

	@BeforeEach
	void setUp() throws Exception {
		Class.forName("org.h2.Driver");
		String name = "pullsrc_" + UUID.randomUUID().toString().replace("-", "");
		source = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
		try (Statement st = source.createStatement()) {
			st.execute("CREATE TABLE CUSTOMER (ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(50))");
			st.execute("CREATE TABLE ORDERS (ID INT NOT NULL PRIMARY KEY, CUSTOMER_ID INT NOT NULL, "
					+ "TOTAL DECIMAL(10,2), OPTIONAL_NOTE VARCHAR(20))");
			st.execute("INSERT INTO CUSTOMER VALUES (1,'Alice'),(2,'Bob')");
			st.execute("INSERT INTO ORDERS VALUES (100,1,50.0,'first order'),(101,1,NULL,NULL)");
		}
		exporter = new PullToH2Exporter();
	}

	@AfterEach
	void tearDown() throws SQLException {
		source.close();
	}

	/**
	 * H2's {@code ResultSetMetaData} requires the {@code ResultSet} it came from to still be open (found
	 * empirically while writing these tests: fetching the metadata and closing the {@code ResultSet}
	 * before calling {@code checkKeyNullability} threw "The object is already closed") - so this runs
	 * {@code check} with the query's {@code ResultSet}/{@code Statement} still open, exactly like
	 * {@code CommandPull} itself does (it calls {@code checkKeyNullability} before closing anything).
	 */
	private interface NullabilityCheck {
		Object run(ResultSetMetaData metaData) throws Exception;
	}

	private Object withMetaData(String sql, NullabilityCheck check) throws Exception {
		try (Statement st = source.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			return check.run(rs.getMetaData());
		}
	}

	// --- checkKeyNullability: confirmed not-nullable -------------------------------------------------

	@Test
	void acceptsAConfirmedNotNullKeyFromAPlainTable() throws Exception {
		Object warning = withMetaData("SELECT ID FROM CUSTOMER", md -> exporter.checkKeyNullability(md, "ID", false));
		Assertions.assertNull(warning);
	}

	@Test
	void acceptsAConfirmedNotNullKeyAcrossAnInnerJoin() throws Exception {
		Object warning = withMetaData("SELECT C.ID AS CID, O.ID AS OID FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID",
				md -> exporter.checkKeyNullability(md, "OID", false));
		Assertions.assertNull(warning);
	}

	// --- checkKeyNullability: confirmed nullable - always rejected, FORCE or not ----------------------

	@Test
	void rejectsAConfirmedNullableKeyFromAPlainTable() throws Exception {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> withMetaData("SELECT NAME FROM CUSTOMER", md -> exporter.checkKeyNullability(md, "NAME", false)));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("can be NULL"), "got: " + ex.getLocalizedMessage());
	}

	@Test
	void rejectsAConfirmedNullableKeyEvenWithForce() throws Exception {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> withMetaData("SELECT NAME FROM CUSTOMER", md -> exporter.checkKeyNullability(md, "NAME", true)));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("cannot be forced past"), "got: " + ex.getLocalizedMessage());
	}

	@Test
	void rejectsAConfirmedNullableKeyAcrossAnInnerJoin() throws Exception {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> withMetaData("SELECT O.ID AS OID, O.OPTIONAL_NOTE AS NOTE FROM ORDERS O JOIN CUSTOMER C ON O.CUSTOMER_ID = C.ID",
						md -> exporter.checkKeyNullability(md, "NOTE", false)));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("can be NULL"), "got: " + ex.getLocalizedMessage());
	}

	// --- checkKeyNullability: unknown nullability - rejected by default, FORCE proceeds ---------------

	@Test
	void rejectsAComputedExpressionKeyWithoutForce() throws Exception {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> withMetaData("SELECT COALESCE(OPTIONAL_NOTE, 'none') AS NOTE FROM ORDERS",
						md -> exporter.checkKeyNullability(md, "NOTE", false)));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("FORCE"), "got: " + ex.getLocalizedMessage());
	}

	@Test
	void acceptsAComputedExpressionKeyWithForceAndReturnsAWarning() throws Exception {
		Object warning = withMetaData("SELECT COALESCE(OPTIONAL_NOTE, 'none') AS NOTE FROM ORDERS",
				md -> exporter.checkKeyNullability(md, "NOTE", true));
		Assertions.assertNotNull(warning);
		Assertions.assertTrue(warning.toString().startsWith("WARNING"), "got: " + warning);
	}

	// --- the empirically confirmed, known limitation: outer joins are not caught ----------------------

	/**
	 * Pins down the limitation documented on {@link PullToH2Exporter#checkKeyNullability}: verified
	 * empirically (see docs/EXPORT_TO_H2.md) that H2 reports {@link ResultSetMetaData#columnNoNulls} for
	 * a {@code LEFT JOIN}'s unmatched-side key column, even though the actual row returned has a real
	 * {@code NULL} there - so this check silently, wrongly says "safe". This is why {@code CommandPull}
	 * also prints an unconditional caution note ({@link PullSourceShapeValidator#usesOuterJoin}) whenever
	 * an outer join is used, independent of what this method concludes.
	 */
	@Test
	void anOuterJoinsUnmatchedKeyIsNotCaughtByTheNullabilityCheck() throws Exception {
		try (Statement st = source.createStatement()) {
			st.execute("INSERT INTO CUSTOMER VALUES (3, 'NoOrdersYet')");
		}
		String sql = "SELECT C.ID AS CID, O.ID AS OID FROM CUSTOMER C LEFT JOIN ORDERS O ON O.CUSTOMER_ID = C.ID WHERE C.ID = 3";

		// Confirm the actual row really does carry a NULL, so the metadata answer below is demonstrably wrong.
		try (Statement st = source.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			Assertions.assertTrue(rs.next());
			rs.getObject("OID");
			Assertions.assertTrue(rs.wasNull(), "expected OID to be NULL for a customer with no orders");
		}

		Object warning = withMetaData(sql, md -> exporter.checkKeyNullability(md, "OID", false));
		Assertions.assertNull(warning,
				"known limitation: H2 reports this outer-joined key as not-nullable even though it can be NULL");
	}

	// --- duplicate result-column-label check (resolveColumnPlan, shared by overwrite/append) ----------

	@Test
	void rejectsAJoinWithTwoColumnsSharingTheSameLabel() throws Exception {
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("DUPLABELTARGET");
		try (Statement st = source.createStatement();
				ResultSet rs = st.executeQuery("SELECT C.ID, O.ID FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID")) {
			BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
					() -> exporter.overwrite(target, "CUST_ORDERS", rs));
			Assertions.assertTrue(ex.getLocalizedMessage().contains("two columns both named 'ID'"), "got: " + ex.getLocalizedMessage());
		}
	}

	@Test
	void acceptsAJoinWithAliasedColumnsAvoidingTheCollision() throws Exception {
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("ALIASEDTARGET");
		try (Statement st = source.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT C.ID AS CUSTOMER_ID, O.ID AS ORDER_ID FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID")) {
			int rowsWritten = exporter.overwrite(target, "CUST_ORDERS", rs);
			Assertions.assertEquals(2, rowsWritten);
		}
	}
}
