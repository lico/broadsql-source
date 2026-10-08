package com.upandcoding.broadsql.controller.shell.sql;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link SqlFormatterService}'s safety guarantee is that it never changes any token's text - only
 * the whitespace between tokens. {@link #assertSameContentTokens} makes that guarantee mechanically
 * checkable: the non-whitespace token sequence (type + exact text) of the input and the output must
 * be identical, for every case in the corpus this sprint's spec calls for (Oracle hints, the legacy
 * {@code (+)} outer join, CTEs, CASE, quoted identifiers, comments, strings that look like SQL, ...).
 */
class TestSqlFormatterService {

	private static void assertSameContentTokens(String original, String formatted) {
		try {
			List<SqlToken> before = significant(SqlTokenizer.tokenize(original));
			List<SqlToken> after = significant(SqlTokenizer.tokenize(formatted));
			Assertions.assertEquals(before.size(), after.size(), "token count changed:\nbefore=" + before + "\nafter=" + after);
			for (int i = 0; i < before.size(); i++) {
				Assertions.assertEquals(before.get(i).getType(), after.get(i).getType(), "token " + i + " type changed");
				Assertions.assertEquals(before.get(i).getText(), after.get(i).getText(), "token " + i + " text changed");
			}
		} catch (SqlLexException e) {
			Assertions.fail(e.getMessage());
		}
	}

	private static List<SqlToken> significant(List<SqlToken> tokens) {
		List<SqlToken> result = new ArrayList<>();
		for (SqlToken t : tokens) {
			if (!t.isType(SqlTokenType.WHITESPACE)) {
				result.add(t);
			}
		}
		return result;
	}

	private static String format(String sql) {
		SqlFormatResult result = SqlFormatterService.format(sql);
		Assertions.assertTrue(result.isSupported(), () -> "expected success, got: " + (result.isSupported() ? "" : result.getReason()));
		return result.getFormattedSql();
	}

	@Test
	void reportsNoQueryWhenInputIsBlank() {
		SqlFormatResult result = SqlFormatterService.format("   ");
		Assertions.assertFalse(result.isSupported());
	}

	@Test
	void preservesTokenContentForASimpleSelect() {
		String sql = "select a.id,a.name,b.code from customer a left join category b on a.category_id=b.id "
				+ "where a.status='ACTIVE' and (a.country='FR' or a.country='BE') order by a.name";
		assertSameContentTokens(sql, format(sql));
	}

	@Test
	void isDeterministicAcrossRepeatedCalls() {
		String sql = "select a.id, a.name from customer a where a.status = 'ACTIVE'";
		Assertions.assertEquals(format(sql), format(sql));
	}

	@Test
	void preservesANestedSubquery() {
		String sql = "SELECT * FROM (SELECT id, name FROM customer WHERE status = 'ACTIVE') t";
		assertSameContentTokens(sql, format(sql));
	}

	@Test
	void preservesACte() {
		String sql = "WITH active AS (SELECT id FROM customer WHERE status = 'ACTIVE') SELECT * FROM active";
		assertSameContentTokens(sql, format(sql));
	}

	@Test
	void preservesACaseExpression() {
		String sql = "SELECT CASE WHEN status = 'A' THEN 'Active' WHEN status = 'I' THEN 'Inactive' ELSE 'Unknown' END FROM customer";
		assertSameContentTokens(sql, format(sql));
	}

	@Test
	void preservesAnAnalyticWindowFunction() {
		String sql = "SELECT id, ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_date DESC) rn FROM orders";
		assertSameContentTokens(sql, format(sql));
	}

	@Test
	void preservesAMergeStatement() {
		String sql = "MERGE INTO customer c USING staging s ON (c.id = s.id) "
				+ "WHEN MATCHED THEN UPDATE SET c.name = s.name WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name)";
		assertSameContentTokens(sql, format(sql));
	}

	@Test
	void preservesTheOracleLegacyOuterJoinOperator() {
		String sql = "SELECT c.id, o.order_id FROM customer c, orders o WHERE c.id = o.customer_id(+)";
		assertSameContentTokens(sql, format(sql));
	}

	@Test
	void preservesAnOracleOptimizerHintExactly() {
		String sql = "SELECT /*+ INDEX(CUSTOMER IDX_CUSTOMER_STATUS) */ * FROM CUSTOMER";
		String formatted = format(sql);
		assertSameContentTokens(sql, formatted);
		Assertions.assertTrue(formatted.contains("/*+ INDEX(CUSTOMER IDX_CUSTOMER_STATUS) */"),
				"hint text must survive verbatim, got:\n" + formatted);
	}

	@Test
	void preservesLineAndBlockComments() {
		String sql = "SELECT id -- the primary key\n, name /* display name */ FROM customer";
		assertSameContentTokens(sql, format(sql));
	}

	@Test
	void preservesQuotedIdentifiers() {
		String sql = "SELECT \"Customer Id\", \"Order#\" FROM \"My Table\"";
		assertSameContentTokens(sql, format(sql));
	}

	@Test
	void preservesEscapedStringLiterals() {
		String sql = "SELECT * FROM customer WHERE name = 'O''Brien'";
		String formatted = format(sql);
		assertSameContentTokens(sql, formatted);
		Assertions.assertTrue(formatted.contains("'O''Brien'"), "escaped literal must survive verbatim, got:\n" + formatted);
	}

	@Test
	void preservesAStringLiteralThatLooksLikeSql() {
		String sql = "INSERT INTO log (message) VALUES ('SELECT * FROM secret WHERE 1=1')";
		String formatted = format(sql);
		assertSameContentTokens(sql, formatted);
		Assertions.assertTrue(formatted.contains("'SELECT * FROM secret WHERE 1=1'"), "string content must survive verbatim, got:\n" + formatted);
	}

	@Test
	void preservesSupportedDmlStatements() {
		assertSameContentTokens("UPDATE customer SET status = 'ACTIVE' WHERE id = 1", format("UPDATE customer SET status = 'ACTIVE' WHERE id = 1"));
		assertSameContentTokens("DELETE FROM customer WHERE id = 1", format("DELETE FROM customer WHERE id = 1"));
		assertSameContentTokens("INSERT INTO customer (id, name) VALUES (1, 'Alice')", format("INSERT INTO customer (id, name) VALUES (1, 'Alice')"));
	}

	@Test
	void leavesInputUnchangedWhenTokenizationFails() {
		String unterminated = "SELECT * FROM customer WHERE name = 'unterminated";
		SqlFormatResult result = SqlFormatterService.format(unterminated);
		Assertions.assertFalse(result.isSupported());
		Assertions.assertNull(result.getFormattedSql());
	}

	@Test
	void startsANewIndentedLineForEachTopLevelProjectionColumn() {
		String formatted = format("SELECT a, b, c FROM t");
		Assertions.assertTrue(formatted.contains("a,\n    b,\n    c"), "got:\n" + formatted);
	}

	@Test
	void doesNotBreakInsideAFunctionCallsArgumentList() {
		String formatted = format("SELECT COUNT(a, b)");
		Assertions.assertFalse(formatted.contains("\n"), "a function call's own argument list should stay on one line, got:\n" + formatted);
	}
}
