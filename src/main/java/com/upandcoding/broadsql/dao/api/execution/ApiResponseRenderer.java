package com.upandcoding.broadsql.dao.api.execution;

import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.upandcoding.broadsql.dao.api.tabular.ApiJsonListRenderer;
import com.upandcoding.broadsql.dao.api.tabular.ApiJsonTabularizer;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTable;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTableRenderer;

/**
 * Renders an {@link ApiExecutionResult} as readable text - docs/SPRINT XT02 - Universal API Client.md,
 * section 21.9-21.12. Deliberately separate from {@link com.upandcoding.broadsql.dao.api.http.ApiHttpTransport}
 * (which knows nothing about display) and from {@link ApiEndpointExecutor} (which knows nothing about
 * rendering) - this is the one place that decides how a response *looks*.
 *
 * <p><b>Display mode</b> (API Quality and UX Consolidation sprint, sections 32-41 - see
 * {@link ApiResultDisplayMode}): {@code LIST} (the new default) renders the body as a complete,
 * structure-preserving vertical view via {@link ApiJsonListRenderer}; {@code TABLE} is the previous
 * sub-sprint-6 "table when the shape allows it, else pretty-JSON" behavior, now explicit rather than
 * default; {@code RAW} is unchanged - always pretty-printed JSON (or raw text for a non-JSON body),
 * regardless of shape. The original raw body is never discarded in any mode, see
 * {@link ApiResultTable#getRawJson()}/{@link ApiExecutionResult#getBody()}. A response that claims
 * JSON via its {@code Content-Type} but does not actually parse as JSON falls back to its raw text in
 * every mode - a distinct "JSON formatting issue," not an execution failure, and not something any
 * mode-specific renderer ({@link ApiJsonListRenderer} included) needs to handle itself. A response
 * whose {@code Content-Type} does not look textual is shown as safe metadata (byte count, content
 * type) rather than an attempt to dump arbitrary bytes as characters.
 */
public final class ApiResponseRenderer {

	private ApiResponseRenderer() {
	}

	/** Equivalent to {@link #render(ApiExecutionResult, ApiResultDisplayMode)} with {@link ApiResultDisplayMode#LIST}. */
	public static String render(ApiExecutionResult result) {
		return render(result, ApiResultDisplayMode.LIST);
	}

	public static String render(ApiExecutionResult result, ApiResultDisplayMode mode) {
		StringBuilder out = new StringBuilder();
		out.append(result.getMethod()).append(' ').append(result.getSafeUrl()).append("\n\n");
		out.append("HTTP ").append(result.getStatusCode()).append('\n');
		out.append("Duration: ").append(result.getDurationMillis()).append(" ms\n");

		if (!result.getHeaders().isEmpty()) {
			out.append('\n');
			for (Map.Entry<String, String> header : result.getHeaders().entrySet()) {
				out.append(header.getKey()).append(": ").append(header.getValue()).append('\n');
			}
		}

		byte[] bodyBytes = result.getBodyBytes();
		if (bodyBytes == null || bodyBytes.length == 0) {
			return out.toString();
		}

		String contentType = result.getContentType();
		out.append('\n');
		if (looksBinary(contentType)) {
			out.append("[binary response, ").append(bodyBytes.length).append(" byte(s)");
			if (contentType != null) {
				out.append(", content-type ").append(contentType);
			}
			out.append("]\n");
			return out.toString();
		}

		String body = result.getBody();
		if (looksJson(contentType, body)) {
			switch (mode) {
				case TABLE -> {
					ApiResultTable table = ApiJsonTabularizer.tabularize(body);
					if (table.isTabular()) {
						out.append(ApiResultTableRenderer.render(table));
						out.append(rowCountFooter(table));
						return out.toString();
					}
					out.append(prettyPrintJson(body));
				}
				case RAW -> out.append(prettyPrintJson(body));
				case LIST -> out.append(renderList(body));
			}
		} else {
			out.append(body);
		}
		return out.toString();
	}

	/** Falls back to the raw body, unchanged, on the same "claims JSON but doesn't actually parse" case {@link #prettyPrintJson} already handles - a display-layer distinction, never a thrown error. */
	private static String renderList(String body) {
		try {
			JsonElement element = JsonParser.parseString(body);
			return ApiJsonListRenderer.render(element);
		} catch (JsonSyntaxException e) {
			return body;
		}
	}

	private static String rowCountFooter(ApiResultTable table) {
		int rowCount = table.getRows().size();
		return "\n" + rowCount + (rowCount == 1 ? " row.\n" : " rows.\n");
	}

	private static boolean looksBinary(String contentType) {
		if (contentType == null) {
			return false;
		}
		String lower = contentType.toLowerCase();
		return !(lower.startsWith("text/") || lower.contains("json") || lower.contains("xml") || lower.contains("javascript") || lower.contains("html") || lower.contains("charset"));
	}

	private static boolean looksJson(String contentType, String body) {
		if (contentType != null && contentType.toLowerCase().contains("json")) {
			return true;
		}
		String trimmed = body.trim();
		return trimmed.startsWith("{") || trimmed.startsWith("[");
	}

	/** Falls back to the raw body, unchanged, if it claims to be JSON but does not actually parse - a display-layer distinction, never a thrown error. */
	private static String prettyPrintJson(String body) {
		try {
			JsonElement element = JsonParser.parseString(body);
			return new GsonBuilder().setPrettyPrinting().create().toJson(element);
		} catch (JsonSyntaxException e) {
			return body;
		}
	}
}
