package com.upandcoding.broadsql.dao.api.tabular;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * Turns a raw JSON API response body into an {@link ApiResultTable}, for
 * {@link com.upandcoding.broadsql.dao.api.execution.ApiResponseRenderer} - docs/BroadSQL XT02 overnight batch
 * plan, sub-sprint 6 ("API results as tables"). Deliberately console-rendering-scoped: no numeric/date
 * typing, no flattening of nested structures, no persistence - see {@link ApiResultTable}.
 *
 * <p>Shape rules (all intentional, not corner cases to "improve" later):
 * <ul>
 *   <li>Array of objects -&gt; columns are the deterministic union of every element's top-level keys, in
 *       first-seen order; a row missing a key simply has no entry for it (rendered as an empty cell, never
 *       shifting other columns).</li>
 *   <li>Single object -&gt; a one-row table.</li>
 *   <li>Empty array -&gt; tabular, zero rows, zero columns - there is nothing to infer columns from, this is
 *       not treated as a fallback.</li>
 *   <li>Array of primitives (or {@code null}s) -&gt; a single synthetic {@code VALUE} column, one row per
 *       element.</li>
 *   <li>A nested object/array cell value is rendered as compact (non-pretty) JSON text - never flattened
 *       into sub-columns, never exploded into extra rows.</li>
 *   <li>Mixed scalar types within a column are stringified uniformly (no coercion or validation here -
 *       that is sub-sprint 7's concern when materializing into H2/XLSX, not console display).</li>
 *   <li>Not tabular (the two fallback cases, both rendered as before by the caller): the body is not valid
 *       JSON at all, or the root array mixes objects with non-objects (or with arrays) - a genuinely
 *       ambiguous shape, not guessed at.</li>
 * </ul>
 *
 * <p>The original raw body is always preserved on the returned {@link ApiResultTable} via
 * {@link ApiResultTable#getRawJson()}, tabular or not - it is never discarded, re-serialized, or
 * reconstructed from the parsed structure.
 */
public final class ApiJsonTabularizer {

	private static final String VALUE_COLUMN = "VALUE";

	private static final Gson COMPACT_GSON = new Gson();

	private ApiJsonTabularizer() {
	}

	/**
	 * Parses {@code rawJson} in Gson's <b>strict</b> mode - unlike {@link JsonParser#parseString}, which
	 * always parses leniently regardless of caller intent (see its own Javadoc: "The JSON data is parsed
	 * in lenient mode"). Lenient mode accepts several documented deviations from the JSON specification
	 * (unquoted/single-quoted keys, a trailing comma before {@code }}/{@code ]}, bare/malformed numbers,
	 * ...); before this fix, {@code {a:1}} and {@code [1,]} were silently accepted and classified as
	 * tabular, contradicting this class's own documented "invalid JSON falls back to non-tabular/raw
	 * rendering" contract (SPRINT XT02 verification finding 8). Uses Gson's own
	 * {@link JsonReader#setStrictness(Strictness)} (available since Gson 2.11, part of this project's
	 * 2.13.2) rather than a hand-written parser, per the fix's own constraint. Also rejects trailing data
	 * after the first JSON value, mirroring {@link JsonParser#parseReader(java.io.Reader)}'s own behavior
	 * (the {@link JsonReader}-accepting overload used here does not check this itself - see its Javadoc).
	 */
	public static ApiResultTable tabularize(String rawJson) {
		JsonElement root;
		try {
			JsonReader strictReader = new JsonReader(new StringReader(rawJson));
			strictReader.setStrictness(Strictness.STRICT);
			root = JsonParser.parseReader(strictReader);
			if (root != null && !root.isJsonNull() && strictReader.peek() != JsonToken.END_DOCUMENT) {
				return notTabular(rawJson);
			}
		} catch (JsonParseException | IllegalStateException | java.io.IOException e) {
			return notTabular(rawJson);
		}
		if (root == null || root.isJsonNull()) {
			return notTabular(rawJson);
		}
		if (root.isJsonObject()) {
			return tabularizeSingleObject(root.getAsJsonObject(), rawJson);
		}
		if (root.isJsonArray()) {
			return tabularizeArray(root.getAsJsonArray(), rawJson);
		}
		// A bare top-level JSON primitive (e.g. "42" or "true") has nothing to tabularize -
		// falls back to the existing pretty-print/raw-text rendering, unchanged.
		return notTabular(rawJson);
	}

	private static ApiResultTable tabularizeSingleObject(JsonObject object, String rawJson) {
		List<String> columns = new ArrayList<>(object.keySet());
		Map<String, String> row = new LinkedHashMap<>();
		for (String column : columns) {
			row.put(column, stringify(object.get(column)));
		}
		List<Map<String, String>> rows = new ArrayList<>();
		rows.add(row);
		return new ApiResultTable(columns, rows, true, rawJson);
	}

	private static ApiResultTable tabularizeArray(JsonArray array, String rawJson) {
		if (array.isEmpty()) {
			return new ApiResultTable(new ArrayList<>(), new ArrayList<>(), true, rawJson);
		}

		boolean allObjects = true;
		boolean allPrimitiveLike = true;
		for (JsonElement element : array) {
			if (!element.isJsonObject()) {
				allObjects = false;
			}
			if (element.isJsonObject() || element.isJsonArray()) {
				allPrimitiveLike = false;
			}
		}

		if (allObjects) {
			return tabularizeArrayOfObjects(array, rawJson);
		}
		if (allPrimitiveLike) {
			return tabularizeArrayOfPrimitives(array, rawJson);
		}
		// Root array mixes objects with non-objects (or contains a nested array at the top level) -
		// a genuinely ambiguous shape; falls back to the existing pretty-print rendering, unchanged.
		return notTabular(rawJson);
	}

	private static ApiResultTable tabularizeArrayOfObjects(JsonArray array, String rawJson) {
		Set<String> columnSet = new LinkedHashSet<>();
		for (JsonElement element : array) {
			columnSet.addAll(element.getAsJsonObject().keySet());
		}
		List<String> columns = new ArrayList<>(columnSet);

		List<Map<String, String>> rows = new ArrayList<>();
		for (JsonElement element : array) {
			JsonObject object = element.getAsJsonObject();
			Map<String, String> row = new LinkedHashMap<>();
			for (String column : columns) {
				if (object.has(column)) {
					row.put(column, stringify(object.get(column)));
				}
				// A key absent from this particular row is left out of the map entirely - the
				// renderer shows an empty cell for it, and no other column is ever shifted.
			}
			rows.add(row);
		}
		return new ApiResultTable(columns, rows, true, rawJson);
	}

	private static ApiResultTable tabularizeArrayOfPrimitives(JsonArray array, String rawJson) {
		List<String> columns = List.of(VALUE_COLUMN);
		List<Map<String, String>> rows = new ArrayList<>();
		for (JsonElement element : array) {
			Map<String, String> row = new LinkedHashMap<>();
			row.put(VALUE_COLUMN, stringify(element));
			rows.add(row);
		}
		return new ApiResultTable(columns, rows, true, rawJson);
	}

	/** {@code null} for a JSON {@code null} (rendered as an empty cell); the raw scalar text for a primitive; compact (non-pretty) JSON text for a nested object/array - never flattened, never exploded. */
	private static String stringify(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return null;
		}
		if (value.isJsonPrimitive()) {
			return value.getAsString();
		}
		return COMPACT_GSON.toJson(value);
	}

	private static ApiResultTable notTabular(String rawJson) {
		return new ApiResultTable(new ArrayList<>(), new ArrayList<>(), false, rawJson);
	}
}
