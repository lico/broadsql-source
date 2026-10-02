package com.upandcoding.broadsql.dao;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.listsource.ListSourceResolver;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/**
 * Covers {@link LastQueryResultHolder} being populated as a side effect of a real
 * {@code SELECT} display pass through {@code QueryExtractorToScreen} - the capture side of
 * {@code <@last:column>} (docs/TODO.md, "Previous result's column as SQL input"). Lives in this
 * package (not {@code listsource}) because it needs {@link DatabaseConnection}'s package-private
 * {@code consoleSettings} field, same reason as {@link TestDatabaseConnections}.
 *
 * <p>The lookup side (column resolution, NULL/duplicate/size-limit semantics) is covered separately in
 * {@code TestLastResultListSource}, seeded by hand - this class only proves the real capture wiring
 * (DatabaseConnection -&gt; QueryExtractorToScreen -&gt; LastQueryResultHolder) actually happens, and the
 * "last successful result" lifecycle rules around it (docs/TODO.md, "Query-result lifecycle").
 */
class TestDatabaseConnectionLastQueryResultCapture {

	private DatabaseConnection db;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE CUSTOMER (CUSTOMER_ID VARCHAR(10), NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES ('C001', 'Alice')", "INSERT INTO CUSTOMER VALUES ('C002', 'Bob')");
		db.setCmdLineConsole(new CapturingShellConsole());
		LastQueryResultHolder.set(null);
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
		LastQueryResultHolder.set(null);
	}

	@Test
	void aSuccessfulSelectPopulatesLastQueryResult() throws BroadSQLException {
		db.executeSelectQuery("SELECT CUSTOMER_ID, NAME FROM CUSTOMER ORDER BY CUSTOMER_ID");

		LastQueryResult last = LastQueryResultHolder.get();
		Assertions.assertNotNull(last, "a successful, displayed SELECT must populate LastQueryResultHolder");
		Assertions.assertEquals(List.of("CUSTOMER_ID", "NAME"), last.columnLabels());
		Assertions.assertEquals(2, last.totalRowCount());
		Assertions.assertFalse(last.exceedsLimit());
		Assertions.assertEquals("C001", last.rows().get(0)[0]);
		Assertions.assertEquals("C002", last.rows().get(1)[0]);
	}

	@Test
	void aQueryAliasBecomesTheCapturedColumnLabel() throws BroadSQLException {
		db.executeSelectQuery("SELECT CUSTOMER_ID AS CID FROM CUSTOMER ORDER BY CUSTOMER_ID");

		Assertions.assertEquals(List.of("CID"), LastQueryResultHolder.get().columnLabels());
	}

	@Test
	void sqlNullIsCapturedAsANullEntryNotTheTextNull() throws BroadSQLException {
		db.executeUpdateQuery("INSERT INTO CUSTOMER VALUES (NULL, 'NoId')");

		db.executeSelectQuery("SELECT CUSTOMER_ID FROM CUSTOMER WHERE NAME = 'NoId'");

		String[] row = LastQueryResultHolder.get().rows().get(0);
		Assertions.assertNull(row[0], "a SQL NULL must be captured as a real null, not the text \"null\" or \"NULL\"");
	}

	@Test
	void anEmptyResultIsCapturedAsZeroRows() throws BroadSQLException {
		db.executeSelectQuery("SELECT CUSTOMER_ID FROM CUSTOMER WHERE 1 = 0");

		LastQueryResult last = LastQueryResultHolder.get();
		Assertions.assertNotNull(last);
		Assertions.assertEquals(0, last.totalRowCount());
		Assertions.assertTrue(last.rows().isEmpty());
	}

	@Test
	void aFailedSubsequentQueryPreservesThePreviousSuccessfulResult() throws BroadSQLException {
		db.executeSelectQuery("SELECT CUSTOMER_ID FROM CUSTOMER ORDER BY CUSTOMER_ID");
		LastQueryResult firstResult = LastQueryResultHolder.get();
		Assertions.assertNotNull(firstResult);

		Assertions.assertThrows(BroadSQLException.class, () -> db.executeSelectQuery("SELECT * FROM NO_SUCH_TABLE"));

		Assertions.assertSame(firstResult, LastQueryResultHolder.get(),
				"a failed query must not destroy the previous successful LAST result");
	}

	@Test
	void aResultBeyondTheSafetyLimitIsMarkedAsExceedingWithoutRetainingAllRows() throws BroadSQLException {
		int total = LastQueryResult.MAX_ROWS + 25;
		for (int i = 0; i < total; i++) {
			db.executeUpdateQuery("INSERT INTO CUSTOMER VALUES ('G" + i + "', 'Bulk')");
		}
		// No cap on display for this test - every inserted row must actually be iterated by the
		// extractor for the capture logic (piggybacking on that same iteration) to see them all.
		db.setMaxRowsOnScreen(0);

		db.executeSelectQuery("SELECT CUSTOMER_ID FROM CUSTOMER WHERE NAME = 'Bulk'");

		LastQueryResult last = LastQueryResultHolder.get();
		Assertions.assertTrue(last.exceedsLimit(), "a " + total + "-row result must exceed the " + LastQueryResult.MAX_ROWS + "-row cap");
		Assertions.assertEquals(total, last.totalRowCount(), "the true row count must still be known even once capture itself stopped");
		Assertions.assertTrue(last.rows().size() <= LastQueryResult.MAX_ROWS,
				"no more than MAX_ROWS rows may ever be retained in memory, regardless of the true result size");
	}

	/**
	 * End-to-end proof that exceeding the limit is a hard failure, not silent truncation - a bounded
	 * {@code rows()} list alone ({@link #aResultBeyondTheSafetyLimitIsMarkedAsExceedingWithoutRetainingAllRows})
	 * does not by itself prove {@code <@last:...>} refuses to resolve a (silently incomplete) list from
	 * it; this drives the real, public {@link ListSourceResolver} entry point - the same one
	 * {@code CommandUtils.substituteMacros} uses - against the real capture this class's other tests
	 * exercise, and asserts both that it throws and that it never returns a value list at all (the classic
	 * bug this must not regress into: quietly returning the first {@code MAX_ROWS} values instead of
	 * failing).
	 */
	@Test
	void exceedingTheSafetyLimitMakesLastColumnFailCompletelyRatherThanReturningATruncatedList() throws BroadSQLException {
		int total = LastQueryResult.MAX_ROWS + 25;
		for (int i = 0; i < total; i++) {
			db.executeUpdateQuery("INSERT INTO CUSTOMER VALUES ('G" + i + "', 'Bulk')");
		}
		db.setMaxRowsOnScreen(0);
		db.executeSelectQuery("SELECT CUSTOMER_ID FROM CUSTOMER WHERE NAME = 'Bulk'");
		Assertions.assertTrue(LastQueryResultHolder.get().exceedsLimit(), "precondition: this result must actually exceed the limit");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ListSourceResolver.resolve("last:CUSTOMER_ID").values(),
				"<@last:CUSTOMER_ID> must fail outright once the underlying result exceeds MAX_ROWS - "
						+ "it must never silently resolve to the first MAX_ROWS captured values");
		Assertions.assertTrue(ex.getMessage().contains(String.valueOf(total)),
				"the error must report the true row count (" + total + "), proving it looked at the real size rather than "
						+ "just working off the truncated in-memory list - got: " + ex.getMessage());
	}
}
