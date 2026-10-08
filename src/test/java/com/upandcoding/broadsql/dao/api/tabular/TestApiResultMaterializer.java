package com.upandcoding.broadsql.dao.api.tabular;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.LastApiExecutionResult;

/**
 * Covers {@link ApiResultMaterializer#materialize} - docs/BroadSQL XT02 overnight batch plan,
 * sub-sprint 7 ("API result export and local snapshot"). Runs against a real H2 database (this class's
 * entire point is what actually lands in the throwaway table), per this project's "verify empirically"
 * convention (CLAUDE.md).
 */
class TestApiResultMaterializer {

	@Test
	void columnsAndRowsAreMaterializedExactly() throws BroadSQLException, SQLException {
		ApiResultTable table = new ApiResultTable(
				List.of("ID", "NAME"),
				List.of(row("ID", "1", "NAME", "Alice"), row("ID", "2", "NAME", "Bob")),
				true, "[{\"ID\":1,\"NAME\":\"Alice\"},{\"ID\":2,\"NAME\":\"Bob\"}]");
		LastApiExecutionResult apiResult = newResult(table);

		try (MaterializedApiResult materialized = ApiResultMaterializer.materialize(apiResult)) {
			ResultSet rs = materialized.getResultSet();
			ResultSetMetaData md = rs.getMetaData();
			Assertions.assertEquals(2, md.getColumnCount());
			Assertions.assertEquals("ID", md.getColumnLabel(1));
			Assertions.assertEquals("NAME", md.getColumnLabel(2));

			Assertions.assertTrue(rs.next());
			Assertions.assertEquals("1", rs.getString("ID"));
			Assertions.assertEquals("Alice", rs.getString("NAME"));
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals("2", rs.getString("ID"));
			Assertions.assertEquals("Bob", rs.getString("NAME"));
			Assertions.assertFalse(rs.next());
		}
	}

	@Test
	void aMissingKeyAndAJsonNullAreBothInsertedAsRealSqlNullNeverTheStringNull() throws BroadSQLException, SQLException {
		Map<String, String> rowWithNullValue = row("ID", "1", "NAME", null);
		Map<String, String> rowMissingName = new LinkedHashMap<>();
		rowMissingName.put("ID", "2");
		// NAME intentionally absent - simulates a row missing a key entirely (ApiJsonTabularizer's own
		// "missing key" case), as opposed to the row above where NAME is present but its value is null.

		ApiResultTable table = new ApiResultTable(List.of("ID", "NAME"), List.of(rowWithNullValue, rowMissingName), true, "[]");
		LastApiExecutionResult apiResult = newResult(table);

		try (MaterializedApiResult materialized = ApiResultMaterializer.materialize(apiResult)) {
			ResultSet rs = materialized.getResultSet();

			Assertions.assertTrue(rs.next());
			String firstName = rs.getString("NAME");
			Assertions.assertTrue(rs.wasNull(), "a JSON null value must be a real SQL NULL, not the string \"null\"");
			Assertions.assertNull(firstName);

			Assertions.assertTrue(rs.next());
			String secondName = rs.getString("NAME");
			Assertions.assertTrue(rs.wasNull(), "a row missing the key entirely must also be a real SQL NULL");
			Assertions.assertNull(secondName);

			Assertions.assertFalse(rs.next());
		}
	}

	@Test
	void anEmptyRowSetStillProducesAQueryableEmptyResultSet() throws BroadSQLException, SQLException {
		ApiResultTable table = new ApiResultTable(List.of("ID", "NAME"), List.of(), true, "[]");
		LastApiExecutionResult apiResult = newResult(table);

		try (MaterializedApiResult materialized = ApiResultMaterializer.materialize(apiResult)) {
			ResultSet rs = materialized.getResultSet();
			Assertions.assertEquals(2, rs.getMetaData().getColumnCount());
			Assertions.assertFalse(rs.next());
		}
	}

	@Test
	void refusesToMaterializeAResultWithNoColumns() {
		ApiResultTable table = new ApiResultTable(List.of(), List.of(), true, "[]");
		LastApiExecutionResult apiResult = newResult(table);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> ApiResultMaterializer.materialize(apiResult));
		Assertions.assertTrue(ex.getMessage().contains("no columns to export"), "got: " + ex.getMessage());
	}

	@Test
	void aColumnNameWithSpecialCharactersSurvivesQuoted() throws BroadSQLException, SQLException {
		ApiResultTable table = new ApiResultTable(List.of("first name", "SELECT"),
				List.of(row("first name", "Alice", "SELECT", "reserved-word-as-column")), true, "[]");
		LastApiExecutionResult apiResult = newResult(table);

		try (MaterializedApiResult materialized = ApiResultMaterializer.materialize(apiResult)) {
			ResultSet rs = materialized.getResultSet();
			Assertions.assertEquals("first name", rs.getMetaData().getColumnLabel(1));
			Assertions.assertEquals("SELECT", rs.getMetaData().getColumnLabel(2));
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals("Alice", rs.getString(1));
			Assertions.assertEquals("reserved-word-as-column", rs.getString(2));
		}
	}

	private static Map<String, String> row(String k1, String v1, String k2, String v2) {
		Map<String, String> row = new LinkedHashMap<>();
		row.put(k1, v1);
		row.put(k2, v2);
		return row;
	}

	private static LastApiExecutionResult newResult(ApiResultTable table) {
		return new LastApiExecutionResult(table, table.getRawJson(), "DEMO", "GET Users", "Test", Instant.now(), 200);
	}
}
