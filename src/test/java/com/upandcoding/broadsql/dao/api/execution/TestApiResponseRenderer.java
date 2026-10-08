package com.upandcoding.broadsql.dao.api.execution;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.http.ApiHttpResponse;

/**
 * API Quality and UX Consolidation sprint: the default display mode changed from "table when the
 * shape allows it" to {@link ApiResultDisplayMode#LIST} (a complete, structure-preserving vertical
 * view - see {@link com.upandcoding.broadsql.dao.api.tabular.ApiJsonListRenderer}). {@code TABLE} keeps
 * every byte of the pre-sprint "tabular by default" behavior, now reached only by asking for it
 * explicitly - the tests below that used to assert that behavior "by default" now request
 * {@link ApiResultDisplayMode#TABLE} explicitly instead (renamed accordingly), so the underlying
 * table-rendering behavior itself stays regression-tested unchanged.
 */
class TestApiResponseRenderer {

	@Test
	void rawModePrettyPrintsAJsonObject() {
		ApiExecutionResult result = result(200, "application/json", "{\"id\":1,\"name\":\"Alice\"}");
		String rendered = ApiResponseRenderer.render(result, ApiResultDisplayMode.RAW);
		Assertions.assertTrue(rendered.contains("\"id\": 1"), "must be pretty-printed, not the compact form");
		Assertions.assertTrue(rendered.contains("HTTP 200"));
	}

	@Test
	void rawModePrettyPrintsAJsonArray() {
		ApiExecutionResult result = result(200, "application/json", "[{\"id\":1},{\"id\":2}]");
		String rendered = ApiResponseRenderer.render(result, ApiResultDisplayMode.RAW);
		Assertions.assertTrue(rendered.contains("[\n"));
	}

	@Test
	void rendersAJsonPrimitive() {
		ApiExecutionResult result = result(200, "application/json", "42");
		Assertions.assertTrue(ApiResponseRenderer.render(result).contains("42"));
	}

	@Test
	void fallsBackToRawTextWhenContentTypeClaimsJsonButItIsNotValid() {
		// Must hold in every mode - looksJson()'s gate and the mode-specific JsonSyntaxException fallback
		// both apply before any mode-specific rendering runs. Checked here with the default (LIST) mode.
		ApiExecutionResult result = result(200, "application/json", "not actually json {{{");
		String rendered = ApiResponseRenderer.render(result);
		Assertions.assertTrue(rendered.contains("not actually json {{{"), "a formatting issue must fall back to raw text, never throw");
	}

	@Test
	void rendersPlainTextResponsesAsIs() {
		ApiExecutionResult result = result(200, "text/plain", "hello world");
		Assertions.assertTrue(ApiResponseRenderer.render(result).contains("hello world"));
	}

	@Test
	void showsSafeMetadataForABinaryContentType() {
		ApiExecutionResult result = result(200, "application/octet-stream", " binarydata");
		String rendered = ApiResponseRenderer.render(result);
		Assertions.assertTrue(rendered.contains("binary response"));
		Assertions.assertTrue(rendered.contains("application/octet-stream"));
	}

	@Test
	void headResponseWithNoBodyShowsHeadersWithoutABodySection() {
		ApiExecutionResult result = result(200, "application/json", "");
		String rendered = ApiResponseRenderer.render(result);
		Assertions.assertTrue(rendered.contains("HTTP 200"));
		Assertions.assertTrue(rendered.contains("Content-Type: application/json"));
	}

	@Test
	void includesTheSafeUrlAndMethod() {
		ApiExecutionResult result = result(200, "application/json", "{}");
		Assertions.assertTrue(ApiResponseRenderer.render(result).startsWith("GET https://example.com/data?api_key=******"));
	}

	// ------------------------------------------------------------------------------------------
	// API Quality and UX Consolidation sprint: LIST is the new default.
	// ------------------------------------------------------------------------------------------

	@Test
	void defaultModeIsListNotTableForAnArrayOfObjects() {
		ApiExecutionResult result = result(200, "application/json", "[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
		String rendered = ApiResponseRenderer.render(result);
		Assertions.assertTrue(rendered.contains("[1]"), "the default must be the LIST view's indexed blocks: " + rendered);
		Assertions.assertTrue(rendered.contains("id   : 1") || rendered.contains("id : 1"), rendered);
		Assertions.assertFalse(rendered.contains("row."), "the default must not show the TABLE mode's row-count footer: " + rendered);
	}

	@Test
	void defaultModeIsListNotTableForASingleObject() {
		ApiExecutionResult result = result(200, "application/json", "{\"id\":7,\"name\":\"Grace\"}");
		String rendered = ApiResponseRenderer.render(result);
		Assertions.assertTrue(rendered.contains("Grace"));
		Assertions.assertFalse(rendered.contains("row."), "the default must not show the TABLE mode's row-count footer: " + rendered);
		Assertions.assertFalse(rendered.contains("|"), "the default must not be a bordered table: " + rendered);
	}

	@Test
	void sniffsJsonEvenWithoutADeclaredContentTypeInListMode() {
		ApiExecutionResult result = result(200, null, "{\"id\":1}");
		String rendered = ApiResponseRenderer.render(result);
		Assertions.assertTrue(rendered.contains("id"), "sniffed JSON without a declared Content-Type must still be recognized: " + rendered);
	}

	// ------------------------------------------------------------------------------------------
	// TABLE mode - explicit opt-in now, but every byte of the pre-sprint "tabular by default"
	// behavior is preserved when actually requested.
	// ------------------------------------------------------------------------------------------

	@Test
	void explicitTableModeRendersAnArrayOfObjectsAsATableWithARowCountFooter() {
		ApiExecutionResult result = result(200, "application/json", "[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
		String rendered = ApiResponseRenderer.render(result, ApiResultDisplayMode.TABLE);
		Assertions.assertTrue(rendered.contains("id"));
		Assertions.assertTrue(rendered.contains("name"));
		Assertions.assertTrue(rendered.contains("Alice"));
		Assertions.assertTrue(rendered.contains("2 rows."), "expected a row-count footer: " + rendered);
		Assertions.assertFalse(rendered.contains("\"id\": 1"), "a tabular body must not also be pretty-printed as JSON: " + rendered);
	}

	@Test
	void explicitTableModeRendersASingleObjectAsAOneRowTable() {
		ApiExecutionResult result = result(200, "application/json", "{\"id\":7,\"name\":\"Grace\"}");
		String rendered = ApiResponseRenderer.render(result, ApiResultDisplayMode.TABLE);
		Assertions.assertTrue(rendered.contains("Grace"));
		Assertions.assertTrue(rendered.contains("1 row."), "expected a singular row-count footer: " + rendered);
	}

	@Test
	void explicitTableModeSniffsJsonEvenWithoutADeclaredContentType() {
		ApiExecutionResult result = result(200, null, "{\"id\":1}");
		String rendered = ApiResponseRenderer.render(result, ApiResultDisplayMode.TABLE);
		Assertions.assertTrue(rendered.contains("id"), "sniffed JSON must still feed the tabular pipeline: " + rendered);
		Assertions.assertTrue(rendered.contains("1 row."), "expected the tabular row-count footer: " + rendered);
	}

	@Test
	void explicitTableModeStillFallsBackToPrettyJsonForAGenuinelyAmbiguousShape() {
		ApiExecutionResult result = result(200, "application/json", "[{\"id\":1},2,\"three\"]");
		String rendered = ApiResponseRenderer.render(result, ApiResultDisplayMode.TABLE);
		Assertions.assertTrue(rendered.contains("[\n"), "a genuinely ambiguous shape must still be pretty-printed JSON under TABLE: " + rendered);
		Assertions.assertFalse(rendered.contains("row."), "a non-tabular body must never show a row-count footer: " + rendered);
	}

	@Test
	void rawModeForcesPrettyJsonEvenForAnOtherwiseTabularArray() {
		ApiExecutionResult result = result(200, "application/json", "[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]");
		String rendered = ApiResponseRenderer.render(result, ApiResultDisplayMode.RAW);
		Assertions.assertTrue(rendered.contains("\"name\": \"Alice\""), "RAW must force pretty-printed JSON, not a table: " + rendered);
		Assertions.assertFalse(rendered.contains("row."), "RAW must never show the tabular row-count footer: " + rendered);
	}

	@Test
	void listTableAndRawModesEachProduceVisiblyDifferentOutputForTheSameTabularBody() {
		ApiExecutionResult result = result(200, "application/json", "{\"id\":1,\"name\":\"Alice\"}");
		String list = ApiResponseRenderer.render(result, ApiResultDisplayMode.LIST);
		String table = ApiResponseRenderer.render(result, ApiResultDisplayMode.TABLE);
		String raw = ApiResponseRenderer.render(result, ApiResultDisplayMode.RAW);

		Assertions.assertNotEquals(list, table);
		Assertions.assertNotEquals(list, raw);
		Assertions.assertNotEquals(table, raw);
		Assertions.assertTrue(raw.contains("\"name\": \"Alice\""));
		Assertions.assertTrue(table.contains("1 row."));
		Assertions.assertFalse(list.contains("row."));
	}

	@Test
	void renderingInAnyModeNeverMutatesTheUnderlyingRawBody() {
		String rawBody = "[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]";
		ApiExecutionResult result = result(200, "application/json", rawBody);

		ApiResponseRenderer.render(result, ApiResultDisplayMode.LIST);
		ApiResponseRenderer.render(result, ApiResultDisplayMode.TABLE);
		ApiResponseRenderer.render(result, ApiResultDisplayMode.RAW);

		Assertions.assertEquals(rawBody, result.getBody(), "the raw response body must remain exactly intact after rendering in any mode");
		Assertions.assertArrayEquals(rawBody.getBytes(StandardCharsets.UTF_8), result.getBodyBytes(),
				"the raw response bytes must remain exactly intact after rendering in any mode");
	}

	private ApiExecutionResult result(int status, String contentType, String body) {
		Map<String, String> headers = new LinkedHashMap<>();
		if (contentType != null) {
			headers.put("Content-Type", contentType);
		}
		ApiHttpResponse response = new ApiHttpResponse(status, headers, body.getBytes(StandardCharsets.UTF_8), 42);
		return new ApiExecutionResult("GET", "https://example.com/data?api_key=******", response);
	}
}
