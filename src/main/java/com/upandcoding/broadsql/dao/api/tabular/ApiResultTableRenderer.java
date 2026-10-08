package com.upandcoding.broadsql.dao.api.tabular;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import com.upandcoding.broadsql.controller.shell.output.TableBorders;

/**
 * Renders an {@link ApiResultTable} as a plain console table for
 * {@link com.upandcoding.broadsql.dao.api.execution.ApiResponseRenderer} - a separator line of dashes, the
 * right-padded column headers, another separator line, then one right-padded line per row, matching
 * {@code com.upandcoding.broadsql.dao.extractors.QueryExtractorToScreen}'s visual style (headers/separators/row
 * values) without inheriting that class's {@code java.sql.ResultSet} dependency. Deliberately narrow: no
 * paging, no {@code <@last:...>} capture, no file output - those all remain {@code QueryExtractorToScreen}'s
 * job for real SQL results; this is console display only, for an {@link ApiResultTable} that already exists
 * in memory.
 *
 * <p>A missing or JSON {@code null} cell renders as a blank, padded cell - never a dash (that convention is
 * {@code QueryExtractorToScreen}'s own way of flagging a genuine SQL NULL) and never the literal text
 * {@code "null"}.
 *
 * <p><b>{@link #render} vs {@link #renderCatalog}</b> (API Quality and UX Consolidation sprint): {@link
 * #render} is for actual API business results (via {@code ApiResponseRenderer}) and never truncates a
 * cell's content, however long - completeness is mandatory there. {@link #renderCatalog} is for
 * navigation/catalog tables only ({@code SHOW ENDPOINTS}, {@code SHOW ALL APIS}, {@code SHOW API
 * ENVIRONMENTS}), where a long folder/name/description may be ellipsized for display - the two are
 * deliberately separate methods so a catalog table's truncation behavior can never leak into a business
 * result's rendering.
 */
public final class ApiResultTableRenderer {

	private static final char SEPARATOR = '|';

	// Same rationale/value as QueryExtractorToScreen.MAX_COLUMN_DISPLAY_SIZE: caps only the alignment
	// padding for short/blank cells in a column - StringUtils.rightPad never truncates real data already
	// longer than the requested width, so a large cell is always shown in full.
	private static final int MAX_COLUMN_DISPLAY_SIZE = 500;

	/** Catalog/navigation tables only (see {@link #renderCatalog}) - a cell longer than this is ellipsized for display; the underlying persisted value is never touched. */
	private static final int MAX_CATALOG_CELL_DISPLAY_LENGTH = 40;

	private ApiResultTableRenderer() {
	}

	/**
	 * Catalog/navigation table rendering - a long cell (folder path, name, alias, URL, description...)
	 * is ellipsized to {@link #MAX_CATALOG_CELL_DISPLAY_LENGTH} characters for display only; the {@link
	 * ApiResultTable} passed in (and whatever it was built from) is never mutated. Never use this for an
	 * actual API business result - see the class javadoc.
	 */
	public static String renderCatalog(ApiResultTable table) {
		return render(truncateForCatalogDisplay(table));
	}

	private static ApiResultTable truncateForCatalogDisplay(ApiResultTable table) {
		List<String> columns = table.getColumnNames();
		List<Map<String, String>> truncatedRows = new ArrayList<>();
		for (Map<String, String> row : table.getRows()) {
			Map<String, String> truncatedRow = new LinkedHashMap<>();
			for (String column : columns) {
				truncatedRow.put(column, truncateForDisplay(row.get(column)));
			}
			truncatedRows.add(truncatedRow);
		}
		return new ApiResultTable(columns, truncatedRows, table.isTabular(), table.getRawJson());
	}

	// Plain ASCII "..." rather than the single-character Unicode ellipsis (U+2026) - the source file
	// carries the correct UTF-8 bytes either way, but a Windows console session's actual output
	// encoding (observed: legacy codepage, not UTF-8) silently mangles the Unicode character to "?" at
	// print time. Three periods render correctly everywhere, matching this CLI's plain-ASCII table
	// borders/separators elsewhere.
	private static final String ELLIPSIS = "...";

	private static String truncateForDisplay(String value) {
		if (value == null || value.length() <= MAX_CATALOG_CELL_DISPLAY_LENGTH) {
			return value;
		}
		return value.substring(0, MAX_CATALOG_CELL_DISPLAY_LENGTH - ELLIPSIS.length()) + ELLIPSIS;
	}

	public static String render(ApiResultTable table) {
		List<String> columns = table.getColumnNames();
		List<Map<String, String>> rows = table.getRows();

		if (columns.isEmpty()) {
			return "(no columns)\n";
		}

		int[] widths = columnWidths(columns, rows);
		String separatorLine = separatorLine(widths);

		StringBuilder out = new StringBuilder();
		out.append(separatorLine).append('\n');
		out.append(headerLine(columns, widths)).append('\n');
		out.append(separatorLine).append('\n');
		for (Map<String, String> row : rows) {
			out.append(dataLine(columns, row, widths)).append('\n');
		}
		out.append(separatorLine).append('\n');
		return out.toString();
	}

	private static int[] columnWidths(List<String> columns, List<Map<String, String>> rows) {
		int[] widths = new int[columns.size()];
		for (int i = 0; i < columns.size(); i++) {
			widths[i] = columns.get(i).length();
		}
		for (Map<String, String> row : rows) {
			for (int i = 0; i < columns.size(); i++) {
				String cell = row.get(columns.get(i));
				if (cell != null && cell.length() > widths[i]) {
					widths[i] = cell.length();
				}
			}
		}
		for (int i = 0; i < widths.length; i++) {
			if (widths[i] > MAX_COLUMN_DISPLAY_SIZE) {
				widths[i] = MAX_COLUMN_DISPLAY_SIZE;
			}
			if (widths[i] < 1) {
				widths[i] = 1;
			}
		}
		return widths;
	}

	// The border rule itself lives in TableBorders (docs/02. guidelines/TABLE_OUTPUT_STANDARD.md)
	private static String separatorLine(int[] widths) {
		return TableBorders.separator(widths, 0, SEPARATOR);
	}

	private static String headerLine(List<String> columns, int[] widths) {
		return TableBorders.row(columns, widths, 0, SEPARATOR);
	}

	private static String dataLine(List<String> columns, Map<String, String> row, int[] widths) {
		List<String> cells = new ArrayList<>(columns.size());
		for (String column : columns) {
			cells.add(row.get(column));
		}
		return TableBorders.row(cells, widths, 0, SEPARATOR);
	}
}
