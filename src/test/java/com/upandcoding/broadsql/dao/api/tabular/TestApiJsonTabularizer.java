package com.upandcoding.broadsql.dao.api.tabular;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestApiJsonTabularizer {

	@Test
	void arrayOfUniformObjectsProducesAColumnPerKeyInFirstSeenOrder() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
		Assertions.assertTrue(table.isTabular());
		Assertions.assertEquals(List.of("id", "name"), table.getColumnNames());
		Assertions.assertEquals(2, table.getRows().size());
		Assertions.assertEquals("1", table.getRows().get(0).get("id"));
		Assertions.assertEquals("Alice", table.getRows().get(0).get("name"));
		Assertions.assertEquals("2", table.getRows().get(1).get("id"));
		Assertions.assertEquals("Bob", table.getRows().get(1).get("name"));
	}

	@Test
	void aRowMissingAKeyRendersAnEmptyCellWithoutShiftingOtherColumns() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[{\"id\":1,\"name\":\"Alice\"},{\"id\":2}]");
		Assertions.assertEquals(List.of("id", "name"), table.getColumnNames());
		Map<String, String> secondRow = table.getRows().get(1);
		Assertions.assertEquals("2", secondRow.get("id"));
		Assertions.assertFalse(secondRow.containsKey("name"), "a row missing a key must have no entry for it, not an empty-string placeholder");
	}

	@Test
	void aSingleObjectProducesAOneRowTable() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("{\"id\":7,\"name\":\"Grace\"}");
		Assertions.assertTrue(table.isTabular());
		Assertions.assertEquals(List.of("id", "name"), table.getColumnNames());
		Assertions.assertEquals(1, table.getRows().size());
		Assertions.assertEquals("7", table.getRows().get(0).get("id"));
		Assertions.assertEquals("Grace", table.getRows().get(0).get("name"));
	}

	@Test
	void anEmptyArrayIsTabularWithZeroRowsAndZeroColumns() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[]");
		Assertions.assertTrue(table.isTabular(), "an empty array is tabular, not a fallback");
		Assertions.assertTrue(table.getColumnNames().isEmpty());
		Assertions.assertTrue(table.getRows().isEmpty());
	}

	@Test
	void anArrayOfPrimitivesProducesASingleValueColumn() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[1,2,3]");
		Assertions.assertTrue(table.isTabular());
		Assertions.assertEquals(List.of("VALUE"), table.getColumnNames());
		Assertions.assertEquals(3, table.getRows().size());
		Assertions.assertEquals("1", table.getRows().get(0).get("VALUE"));
		Assertions.assertEquals("2", table.getRows().get(1).get("VALUE"));
		Assertions.assertEquals("3", table.getRows().get(2).get("VALUE"));
	}

	@Test
	void aNestedObjectOrArrayCellIsRenderedAsCompactJsonTextNeverFlattened() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[{\"id\":1,\"tags\":[\"a\",\"b\"],\"meta\":{\"active\":true}}]");
		Map<String, String> row = table.getRows().get(0);
		Assertions.assertEquals("[\"a\",\"b\"]", row.get("tags"), "must be compact, non-pretty JSON text");
		Assertions.assertEquals("{\"active\":true}", row.get("meta"));
	}

	@Test
	void mixedScalarTypesAreStringifiedUniformly() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[{\"v\":1},{\"v\":\"two\"},{\"v\":true},{\"v\":null}]");
		List<Map<String, String>> rows = table.getRows();
		Assertions.assertEquals("1", rows.get(0).get("v"));
		Assertions.assertEquals("two", rows.get(1).get("v"));
		Assertions.assertEquals("true", rows.get(2).get("v"));
		Assertions.assertNull(rows.get(3).get("v"), "a JSON null must render as no value, never the literal text \"null\"");
	}

	@Test
	void notJsonAtAllFallsBackToNonTabular() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("not actually json {{{");
		Assertions.assertFalse(table.isTabular());
		Assertions.assertTrue(table.getColumnNames().isEmpty());
		Assertions.assertTrue(table.getRows().isEmpty());
		Assertions.assertEquals("not actually json {{{", table.getRawJson(), "the raw body must be preserved even for a non-tabular result");
	}

	@Test
	void anArrayMixingObjectsAndNonObjectsFallsBackToNonTabular() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[{\"id\":1},2,\"three\"]");
		Assertions.assertFalse(table.isTabular(), "a genuinely ambiguous shape must fall back, not guess");
		Assertions.assertTrue(table.getColumnNames().isEmpty());
		Assertions.assertTrue(table.getRows().isEmpty());
	}

	@Test
	void aBareTopLevelPrimitiveFallsBackToNonTabular() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("42");
		Assertions.assertFalse(table.isTabular());
		Assertions.assertEquals("42", table.getRawJson());
	}

	@Test
	void theRawJsonIsAlwaysPreservedExactlyAsGiven() {
		String raw = "[{\"id\":1},{\"id\":2}]";
		ApiResultTable table = ApiJsonTabularizer.tabularize(raw);
		Assertions.assertSame(raw, table.getRawJson(), "must be the exact same body, never re-serialized or reconstructed");
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02 verification finding 8: Gson's lenient parsing (the default for
	// JsonParser.parseString) accepts several documented deviations from the JSON specification -
	// unquoted/single-quoted keys, a trailing comma, malformed escapes, ... - and these used to be
	// silently accepted and classified as tabular, contradicting this class's own documented "invalid
	// JSON stays non-tabular/raw" fallback contract. The fix switches to Gson's strict mode
	// (JsonReader#setStrictness(Strictness.STRICT)) rather than a hand-written parser.
	// ------------------------------------------------------------------------------------------

	@Test
	void anUnquotedObjectKeyIsRejectedAsNonTabular() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("{a:1}");
		Assertions.assertFalse(table.isTabular(), "an unquoted key is a lenient-mode-only deviation from the JSON spec and must not be accepted");
		Assertions.assertEquals("{a:1}", table.getRawJson());
	}

	@Test
	void aSingleQuotedObjectKeyIsRejectedAsNonTabular() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("{'a':1}");
		Assertions.assertFalse(table.isTabular(), "single-quoted strings are a lenient-mode-only deviation and must not be accepted");
	}

	@Test
	void aTrailingCommaInAnArrayIsRejectedAsNonTabular() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[1,2,]");
		Assertions.assertFalse(table.isTabular(), "a trailing comma before ']' must not be accepted in strict mode");
	}

	@Test
	void aTrailingCommaInAnObjectIsRejectedAsNonTabular() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("{\"a\":1,}");
		Assertions.assertFalse(table.isTabular(), "a trailing comma before '}' must not be accepted in strict mode");
	}

	@Test
	void aMalformedEscapeSequenceIsRejectedAsNonTabular() {
		String malformed = "{\"a\":\"bad\\qescape\"}";
		ApiResultTable table = ApiJsonTabularizer.tabularize(malformed);
		Assertions.assertFalse(table.isTabular(), "\\q is not a valid JSON escape sequence");
		Assertions.assertEquals(malformed, table.getRawJson());
	}

	@Test
	void aTruncatedObjectIsRejectedAsNonTabular() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("{\"a\":1");
		Assertions.assertFalse(table.isTabular(), "a truncated (unterminated) object must fall back, not throw or hang");
	}

	@Test
	void aTruncatedArrayIsRejectedAsNonTabular() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[1,2");
		Assertions.assertFalse(table.isTabular(), "a truncated (unterminated) array must fall back, not throw or hang");
	}

	@Test
	void trailingDataAfterAValidJsonValueIsRejectedAsNonTabular() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("{\"a\":1} garbage");
		Assertions.assertFalse(table.isTabular(), "trailing data after a complete JSON value must be rejected, not silently ignored");
	}

	@Test
	void anEmptyObjectProducesAOneRowTableWithNoColumns() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("{}");
		Assertions.assertTrue(table.isTabular(), "an empty object is still tabular - a valid edge case, not a fallback");
		Assertions.assertTrue(table.getColumnNames().isEmpty());
		Assertions.assertEquals(1, table.getRows().size());
		Assertions.assertTrue(table.getRows().get(0).isEmpty());
	}

	@Test
	void unicodeCharactersSurviveStrictParsingUnchanged() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("{\"name\":\"café ☕\"}");
		Assertions.assertTrue(table.isTabular());
		Assertions.assertEquals("café ☕", table.getRows().get(0).get("name"));
	}

	@Test
	void jsonNullAtTheTopLevelFallsBackToNonTabular() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("null");
		Assertions.assertFalse(table.isTabular(), "a bare top-level null has nothing to tabularize");
	}

	@Test
	void wellFormedNestedObjectsAndArraysStillTabularizeCorrectlyUnderStrictParsing() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[{\"id\":1,\"child\":{\"nested\":[1,2,3]}}]");
		Assertions.assertTrue(table.isTabular(), "valid, well-formed JSON must still parse correctly under strict mode");
		Assertions.assertEquals("{\"nested\":[1,2,3]}", table.getRows().get(0).get("child"));
	}
}
