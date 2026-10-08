package com.upandcoding.broadsql.controller.shell.sql;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link SqlWildcardExpander}'s parsing/safety logic against a fabricated
 * {@link SqlRelationColumnResolver} (no real database needed) - see
 * {@code TestCommandExpand} for the end-to-end tests against a real H2 in-memory database.
 */
class TestSqlWildcardExpander {

	private static SqlWildcardExpander expanderWith(Map<String, List<String>> columnsByRelation) {
		return new SqlWildcardExpander(relation -> {
			List<String> columns = columnsByRelation.get(relation.toUpperCase());
			if (columns == null) {
				throw new IllegalStateException("relation not found: " + relation);
			}
			return columns;
		});
	}

	@Test
	void expandsANakedStarOverASingleTable() {
		SqlWildcardExpander expander = expanderWith(Map.of("CUSTOMER", List.of("ID", "NAME", "STATUS")));
		WildcardExpansionResult result = expander.expand("SELECT * FROM CUSTOMER WHERE STATUS = 'ACTIVE'");
		Assertions.assertTrue(result.isSupported(), result.getReason());
		Assertions.assertEquals("SELECT ID,\n    NAME,\n    STATUS FROM CUSTOMER WHERE STATUS = 'ACTIVE'", result.getExpandedSql());
	}

	@Test
	void expandsASchemaQualifiedTable() {
		SqlWildcardExpander expander = expanderWith(Map.of("SCHEMA.CUSTOMER", List.of("ID", "NAME")));
		WildcardExpansionResult result = expander.expand("SELECT * FROM SCHEMA.CUSTOMER");
		Assertions.assertTrue(result.isSupported(), result.getReason());
		Assertions.assertEquals("SELECT ID,\n    NAME FROM SCHEMA.CUSTOMER", result.getExpandedSql());
	}

	@Test
	void expandsAnAliasQualifiedStarWithoutAs() {
		SqlWildcardExpander expander = expanderWith(Map.of("CUSTOMER", List.of("ID", "NAME")));
		WildcardExpansionResult result = expander.expand("SELECT c.* FROM CUSTOMER c");
		Assertions.assertTrue(result.isSupported(), result.getReason());
		Assertions.assertEquals("SELECT c.ID,\n    c.NAME FROM CUSTOMER c", result.getExpandedSql());
	}

	@Test
	void expandsAnAliasQualifiedStarWithAs() {
		SqlWildcardExpander expander = expanderWith(Map.of("CUSTOMER", List.of("ID", "NAME")));
		WildcardExpansionResult result = expander.expand("SELECT c.* FROM CUSTOMER AS c");
		Assertions.assertTrue(result.isSupported(), result.getReason());
		Assertions.assertEquals("SELECT c.ID,\n    c.NAME FROM CUSTOMER AS c", result.getExpandedSql());
	}

	@Test
	void preservesAnExpressionAlongsideAnExpandedAliasStar() {
		SqlWildcardExpander expander = expanderWith(Map.of("CUSTOMER", List.of("ID", "NAME")));
		WildcardExpansionResult result = expander.expand("SELECT CURRENT_TIMESTAMP AS RUN_AT, c.* FROM CUSTOMER c");
		Assertions.assertTrue(result.isSupported(), result.getReason());
		Assertions.assertEquals("SELECT CURRENT_TIMESTAMP AS RUN_AT, c.ID,\n    c.NAME FROM CUSTOMER c", result.getExpandedSql());
	}

	@Test
	void preservesColumnOrdinalOrderFromTheResolver() {
		SqlWildcardExpander expander = expanderWith(Map.of("T", List.of("Z_COL", "A_COL", "M_COL")));
		WildcardExpansionResult result = expander.expand("SELECT * FROM T");
		Assertions.assertEquals("SELECT Z_COL,\n    A_COL,\n    M_COL FROM T", result.getExpandedSql());
	}

	@Test
	void expandsAJoinWithAliasQualifiedStarsOnBothSides() {
		SqlWildcardExpander expander = expanderWith(Map.of(
				"CUSTOMER", List.of("CUSTOMER_ID", "NAME"),
				"ORDERS", List.of("ORDER_ID", "CUSTOMER_ID")));
		WildcardExpansionResult result = expander.expand(
				"SELECT c.*, o.ORDER_ID FROM CUSTOMER c JOIN ORDERS o ON o.CUSTOMER_ID = c.CUSTOMER_ID");
		Assertions.assertTrue(result.isSupported(), result.getReason());
		Assertions.assertEquals(
				"SELECT c.CUSTOMER_ID,\n    c.NAME, o.ORDER_ID FROM CUSTOMER c JOIN ORDERS o ON o.CUSTOMER_ID = c.CUSTOMER_ID",
				result.getExpandedSql());
	}

	@Test
	void leavesCountStarCompletelyUntouched() {
		SqlWildcardExpander expander = expanderWith(Map.of("CUSTOMER", List.of("ID")));
		WildcardExpansionResult result = expander.expand("SELECT COUNT(*) FROM CUSTOMER");
		Assertions.assertFalse(result.isSupported());
	}

	@Test
	void leavesAMultiplicationExpressionUntouched() {
		SqlWildcardExpander expander = expanderWith(Map.of("T", List.of("A", "B")));
		WildcardExpansionResult result = expander.expand("SELECT A * B FROM T");
		Assertions.assertFalse(result.isSupported());
	}

	@Test
	void leavesAStarInsideAStringLiteralUntouched() {
		SqlWildcardExpander expander = expanderWith(Map.of("T", List.of("A")));
		WildcardExpansionResult result = expander.expand("SELECT 'a*b' FROM T");
		Assertions.assertFalse(result.isSupported());
	}

	@Test
	void leavesAStarInsideACommentUntouched() {
		SqlWildcardExpander expander = expanderWith(Map.of("T", List.of("A")));
		WildcardExpansionResult result = expander.expand("SELECT A /* x*y */ FROM T");
		Assertions.assertFalse(result.isSupported());
	}

	@Test
	void rejectsANakedStarOverMoreThanOneTable() {
		SqlWildcardExpander expander = expanderWith(Map.of("A", List.of("X"), "B", List.of("Y")));
		WildcardExpansionResult result = expander.expand("SELECT * FROM A JOIN B ON A.X = B.Y");
		Assertions.assertFalse(result.isSupported());
		Assertions.assertTrue(result.getReason().toLowerCase().contains("more than one"), result.getReason());
	}

	@Test
	void rejectsAnUnresolvableAlias() {
		SqlWildcardExpander expander = expanderWith(Map.of("CUSTOMER", List.of("ID")));
		WildcardExpansionResult result = expander.expand("SELECT z.* FROM CUSTOMER c");
		Assertions.assertFalse(result.isSupported());
		Assertions.assertTrue(result.getReason().contains("z"), result.getReason());
	}

	@Test
	void rejectsAnAmbiguousDuplicateAlias() {
		SqlWildcardExpander expander = expanderWith(Map.of("A", List.of("X"), "B", List.of("Y")));
		WildcardExpansionResult result = expander.expand("SELECT c.* FROM A c JOIN B c ON 1 = 1");
		Assertions.assertFalse(result.isSupported());
		Assertions.assertTrue(result.getReason().toLowerCase().contains("ambiguous"), result.getReason());
	}

	@Test
	void rejectsADerivedTableFromSource() {
		SqlWildcardExpander expander = expanderWith(Map.of());
		WildcardExpansionResult result = expander.expand("SELECT x.* FROM (SELECT 1 AS A) x");
		Assertions.assertFalse(result.isSupported());
	}

	@Test
	void rejectsWhenMetadataResolutionFails() {
		SqlWildcardExpander expander = expanderWith(Map.of());
		WildcardExpansionResult result = expander.expand("SELECT * FROM NOSUCHTABLE");
		Assertions.assertFalse(result.isSupported());
	}

	@Test
	void rejectsAStatementThatIsNotASelect() {
		SqlWildcardExpander expander = expanderWith(Map.of());
		WildcardExpansionResult result = expander.expand("UPDATE CUSTOMER SET NAME = 'X'");
		Assertions.assertFalse(result.isSupported());
	}

	@Test
	void rejectsAProjectionWithNoExpandableStar() {
		SqlWildcardExpander expander = expanderWith(Map.of("CUSTOMER", List.of("ID")));
		WildcardExpansionResult result = expander.expand("SELECT ID FROM CUSTOMER");
		Assertions.assertFalse(result.isSupported());
	}

	@Test
	void reportsNoQueryWhenInputIsBlank() {
		SqlWildcardExpander expander = expanderWith(Map.of());
		WildcardExpansionResult result = expander.expand("   ");
		Assertions.assertFalse(result.isSupported());
	}
}
