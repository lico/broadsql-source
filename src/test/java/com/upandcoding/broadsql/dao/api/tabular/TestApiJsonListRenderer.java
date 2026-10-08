package com.upandcoding.broadsql.dao.api.tabular;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * API Quality and UX Consolidation sprint, sections 32-38: {@link ApiJsonListRenderer}, the new
 * default JSON-body renderer - completeness (every field, however deeply nested, appears; nothing is
 * ever truncated or selected away) is the one hard rule these tests exist to prove.
 */
class TestApiJsonListRenderer {

	@Test
	void rendersAFlatObjectAsFieldValueLines() {
		String rendered = render("{\"id\":1482,\"name\":\"Widget A\",\"status\":\"ACTIVE\"}");
		Assertions.assertTrue(rendered.contains("id"), rendered);
		Assertions.assertTrue(rendered.contains("1482"), rendered);
		Assertions.assertTrue(rendered.contains("Widget A"), rendered);
		Assertions.assertTrue(rendered.contains("ACTIVE"), rendered);
	}

	@Test
	void fieldLabelsAreAlignedToTheLongestLabelInThatObject() {
		String rendered = render("{\"id\":1,\"description\":\"x\"}");
		String[] lines = rendered.split("\n");
		// "id" is padded out to the width of "description" (11 chars) before " : ".
		Assertions.assertTrue(lines[0].startsWith("id          : ") || lines[1].startsWith("id          : "), rendered);
	}

	@Test
	void completenessEveryTopLevelFieldAppears() {
		String rendered = render("{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5}");
		for (String key : new String[] { "a", "b", "c", "d", "e" }) {
			Assertions.assertTrue(rendered.contains(key), "missing field '" + key + "': " + rendered);
		}
	}

	@Test
	void multipleTopLevelArrayItemsRenderAsSeparateIndexedBlocks() {
		String rendered = render("[{\"id\":1,\"name\":\"Widget A\"},{\"id\":2,\"name\":\"Widget B\"}]");
		Assertions.assertTrue(rendered.contains("[1]"), rendered);
		Assertions.assertTrue(rendered.contains("[2]"), rendered);
		Assertions.assertTrue(rendered.contains("Widget A"), rendered);
		Assertions.assertTrue(rendered.contains("Widget B"), rendered);
		Assertions.assertTrue(rendered.indexOf("[1]") < rendered.indexOf("Widget A"), "block [1] must come before its own fields: " + rendered);
		Assertions.assertTrue(rendered.indexOf("Widget A") < rendered.indexOf("[2]"), "item 1's fields must come before block [2]: " + rendered);
	}

	@Test
	void aNestedObjectFieldBecomesAHeaderWithItsOwnFieldsIndentedUnderIt() {
		String rendered = render("{\"id\":1482,\"owner\":{\"id\":91,\"displayName\":\"John Smith\"}}");
		Assertions.assertTrue(rendered.contains("owner"), rendered);
		Assertions.assertTrue(rendered.contains("displayName"), rendered);
		Assertions.assertTrue(rendered.contains("John Smith"), rendered);
		int ownerLine = indexOfLineStartingWith(rendered, "owner");
		int nestedFieldLine = indexOfLineStartingWith(rendered, "  displayName");
		Assertions.assertTrue(ownerLine >= 0 && nestedFieldLine >= 0, rendered);
		Assertions.assertTrue(ownerLine < nestedFieldLine, "the nested field must appear after, and indented under, its parent header: " + rendered);
	}

	@Test
	void aNestedArrayOfPrimitivesRendersAsIndexedEntries() {
		String rendered = render("{\"id\":1,\"labels\":[\"important\",\"customer\"]}");
		Assertions.assertTrue(rendered.contains("labels"), rendered);
		Assertions.assertTrue(rendered.contains("[1]"), rendered);
		Assertions.assertTrue(rendered.contains("important"), rendered);
		Assertions.assertTrue(rendered.contains("[2]"), rendered);
		Assertions.assertTrue(rendered.contains("customer"), rendered);
	}

	@Test
	void aNestedArrayOfObjectsRendersEachElementsFieldsInFull() {
		String rendered = render("{\"permissions\":[{\"user\":\"john\",\"level\":\"read\"},{\"user\":\"mary\",\"level\":\"write\"}]}");
		Assertions.assertTrue(rendered.contains("permissions"), rendered);
		Assertions.assertTrue(rendered.contains("john"), rendered);
		Assertions.assertTrue(rendered.contains("read"), rendered);
		Assertions.assertTrue(rendered.contains("mary"), rendered);
		Assertions.assertTrue(rendered.contains("write"), rendered);
	}

	@Test
	void aLongStringValueIsNeverTruncated() {
		String longValue = "x".repeat(500);
		String rendered = render("{\"description\":\"" + longValue + "\"}");
		Assertions.assertTrue(rendered.contains(longValue), "a long value must never be truncated or ellipsized in LIST mode");
	}

	@Test
	void aMultiLineStringValueIndentsItsContinuationLinesUnderTheColon() {
		String rendered = render("{\"description\":\"line one\\nline two\"}");
		Assertions.assertTrue(rendered.contains("line one"), rendered);
		Assertions.assertTrue(rendered.contains("line two"), rendered);
		String[] lines = rendered.split("\n");
		int firstLineIndex = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].contains("line one")) {
				firstLineIndex = i;
				break;
			}
		}
		Assertions.assertTrue(firstLineIndex >= 0 && firstLineIndex + 1 < lines.length, rendered);
		String continuation = lines[firstLineIndex + 1];
		Assertions.assertTrue(continuation.startsWith(" "), "the continuation line must be indented to align under the ':': " + rendered);
		Assertions.assertFalse(continuation.trim().isEmpty() && !continuation.contains("line two"), rendered);
	}

	@Test
	void aTopLevelJsonScalarRendersAsItsOwnValue() {
		Assertions.assertTrue(render("42").contains("42"));
		Assertions.assertTrue(render("true").contains("true"));
		Assertions.assertTrue(render("\"ok\"").contains("ok"));
	}

	@Test
	void aTopLevelJsonNullRendersAsTheLiteralNull() {
		Assertions.assertTrue(render("null").contains("null"));
	}

	@Test
	void aJsonNullFieldValueRendersAsTheLiteralNullNotBlank() {
		String rendered = render("{\"deletedAt\":null}");
		Assertions.assertTrue(rendered.contains("deletedAt"));
		Assertions.assertTrue(rendered.contains("null"), "a JSON null field must render as 'null', not be indistinguishable from an empty string: " + rendered);
	}

	@Test
	void anEmptyObjectRendersAsEmptyMarkerNotADanglingHeader() {
		String rendered = render("{}");
		Assertions.assertTrue(rendered.contains("(empty)"), rendered);
	}

	@Test
	void anEmptyArrayRendersAsEmptyMarker() {
		String rendered = render("[]");
		Assertions.assertTrue(rendered.contains("(empty)"), rendered);
	}

	@Test
	void anEmptyNestedArrayFieldRendersAsEmptyMarkerUnderItsHeader() {
		String rendered = render("{\"labels\":[]}");
		Assertions.assertTrue(rendered.contains("labels"), rendered);
		Assertions.assertTrue(rendered.contains("(empty)"), rendered);
	}

	private int indexOfLineStartingWith(String text, String prefix) {
		String[] lines = text.split("\n");
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].startsWith(prefix)) {
				return i;
			}
		}
		return -1;
	}

	private String render(String json) {
		JsonElement element = JsonParser.parseString(json);
		return ApiJsonListRenderer.render(element);
	}
}
