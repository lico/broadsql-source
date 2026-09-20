package com.upandcoding.broadsql.dao.pull;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Covers {@link PullSourceShapeValidator} directly - no database needed, since it is a pure text/regex
 * check. Regression coverage for the pre-existing "simple single-table" rules (previously verified only
 * by a throwaway, uncommitted program per docs/TECHNICAL_CHANGE.md, 28/08/2026), plus the "APPEND KEY(...)
 * opened up to joined queries" change (docs/EXPORT_TO_H2.md, 03/09/2026): JOIN is now eligible, but
 * comma-joined/derived FROM sources and every aggregation/ordering keyword stay forbidden.
 */
class TestPullSourceShapeValidator {

	// --- validateEligible: still-forbidden shapes (unchanged by this feature) -----------------------

	@Test
	void rejectsCommaJoinedTables() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullSourceShapeValidator.validateEligible("SELECT * FROM CUSTOMER, ORDERS WHERE CUSTOMER.ID = ORDERS.CUSTOMER_ID"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("comma-joined"), "got: " + ex.getLocalizedMessage());
	}

	@Test
	void rejectsDerivedTableInFrom() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullSourceShapeValidator.validateEligible("SELECT * FROM (SELECT * FROM CUSTOMER) T"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("derived table"), "got: " + ex.getLocalizedMessage());
	}

	@Test
	void rejectsGroupBy() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullSourceShapeValidator.validateEligible("SELECT COUNTRY, COUNT(*) FROM CUSTOMER GROUP BY COUNTRY"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("GROUP BY"), "got: " + ex.getLocalizedMessage());
	}

	@Test
	void rejectsOrderBy() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullSourceShapeValidator.validateEligible("SELECT * FROM CUSTOMER ORDER BY ID"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("ORDER BY"), "got: " + ex.getLocalizedMessage());
	}

	@Test
	void rejectsUnion() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullSourceShapeValidator.validateEligible("SELECT ID FROM CUSTOMER UNION SELECT ID FROM ARCHIVED_CUSTOMER"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("UNION"), "got: " + ex.getLocalizedMessage());
	}

	@Test
	void rejectsLimit() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullSourceShapeValidator.validateEligible("SELECT * FROM CUSTOMER LIMIT 10"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("LIMIT"), "got: " + ex.getLocalizedMessage());
	}

	// --- validateEligible: baseline-eligible shapes (regression, pre-existing behavior) --------------

	@Test
	void acceptsAPlainSingleTable() {
		Assertions.assertDoesNotThrow(() -> PullSourceShapeValidator.validateEligible("SELECT * FROM CUSTOMER"));
	}

	@Test
	void acceptsAPlainSingleTableWithWhere() {
		Assertions.assertDoesNotThrow(() -> PullSourceShapeValidator.validateEligible("SELECT * FROM CUSTOMER WHERE COUNTRY = 'FR'"));
	}

	@Test
	void doesNotFalselyRejectAKeywordInsideAStringLiteral() {
		Assertions.assertDoesNotThrow(() ->
				PullSourceShapeValidator.validateEligible("SELECT * FROM CUSTOMER WHERE NAME = 'JOIN THE CLUB, ORDER BY MAIL'"));
	}

	@Test
	void doesNotFalselyRejectAKeywordInsideALegitimateWhereSubquery() {
		Assertions.assertDoesNotThrow(() -> PullSourceShapeValidator.validateEligible(
				"SELECT * FROM CUSTOMER WHERE ID IN (SELECT ID FROM CUSTOMER ORDER BY ID LIMIT 5)"));
	}

	// --- validateEligible: JOIN now eligible (this change) -------------------------------------------

	@Test
	void acceptsABareJoin() {
		Assertions.assertDoesNotThrow(() ->
				PullSourceShapeValidator.validateEligible("SELECT C.ID, O.ID FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID"));
	}

	@Test
	void acceptsAnExplicitInnerJoin() {
		Assertions.assertDoesNotThrow(() -> PullSourceShapeValidator.validateEligible(
				"SELECT C.ID, O.ID FROM CUSTOMER C INNER JOIN ORDERS O ON O.CUSTOMER_ID = C.ID"));
	}

	@Test
	void acceptsALeftJoin() {
		Assertions.assertDoesNotThrow(() -> PullSourceShapeValidator.validateEligible(
				"SELECT C.ID, O.ID FROM CUSTOMER C LEFT JOIN ORDERS O ON O.CUSTOMER_ID = C.ID"));
	}

	@Test
	void acceptsAJoinWithAWhereClause() {
		Assertions.assertDoesNotThrow(() -> PullSourceShapeValidator.validateEligible(
				"SELECT C.ID, O.ID FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID WHERE C.COUNTRY = 'FR'"));
	}

	@Test
	void acceptsMultipleChainedJoins() {
		Assertions.assertDoesNotThrow(() -> PullSourceShapeValidator.validateEligible(
				"SELECT C.ID, O.ID, P.ID FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID JOIN PAYMENTS P ON P.ORDER_ID = O.ID"));
	}

	@Test
	void aJoinStillCannotBeCombinedWithGroupBy() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> PullSourceShapeValidator.validateEligible(
				"SELECT C.ID, COUNT(O.ID) FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID GROUP BY C.ID"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("GROUP BY"), "got: " + ex.getLocalizedMessage());
	}

	// --- usesOuterJoin --------------------------------------------------------------------------------

	@Test
	void usesOuterJoinIsFalseForAPlainTable() {
		Assertions.assertFalse(PullSourceShapeValidator.usesOuterJoin("SELECT * FROM CUSTOMER"));
	}

	@Test
	void usesOuterJoinIsFalseForABareOrInnerJoin() {
		Assertions.assertFalse(PullSourceShapeValidator.usesOuterJoin("SELECT * FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID"));
		Assertions.assertFalse(PullSourceShapeValidator.usesOuterJoin("SELECT * FROM CUSTOMER C INNER JOIN ORDERS O ON O.CUSTOMER_ID = C.ID"));
	}

	@Test
	void usesOuterJoinIsTrueForLeftRightAndFullOuterJoin() {
		Assertions.assertTrue(PullSourceShapeValidator.usesOuterJoin("SELECT * FROM CUSTOMER C LEFT JOIN ORDERS O ON O.CUSTOMER_ID = C.ID"));
		Assertions.assertTrue(PullSourceShapeValidator.usesOuterJoin("SELECT * FROM CUSTOMER C LEFT OUTER JOIN ORDERS O ON O.CUSTOMER_ID = C.ID"));
		Assertions.assertTrue(PullSourceShapeValidator.usesOuterJoin("SELECT * FROM CUSTOMER C RIGHT JOIN ORDERS O ON O.CUSTOMER_ID = C.ID"));
		Assertions.assertTrue(PullSourceShapeValidator.usesOuterJoin("SELECT * FROM CUSTOMER C FULL OUTER JOIN ORDERS O ON O.CUSTOMER_ID = C.ID"));
	}

	@Test
	void usesOuterJoinIgnoresTheWordInsideAStringLiteral() {
		Assertions.assertFalse(PullSourceShapeValidator.usesOuterJoin("SELECT * FROM CUSTOMER WHERE NAME = 'LEFT JOIN THIS'"));
	}

	// --- appendKeyFilter --------------------------------------------------------------------------------

	@Test
	void appendKeyFilterAddsAWhereClauseWhenNoneExists() throws BroadSQLException {
		String result = PullSourceShapeValidator.appendKeyFilter("SELECT * FROM CUSTOMER", "ID");
		Assertions.assertEquals("SELECT * FROM CUSTOMER WHERE ID > ?", result);
	}

	@Test
	void appendKeyFilterExtendsAnExistingWhereClause() throws BroadSQLException {
		String result = PullSourceShapeValidator.appendKeyFilter("SELECT * FROM CUSTOMER WHERE COUNTRY = 'FR'", "ID");
		Assertions.assertEquals("SELECT * FROM CUSTOMER WHERE COUNTRY = 'FR' AND (ID > ?)", result);
	}

	@Test
	void appendKeyFilterWorksAfterAJoinWithNoWhereClause() throws BroadSQLException {
		String result = PullSourceShapeValidator.appendKeyFilter(
				"SELECT C.ID AS CID, O.ID AS OID FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID", "OID");
		Assertions.assertEquals(
				"SELECT C.ID AS CID, O.ID AS OID FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID WHERE OID > ?", result);
	}

	@Test
	void appendKeyFilterExtendsAnExistingWhereClauseAfterAJoin() throws BroadSQLException {
		String result = PullSourceShapeValidator.appendKeyFilter(
				"SELECT C.ID AS CID, O.ID AS OID FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID WHERE C.COUNTRY = 'FR'", "OID");
		Assertions.assertEquals(
				"SELECT C.ID AS CID, O.ID AS OID FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID WHERE C.COUNTRY = 'FR' AND (OID > ?)",
				result);
	}

	@Test
	void appendKeyFilterRejectsANonIdentifierKeyColumn() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullSourceShapeValidator.appendKeyFilter("SELECT * FROM CUSTOMER", "ID; DROP TABLE CUSTOMER"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("Invalid KEY column"), "got: " + ex.getLocalizedMessage());
	}
}
