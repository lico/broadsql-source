package com.upandcoding.broadsql.dao.pull;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Covers {@link PullCommandParser}'s {@code AS H2 MODE APPEND KEY(<column>) [FORCE]} clause - the
 * optional trailing {@code FORCE} keyword (docs/EXPORT_TO_H2.md, "APPEND KEY(...) opened up to joined
 * queries") plus a regression check that plain {@code KEY(<column>)} parsing (pre-existing, previously
 * verified only by a throwaway, uncommitted program per docs/TECHNICAL_CHANGE.md, 28/08/2026) still
 * works. Join-eligibility itself is covered by {@link TestPullSourceShapeValidator}, which
 * {@link PullCommandParser#parse} delegates to for {@code MODE APPEND}.
 */
class TestPullCommandParserAppendKey {

	@Test
	void parsesAppendKeyWithoutForce() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO WORKCOPY.CUSTOMER AS H2 MODE APPEND KEY(ID)", null);

		Assertions.assertEquals(PullStatement.Mode.APPEND, statement.getMode());
		Assertions.assertEquals("ID", statement.getKeyColumn());
		Assertions.assertFalse(statement.isForce());
	}

	@Test
	void parsesAppendKeyWithForce() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO WORKCOPY.CUSTOMER AS H2 MODE APPEND KEY(ID) FORCE", null);

		Assertions.assertEquals(PullStatement.Mode.APPEND, statement.getMode());
		Assertions.assertEquals("ID", statement.getKeyColumn());
		Assertions.assertTrue(statement.isForce());
	}

	@Test
	void forceIsCaseInsensitive() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO WORKCOPY.CUSTOMER AS H2 MODE APPEND KEY(ID) force", null);

		Assertions.assertTrue(statement.isForce());
	}

	@Test
	void overwriteModeIsNeverForced() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO WORKCOPY.CUSTOMER AS H2", null);

		Assertions.assertEquals(PullStatement.Mode.OVERWRITE, statement.getMode());
		Assertions.assertFalse(statement.isForce());
	}

	@Test
	void rejectsGarbageAfterForce() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO WORKCOPY.CUSTOMER AS H2 MODE APPEND KEY(ID) FORCE NOW", null));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("Unexpected text"), "got: " + ex.getLocalizedMessage());
	}

	@Test
	void rejectsAnyOtherWordAfterKey() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO WORKCOPY.CUSTOMER AS H2 MODE APPEND KEY(ID) MAYBE", null));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("only an optional FORCE"), "got: " + ex.getLocalizedMessage());
	}

	@Test
	void parsesAJoinedSourceWithAppendKeyForceEndToEnd() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse(
				"(SELECT C.ID AS CID, O.ID AS OID FROM CUSTOMER C LEFT JOIN ORDERS O ON O.CUSTOMER_ID = C.ID) "
						+ "TO WORKCOPY.CUST_ORDERS AS H2 MODE APPEND KEY(OID) FORCE",
				null);

		Assertions.assertEquals(PullStatement.Mode.APPEND, statement.getMode());
		Assertions.assertEquals("OID", statement.getKeyColumn());
		Assertions.assertTrue(statement.isForce());
	}

	@Test
	void stillRejectsACommaJoinedSourceForAppendKey() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> PullCommandParser.parse(
				"(SELECT * FROM CUSTOMER, ORDERS) TO WORKCOPY.CUST_ORDERS AS H2 MODE APPEND KEY(ID)", null));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("comma-joined"), "got: " + ex.getLocalizedMessage());
	}
}
