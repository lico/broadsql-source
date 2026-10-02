package com.upandcoding.broadsql.dao.api.tabular;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Renders a parsed JSON body as a complete, structure-preserving vertical list - API Quality and UX
 * Consolidation sprint, sections 32-38: the new default display mode for an API execution result
 * (replacing table-when-possible as the default; {@link ApiResultTableRenderer} remains available as
 * an explicit {@code TABLE} choice - see {@code ApiResponseRenderer}).
 *
 * <p><b>Completeness is the one hard rule</b> (sections 33/38): every key present in the parsed JSON
 * gets a line, in encounter order - there is no field-selection logic anywhere in this class to
 * misbehave, by construction. A scalar value is <b>never truncated or ellipsized</b>, however long
 * (unlike {@link ApiResultTableRenderer#renderCatalog}, which exists for a completely different
 * purpose - navigation/catalog tables, not business results; see its own javadoc for why the two must
 * never be confused). A multi-line string value has its continuation lines indented to align under the
 * {@code :} separator, so a long description stays visually attached to its field.
 *
 * <p><b>Nesting</b>: a nested object's own field name becomes a one-line header, with its fields
 * indented two spaces deeper; a nested array's field name becomes a header followed by {@code [1]},
 * {@code [2]}, ... entries at that same deeper indent, each in turn indented two spaces deeper still
 * for an object element's own fields, or rendered inline for a scalar element (section 34). This
 * indent-one-level-deeper-per-nesting-step rule is applied <b>uniformly</b>, including at the top
 * level (an array-of-objects response's {@code [1]}/{@code [2]} entries have their own fields indented
 * two spaces under the index, not flush with it) - a small, deliberate, internally-consistent choice
 * where the sprint brief's own top-level and nested illustrative examples were not literally
 * consistent with each other (the brief itself frames its examples as "conceptually like", not a
 * byte-exact spec).
 *
 * <p><b>A JSON {@code null}</b> renders as the literal text {@code null} - deliberately different from
 * {@link ApiResultTableRenderer}'s "blank cell, never the word null" convention, which exists there
 * specifically to avoid visual confusion with SQL {@code NULL} in a SQL-adjacent table; this renderer
 * is JSON-native (no SQL row concept applies), so the JSON value's own literal is the more faithful,
 * complete choice - indistinguishable-from-empty-string would be a real information loss here.
 * An empty object/array value renders as {@code (empty)} rather than a header with nothing under it.
 *
 * <p><b>Non-JSON, empty, and binary bodies never reach this class</b> - {@code ApiResponseRenderer}'s
 * existing gates ({@code looksBinary}, the empty-body short-circuit, {@code looksJson}) route those to
 * their existing safe paths before any mode dispatch happens. A body that claims JSON via
 * {@code Content-Type} but does not actually parse is the caller's responsibility too (the same
 * {@code JsonSyntaxException}-catch-and-fall-back-to-raw-text idiom {@code prettyPrintJson} already
 * uses) - this class assumes it is handed an already-successfully-parsed {@link JsonElement}.
 */
public final class ApiJsonListRenderer {

	private static final String INDENT_UNIT = "  ";
	private static final String NULL_TEXT = "null";
	private static final String EMPTY_TEXT = "(empty)";

	private ApiJsonListRenderer() {
	}

	/**
	 * @param root any parsed {@link JsonElement} - object, array, or a bare top-level scalar/null (a
	 *             content-type-declared JSON response whose body is just {@code true}/{@code 42}/
	 *             {@code "ok"}/{@code null} has nothing to tabulate as fields, so it is printed as its
	 *             own scalar value alone - closing the "JSON scalar"/"JSON null" cases a naive
	 *             object/array-only dispatch would miss).
	 */
	public static String render(JsonElement root) {
		StringBuilder out = new StringBuilder();
		if (root == null || root.isJsonNull()) {
			out.append(NULL_TEXT).append('\n');
		} else if (root.isJsonObject()) {
			renderObject(root.getAsJsonObject(), "", out);
		} else if (root.isJsonArray()) {
			renderArray(root.getAsJsonArray(), "", out);
		} else {
			out.append(scalarText(root.getAsJsonPrimitive())).append('\n');
		}
		return out.toString();
	}

	private static void renderObject(JsonObject object, String indent, StringBuilder out) {
		if (object.entrySet().isEmpty()) {
			out.append(indent).append(EMPTY_TEXT).append('\n');
			return;
		}
		int labelWidth = 0;
		for (String key : object.keySet()) {
			labelWidth = Math.max(labelWidth, key.length());
		}
		for (var entry : object.entrySet()) {
			String key = entry.getKey();
			JsonElement value = entry.getValue();
			if (value != null && value.isJsonObject()) {
				out.append('\n').append(indent).append(key).append('\n');
				renderObject(value.getAsJsonObject(), indent + INDENT_UNIT, out);
			} else if (value != null && value.isJsonArray()) {
				out.append('\n').append(indent).append(key).append('\n');
				renderArray(value.getAsJsonArray(), indent + INDENT_UNIT, out);
			} else {
				appendField(out, indent, padRight(key, labelWidth), scalarText(value));
			}
		}
	}

	private static void renderArray(JsonArray array, String indent, StringBuilder out) {
		if (array.isEmpty()) {
			out.append(indent).append(EMPTY_TEXT).append('\n');
			return;
		}
		for (int i = 0; i < array.size(); i++) {
			JsonElement element = array.get(i);
			String index = "[" + (i + 1) + "]";
			if (element != null && element.isJsonObject()) {
				out.append(indent).append(index).append('\n');
				renderObject(element.getAsJsonObject(), indent + INDENT_UNIT, out);
				out.append('\n');
			} else if (element != null && element.isJsonArray()) {
				out.append(indent).append(index).append('\n');
				renderArray(element.getAsJsonArray(), indent + INDENT_UNIT, out);
			} else {
				appendField(out, indent, index, scalarText(element));
			}
		}
	}

	/** Appends {@code "indent + label : value"}, indenting any continuation line of a multi-line value to align under the {@code :}. */
	private static void appendField(StringBuilder out, String indent, String label, String value) {
		String prefix = indent + label + " : ";
		String[] lines = value.split("\n", -1);
		out.append(prefix).append(lines[0]).append('\n');
		if (lines.length > 1) {
			String continuationIndent = " ".repeat(prefix.length());
			for (int i = 1; i < lines.length; i++) {
				out.append(continuationIndent).append(lines[i]).append('\n');
			}
		}
	}

	private static String padRight(String value, int width) {
		return width <= value.length() ? value : value + " ".repeat(width - value.length());
	}

	/** {@code null} for a JSON {@code null} (see class javadoc for why this differs from {@link ApiResultTableRenderer}'s blank-cell convention); the raw scalar text otherwise - never truncated. */
	private static String scalarText(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return NULL_TEXT;
		}
		return scalarText(value.getAsJsonPrimitive());
	}

	private static String scalarText(JsonPrimitive value) {
		return value.getAsString();
	}
}
