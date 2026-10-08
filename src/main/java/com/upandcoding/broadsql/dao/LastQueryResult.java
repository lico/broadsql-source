package com.upandcoding.broadsql.dao;

import java.util.Collections;
import java.util.List;

/**
 * An immutable snapshot of the most recent SQL query result actually shown to the user on screen -
 * captured by {@code QueryExtractorToScreen} while it iterates the {@code ResultSet} for display, so
 * no extra JDBC fetch is ever performed just for this. Backs {@code <@last:column>}
 * (see {@code com.upandcoding.broadsql.controller.shell.commands.listsource.LastResultListSource}).
 *
 * <p>Deliberately the smallest structure that supports that one feature - not a general result-history
 * mechanism (see docs/TODO.md, "Result reuse inside an investigation session"). Only ever replaces the
 * previous snapshot after a query has fully, successfully finished displaying; a failed or interrupted
 * query never reaches the code that builds one, so the previous successful snapshot is preserved
 * automatically (see {@code docs/TECHNICAL_CHANGE.md}).
 *
 * <p><b>Memory bound</b>: at most {@link #MAX_ROWS} rows are ever retained, regardless of how many rows
 * the underlying query actually returned or how many are displayed on screen (which is governed
 * separately by {@code ConsoleSettings#getMaxRowsOnScreen()}) - ensures a multi-million-row query never
 * materializes more than {@link #MAX_ROWS} rows in memory just because {@code <@last:...>} exists.
 * {@link #totalRowCount} keeps counting every row actually iterated even after capture stops, so
 * {@link #exceedsLimit()} can report the true count in its error message rather than just "too many".
 *
 * <p><b>SPRINT 2309T (#161)</b>: the same snapshot is what {@code DUMP /} exports - "the last tabular
 * result", never re-run. For that it additionally keeps each column's JDBC type/precision/scale and a
 * typed copy of each captured value ({@link #typedRows()}; {@code Long}, {@code BigDecimal},
 * {@code Double}, {@code Float}, {@code Boolean}, {@code LocalDate}/{@code LocalTime}/{@code LocalDateTime},
 * {@code OffsetDateTime}/{@code OffsetTime} for time-zone-aware values (offset kept), an
 * {@link UnavailableValue}, or
 * {@code String}), so a dumped number stays a number in XLSX/JSON, and whether the display stopped early
 * ({@link #isTruncated()}). The typed copy keeps every displayed row: unlike the text copy above, its
 * only bound is the configured {@code MaxRowsOnScreen}, never {@link #MAX_ROWS}. {@code DUMP /} refuses a
 * snapshot that is not the complete result ({@link #isComplete()}) rather than exporting a silently
 * partial file.
 */
public final class LastQueryResult {

	/**
	 * Centralized, easy-to-change cap on how many rows {@code <@last:column>} may resolve from - chosen
	 * conservatively: an order of magnitude above the 1,000-row {@code maxRowsOnScreen} default already
	 * used by {@code TestDatabaseConnections#defaultConsoleSettings()} and other list sources' own
	 * "large" test fixtures (e.g. {@code TestClipboardListSource}'s 5,000-value case), while staying far
	 * below the "hundreds of thousands or millions of rows" this feature is explicitly not meant for -
	 * see docs/TODO.md, "Previous result's column as SQL input". Large datasets should use
	 * {@code PULL ... AS H2} instead.
	 */
	public static final int MAX_ROWS = 5000;

	private final List<String> columnLabels;
	private final List<String[]> rows;
	private final int totalRowCount;
	private final String connectionId;
	private final ColumnInfo[] columns;
	private final List<Object[]> typedRows;
	private final boolean truncated;

	/**
	 * One column's JDBC shape, as reported by the displayed query's own {@code ResultSetMetaData} -
	 * everything {@code DUMP /} needs to rebuild a typed table from the snapshot.
	 */
	public record ColumnInfo(String label, int jdbcType, String typeName, int precision, int scale) {
	}

	/**
	 * A cell of {@link #typedRows()} that was displayed ({@code text}, from {@code getString()}) but whose typed
	 * value could not be read (the driver's typed getter rejected it: a {@code NaN} or {@code Infinity} numeric,
	 * a zero date...). The typed copy is optional: it never makes the display fail. {@code DUMP /} refuses to
	 * export such a column rather than writing a value of another type.
	 */
	public record UnavailableValue(String text, String reason) {
	}

	/** A snapshot without typed values (e.g. built by a test for {@code <@last:...>}): never exportable by {@code DUMP /}. */
	public LastQueryResult(List<String> columnLabels, List<String[]> rows, int totalRowCount, String connectionId) {
		this(columnLabels, rows, totalRowCount, connectionId, null, null, false);
	}

	/**
	 * @param columns   one entry per column, aligned with {@code columnLabels}; {@code null} if unknown
	 * @param typedRows the captured rows as typed values, aligned with {@code rows}; {@code null} if unknown
	 * @param truncated {@code true} when the display stopped before the end of the result
	 *                  ({@code MaxRowsOnScreen}), so {@code totalRowCount} is not the real row count
	 */
	public LastQueryResult(List<String> columnLabels, List<String[]> rows, int totalRowCount, String connectionId, ColumnInfo[] columns,
			List<Object[]> typedRows, boolean truncated) {
		this.columnLabels = Collections.unmodifiableList(columnLabels);
		this.rows = Collections.unmodifiableList(rows);
		this.totalRowCount = totalRowCount;
		this.connectionId = connectionId;
		this.columns = columns;
		this.typedRows = typedRows == null ? null : Collections.unmodifiableList(typedRows);
		this.truncated = truncated;
	}

	/**
	 * @return the column labels exactly as returned by {@code ResultSetMetaData.getColumnLabel} - the
	 *         visible name, so a query alias ({@code SELECT id AS cid}) resolves by {@code cid}, not the
	 *         underlying column name
	 */
	public List<String> columnLabels() {
		return columnLabels;
	}

	/**
	 * @return the captured rows, each aligned with {@link #columnLabels()}; a {@code null} entry means
	 *         SQL NULL. Only meaningful when {@link #exceedsLimit()} is {@code false} - callers must
	 *         check that first.
	 */
	public List<String[]> rows() {
		return rows;
	}

	/**
	 * @return the true number of rows actually iterated for display - may exceed {@link #rows()}'s size
	 *         once {@link #MAX_ROWS} was reached, in which case {@link #exceedsLimit()} is {@code true}
	 */
	public int totalRowCount() {
		return totalRowCount;
	}

	/**
	 * @return {@code true} if the underlying result had more than {@link #MAX_ROWS} rows - {@code
	 *         <@last:...>} must refuse to resolve a list from this snapshot rather than silently using
	 *         only the first {@link #MAX_ROWS} of them
	 */
	public boolean exceedsLimit() {
		return totalRowCount > MAX_ROWS;
	}

	/**
	 * @return the connection the result came from (informational only) - may be {@code null}
	 */
	public String connectionId() {
		return connectionId;
	}

	/** @return each column's JDBC shape, or {@code null} for a snapshot built without it */
	public ColumnInfo[] columns() {
		return columns == null ? null : columns.clone();
	}

	/** @return the captured rows as typed values (see the class Javadoc), or {@code null} for a snapshot built without them */
	public List<Object[]> typedRows() {
		return typedRows;
	}

	/** @return {@code true} when the display stopped early ({@code MaxRowsOnScreen}), so later rows were never fetched */
	public boolean isTruncated() {
		return truncated;
	}

	/**
	 * @return {@code true} when this snapshot holds every row of the result, with types - the only case
	 *         {@code DUMP /} can export without re-running the query. Depends only on whether the display
	 *         stopped at {@code MaxRowsOnScreen}, not on {@link #MAX_ROWS}.
	 */
	public boolean isComplete() {
		return columns != null && typedRows != null && !truncated && typedRows.size() == totalRowCount;
	}
}
