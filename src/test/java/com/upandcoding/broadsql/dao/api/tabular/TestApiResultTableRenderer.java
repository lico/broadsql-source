package com.upandcoding.broadsql.dao.api.tabular;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestApiResultTableRenderer {

	@Test
	void rendersASeparatorLineHeaderSeparatorThenOneLinePerRow() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
		String rendered = ApiResultTableRenderer.render(table);
		String[] lines = rendered.split("\n", -1);

		Assertions.assertTrue(lines[0].matches("\\|-+\\|-+\\|"), "first line must be a dash separator starting and ending with |: " + lines[0]);
		Assertions.assertTrue(lines[1].startsWith("|"), "header line must start with |: " + lines[1]);
		Assertions.assertTrue(lines[1].contains("id"));
		Assertions.assertTrue(lines[1].contains("name"));
		Assertions.assertTrue(lines[2].matches("\\|-+\\|-+\\|"), "third line must be another dash separator starting and ending with |: " + lines[2]);
		Assertions.assertTrue(lines[3].contains("1"));
		Assertions.assertTrue(lines[3].contains("Alice"));
		Assertions.assertTrue(lines[4].contains("2"));
		Assertions.assertTrue(lines[4].contains("Bob"));
		Assertions.assertTrue(lines[5].matches("\\|-+\\|-+\\|"), "must end with a bottom dash separator after the final data row: " + lines[5]);
	}

	@Test
	void emitsABottomBorderAfterTheFinalDataRow() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[{\"id\":1,\"name\":\"Alice\"}]");
		String rendered = ApiResultTableRenderer.render(table);
		String[] lines = rendered.split("\n", -1);
		// separator, header, separator, data, bottom separator, trailing empty split segment
		Assertions.assertEquals(6, lines.length, "expected top separator, header, header separator, one data row, and a bottom separator: " + rendered);
		Assertions.assertEquals(lines[0], lines[4], "the bottom separator must match the top separator: " + rendered);
	}

	@Test
	void everyRenderedLineStartsAndEndsWithTheSeparator() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
		String rendered = ApiResultTableRenderer.render(table);
		String[] lines = rendered.split("\n", -1);
		for (int i = 0; i < lines.length - 1; i++) {
			Assertions.assertTrue(lines[i].startsWith("|"), "line " + i + " must start with |: " + lines[i]);
			Assertions.assertTrue(lines[i].endsWith("|"), "line " + i + " must end with |: " + lines[i]);
		}
	}

	@Test
	void columnsAreWidePenoughToFitTheLongestValueOrHeader() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[{\"name\":\"A very long value indeed\"}]");
		String rendered = ApiResultTableRenderer.render(table);
		Assertions.assertTrue(rendered.contains("A very long value indeed"));
	}

	@Test
	void aMissingOrNullCellRendersBlankNeverTheWordNull() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[{\"id\":1,\"name\":\"Alice\"},{\"id\":2}]");
		String rendered = ApiResultTableRenderer.render(table);
		Assertions.assertFalse(rendered.toLowerCase().contains("null"), "a missing/null cell must never render the literal text 'null': " + rendered);
	}

	@Test
	void anEmptyArrayRendersNoColumnsPlaceholderRatherThanAnEmptyOrBrokenTable() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("[]");
		String rendered = ApiResultTableRenderer.render(table);
		Assertions.assertEquals("(no columns)\n", rendered);
	}

	@Test
	void aSingleObjectRendersAsAOneRowTable() {
		ApiResultTable table = ApiJsonTabularizer.tabularize("{\"id\":7,\"name\":\"Grace\"}");
		String rendered = ApiResultTableRenderer.render(table);
		String[] lines = rendered.split("\n", -1);
		// separator, header, separator, one data row, bottom separator
		Assertions.assertEquals(5, lines.length - 1, "expected exactly one data row plus header/separator/bottom-border lines, got: " + rendered);
		Assertions.assertTrue(lines[3].contains("7"));
		Assertions.assertTrue(lines[3].contains("Grace"));
		Assertions.assertTrue(lines[4].matches("\\|-+\\|-+\\|"), "must end with a bottom dash separator: " + lines[4]);
	}

	// ------------------------------------------------------------------------------------------
	// API Quality and UX Consolidation sprint: renderCatalog() truncates for display only (catalog/
	// navigation tables); render() (actual API business results) never does.
	// ------------------------------------------------------------------------------------------

	@Test
	void renderCatalogEllipsizesALongCellButRenderNeverDoes() {
		String longValue = "x".repeat(200);
		ApiResultTable table = new ApiResultTable(List.of("NAME"), List.of(Map.of("NAME", longValue)), true, null);

		String catalogRendered = ApiResultTableRenderer.renderCatalog(table);
		Assertions.assertFalse(catalogRendered.contains(longValue), "a catalog table must ellipsize a long cell: " + catalogRendered);
		Assertions.assertTrue(catalogRendered.contains("..."), catalogRendered);

		String businessRendered = ApiResultTableRenderer.render(table);
		Assertions.assertTrue(businessRendered.contains(longValue), "an actual API business result must never be truncated: " + businessRendered);
	}

	@Test
	void renderCatalogLeavesAShortCellUntouched() {
		ApiResultTable table = new ApiResultTable(List.of("NAME"), List.of(Map.of("NAME", "short")), true, null);
		String rendered = ApiResultTableRenderer.renderCatalog(table);
		Assertions.assertTrue(rendered.contains("short"));
		Assertions.assertFalse(rendered.contains("..."));
	}

	@Test
	void renderCatalogNeverMutatesTheOriginalTablePassedIn() {
		String longValue = "y".repeat(200);
		ApiResultTable table = new ApiResultTable(List.of("NAME"), List.of(Map.of("NAME", longValue)), true, null);

		ApiResultTableRenderer.renderCatalog(table);

		Assertions.assertEquals(longValue, table.getRows().get(0).get("NAME"), "renderCatalog must never mutate the ApiResultTable it was given");
	}
}
