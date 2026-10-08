package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.util.ArrayList;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;

/**
 * {@code <@last:<column>>}: reuses one column of the most recently successfully displayed SQL query
 * result as a list source - e.g. {@code <@last:CUSTOMER_ID>} after
 * {@code SELECT CUSTOMER_ID FROM COMPLAINT WHERE STATUS = 'OPEN';} (docs/TODO.md, "Previous result's
 * column as SQL input"). A sibling of {@link CsvColumnListSource}/{@link ExcelColumnListSource}, backed
 * by {@link LastQueryResultHolder} instead of an external file/clipboard.
 *
 * <p><b>Column selection</b> is by the result's visible column label (so a query alias, e.g.
 * {@code SELECT customer_id AS cid}, resolves by {@code cid}), matched exactly first, then
 * case-insensitively - same convention as {@link CsvColumnListSource}. A label matching more than once
 * (e.g. {@code SELECT a.ID, b.ID}) is rejected as ambiguous rather than silently picking the first.
 *
 * <p><b>NULL handling</b>: SQL NULL values in the selected column are skipped, never rendered as the
 * literal text {@code NULL} - same convention as blank lines/cells in {@link FileListSource}/
 * {@link CsvColumnListSource}. Duplicates and value order are preserved, exactly like every other
 * {@link ListSource}.
 *
 * <p><b>Size limit</b>: refuses to resolve a list at all once the underlying result exceeded
 * {@link LastQueryResult#MAX_ROWS} - never silently truncates. See
 * {@code com.upandcoding.broadsql.dao.extractors.QueryExtractorToScreen} for where the snapshot is captured
 * and bounded.
 */
public class LastResultListSource implements ListSource {

	private static final String USAGE = "<@last:...> needs the form <@last:<column>>, e.g. <@last:CUSTOMER_ID>";

	private final String columnName;

	LastResultListSource(String columnName) {
		this.columnName = columnName;
	}

	static LastResultListSource parse(String spec) throws BroadSQLException {
		String column = spec.trim();
		if (column.isEmpty()) {
			throw new BroadSQLException(USAGE);
		}
		return new LastResultListSource(column);
	}

	@Override
	public List<String> values() throws BroadSQLException {
		LastQueryResult last = LastQueryResultHolder.get();
		if (last == null) {
			throw new BroadSQLException("No previous query result is available for <@last:...>.");
		}
		if (last.exceedsLimit()) {
			throw new BroadSQLException("The last result contains " + last.totalRowCount() + " values. <@last:...> "
					+ "is intended for relatively small lists (limit: " + LastQueryResult.MAX_ROWS + "). "
					+ "Use PULL ... AS H2 for large datasets.");
		}

		int columnIndex = resolveColumnIndex(last.columnLabels());

		List<String> values = new ArrayList<>();
		for (String[] row : last.rows()) {
			String value = row[columnIndex];
			if (value != null) {
				String trimmed = value.trim();
				if (!trimmed.isEmpty()) {
					values.add(trimmed);
				}
			}
		}
		return values;
	}

	private int resolveColumnIndex(List<String> columnLabels) throws BroadSQLException {
		List<Integer> exactMatches = new ArrayList<>();
		List<Integer> caseInsensitiveMatches = new ArrayList<>();
		for (int i = 0; i < columnLabels.size(); i++) {
			String label = columnLabels.get(i);
			if (label.equals(columnName)) {
				exactMatches.add(i);
			}
			if (label.equalsIgnoreCase(columnName)) {
				caseInsensitiveMatches.add(i);
			}
		}
		List<Integer> candidates = !exactMatches.isEmpty() ? exactMatches : caseInsensitiveMatches;
		if (candidates.isEmpty()) {
			throw new BroadSQLException("Column '" + columnName + "' was not found in the last query result. "
					+ "Available columns: " + String.join(", ", columnLabels));
		}
		if (candidates.size() > 1) {
			throw new BroadSQLException("Column '" + columnName + "' is ambiguous in the last query result.");
		}
		return candidates.get(0);
	}
}
