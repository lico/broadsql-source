package com.upandcoding.broadsql.dao.api.tabular;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A minimal, purely rendering-time view of an API response as a table - docs/BroadSQL XT02 overnight
 * batch plan, sub-sprint 6 ("API results as tables"). Built by {@link ApiJsonTabularizer#tabularize(String)}
 * from an {@link com.upandcoding.broadsql.dao.api.execution.ApiExecutionResult}'s raw body.
 *
 * <p>Deliberately the smallest structure that supports console rendering this sprint - string-typed cells
 * only, consistent with {@code com.upandcoding.broadsql.dao.LastQueryResult}'s existing {@code String[]}-row
 * style; no real column typing (numeric/date) is attempted here, that is sub-sprint 7's concern (H2/XLSX
 * materialization), not console display.
 *
 * <p><b>The original raw JSON is never discarded.</b> {@link #getRawJson()} always returns exactly the
 * response body {@link ApiJsonTabularizer#tabularize(String)} was given, whether or not the shape turned
 * out to be tabular - this is the guarantee that "never destroy the original response" rests on.
 */
public final class ApiResultTable {

	private final List<String> columnNames;
	private final List<Map<String, String>> rows;
	private final boolean tabular;
	private final String rawJson;

	public ApiResultTable(List<String> columnNames, List<Map<String, String>> rows, boolean tabular, String rawJson) {
		this.columnNames = Collections.unmodifiableList(columnNames);
		this.rows = Collections.unmodifiableList(rows);
		this.tabular = tabular;
		this.rawJson = rawJson;
	}

	/** @return the column names, in first-seen order across every row - empty for a non-tabular result or an empty array. */
	public List<String> getColumnNames() {
		return columnNames;
	}

	/** @return one map per row, keyed by column name; a row missing a key for the current column set simply has no entry for it (empty cell on render). */
	public List<Map<String, String>> getRows() {
		return rows;
	}

	/** @return {@code true} if the response body could be represented as a table (including a legitimately empty one) - {@code false} for the two documented fallback shapes (not JSON at all, or an array mixing objects and non-objects). */
	public boolean isTabular() {
		return tabular;
	}

	/** @return the exact, untouched raw JSON body this table was derived from - always present, tabular or not; never discarded, never re-serialized. */
	public String getRawJson() {
		return rawJson;
	}
}
