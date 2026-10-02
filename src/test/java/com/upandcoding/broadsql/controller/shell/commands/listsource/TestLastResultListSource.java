package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;

/**
 * Covers {@code <@last:column>} (docs/TODO.md, "Previous result's column as SQL input") directly
 * against {@link LastResultListSource}, seeding {@link LastQueryResultHolder} by hand rather than
 * through a real query - the capture side ({@code QueryExtractorToScreen} populating the holder from a
 * real {@code ResultSet}) has its own coverage in {@code TestDatabaseConnectionLastQueryResultCapture}.
 *
 * <p>{@link LastQueryResultHolder} is a JVM-wide static, so every test resets it explicitly in
 * {@code @BeforeEach}/{@code @AfterEach} rather than relying on ordering between test classes.
 */
class TestLastResultListSource {

	@BeforeEach
	@AfterEach
	void resetHolder() {
		LastQueryResultHolder.set(null);
	}

	private static LastQueryResult result(List<String> columnLabels, List<String[]> rows) {
		return new LastQueryResult(columnLabels, rows, rows.size(), "TESTCONN");
	}

	@Test
	void resolvesAColumnByExactLabel() throws BroadSQLException {
		LastQueryResultHolder.set(result(List.of("CUSTOMER_ID"),
				Arrays.<String[]>asList(new String[] { "C001" }, new String[] { "C002" })));

		Assertions.assertEquals(List.of("C001", "C002"), ListSourceResolver.resolve("last:CUSTOMER_ID").values());
	}

	@Test
	void matchesColumnLabelCaseInsensitivelyWhenNoExactMatch() throws BroadSQLException {
		LastQueryResultHolder.set(result(List.of("CUSTOMER_ID"), Arrays.<String[]>asList(new String[] { "C001" })));

		Assertions.assertEquals(List.of("C001"), ListSourceResolver.resolve("last:customer_id").values());
	}

	@Test
	void resolvesAQueryAliasSinceLabelsAreTheVisibleName() throws BroadSQLException {
		// SELECT CUSTOMER_ID AS CID ... - the captured label is already the alias "CID", not the
		// underlying column name, exactly like ResultSetMetaData.getColumnLabel() behaves.
		LastQueryResultHolder.set(result(List.of("CID"), Arrays.<String[]>asList(new String[] { "C001" })));

		Assertions.assertEquals(List.of("C001"), ListSourceResolver.resolve("last:CID").values());
	}

	@Test
	void missingColumnFailsClearlyAndListsAvailableColumns() throws BroadSQLException {
		LastQueryResultHolder.set(result(List.of("ID", "NAME", "STATUS"), Arrays.<String[]>asList(new String[] { "1", "Alice", "OPEN" })));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ListSourceResolver.resolve("last:CUSTOMER_ID").values());
		Assertions.assertTrue(ex.getMessage().contains("CUSTOMER_ID"), "got: " + ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("ID, NAME, STATUS"), "expected available columns listed, got: " + ex.getMessage());
	}

	@Test
	void duplicateColumnLabelsFailClearlyAsAmbiguous() throws BroadSQLException {
		LastQueryResultHolder.set(result(List.of("ID", "ID"), Arrays.<String[]>asList(new String[] { "1", "2" })));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ListSourceResolver.resolve("last:ID").values());
		Assertions.assertTrue(ex.getMessage().toLowerCase().contains("ambiguous"), "got: " + ex.getMessage());
	}

	@Test
	void sqlNullValuesAreSkippedNotRenderedAsText() throws BroadSQLException {
		LastQueryResultHolder.set(result(List.of("CUSTOMER_ID"),
				Arrays.<String[]>asList(new String[] { "C001" }, new String[] { (String) null }, new String[] { "C003" })));

		Assertions.assertEquals(List.of("C001", "C003"), ListSourceResolver.resolve("last:CUSTOMER_ID").values());
	}

	@Test
	void allNullValuesResolveToAnEmptyList() throws BroadSQLException {
		LastQueryResultHolder.set(result(List.of("CUSTOMER_ID"),
				Arrays.<String[]>asList(new String[] { (String) null }, new String[] { (String) null })));

		Assertions.assertEquals(List.of(), ListSourceResolver.resolve("last:CUSTOMER_ID").values());
	}

	@Test
	void duplicateValuesArePreserved() throws BroadSQLException {
		LastQueryResultHolder.set(result(List.of("ID"),
				Arrays.<String[]>asList(new String[] { "C001" }, new String[] { "C001" }, new String[] { "C002" })));

		Assertions.assertEquals(List.of("C001", "C001", "C002"), ListSourceResolver.resolve("last:ID").values());
	}

	@Test
	void leadingZerosAndNumericLookingValuesStayAsText() throws BroadSQLException {
		LastQueryResultHolder.set(result(List.of("ID"), Arrays.<String[]>asList(new String[] { "00123" })));

		Assertions.assertEquals(List.of("00123"), ListSourceResolver.resolve("last:ID").values());
	}

	@Test
	void anEmptyResultResolvesToAnEmptyList() throws BroadSQLException {
		LastQueryResultHolder.set(result(List.of("ID"), List.of()));

		Assertions.assertEquals(List.of(), ListSourceResolver.resolve("last:ID").values());
	}

	@Test
	void noPreviousResultFailsClearly() {
		LastQueryResultHolder.set(null);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ListSourceResolver.resolve("last:ID").values());
		Assertions.assertTrue(ex.getMessage().contains("No previous query result"), "got: " + ex.getMessage());
	}

	@Test
	void aResultExceedingTheSafetyLimitFailsClearlyWithoutTruncating() throws BroadSQLException {
		List<String[]> rows = new ArrayList<>();
		int total = LastQueryResult.MAX_ROWS + 1;
		for (int i = 0; i < total; i++) {
			rows.add(new String[] { "V" + i });
		}
		LastQueryResultHolder.set(result(List.of("ID"), rows));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ListSourceResolver.resolve("last:ID").values());
		Assertions.assertTrue(ex.getMessage().contains(String.valueOf(total)), "got: " + ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("PULL"), "expected a pointer to PULL ... AS H2, got: " + ex.getMessage());
	}

	@Test
	void routesTheLastPrefixCaseInsensitively() throws BroadSQLException {
		LastQueryResultHolder.set(result(List.of("ID"), Arrays.<String[]>asList(new String[] { "1" })));

		Assertions.assertInstanceOf(LastResultListSource.class, ListSourceResolver.resolve("LAST:ID"));
		Assertions.assertInstanceOf(LastResultListSource.class, ListSourceResolver.resolve("last:ID"));
	}
}
