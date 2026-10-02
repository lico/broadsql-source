package com.upandcoding.broadsql.dao.extractors;

import org.apache.poi.ss.usermodel.Sheet;

/**
 * Estimates a "reasonable" column width for an exported data sheet ({@link QueryExtractorToExcel2007},
 * {@link com.upandcoding.broadsql.dao.pull.spreadsheet.PullToXlsxExporter}), without the cost of POI's
 * {@code autoSizeColumn} (which renders every cell with AWT font metrics - too slow to run on every
 * export, especially for a large result set) and without letting one long {@code VARCHAR}/{@code CLOB}
 * value blow a column up to an unusable width.
 *
 * <p>Only free-text values need sampling: a {@code DATE}/{@code TIME}/{@code TIMESTAMP}/numeric/
 * {@code BOOLEAN} column's width is bounded by its own format (a formatted timestamp is always the same
 * length), so those are set once via {@link #fixedMinimum(int, int)} from the header length and the
 * type's known display width - only the generic text branch calls {@link #sampleText(int, String, int)},
 * and even then only for the first {@value #MAX_SAMPLED_ROWS} data rows, since a representative sample is
 * enough to size a column sensibly and reading every value of a very large result set just for formatting
 * would be wasted work.
 */
public final class SpreadsheetColumnWidths {

	private static final int MAX_SAMPLED_ROWS = 200;
	private static final int MIN_CHARS = 6;
	private static final int MAX_TEXT_CHARS = 60;
	private static final int PADDING_CHARS = 2;

	private final int[] maxChars;

	public SpreadsheetColumnWidths(int columnCount) {
		this.maxChars = new int[columnCount];
	}

	/** Records the header label's length as a floor for {@code col} - every column gets this, regardless of type. */
	public void header(int col, String label) {
		observe(col, label == null ? 0 : label.length());
	}

	/**
	 * Sets a type-appropriate minimum width for {@code col} once (e.g. 10 chars for a {@code DATE}, 19 for
	 * a {@code TIMESTAMP}) - covers every column type except free text, whose width instead comes from
	 * {@link #sampleText(int, String, int)}.
	 */
	public void fixedMinimum(int col, int chars) {
		observe(col, chars);
	}

	/** Samples a free-text value's length for {@code col}, ignored once {@code rowIndex} exceeds the sample cap. */
	public void sampleText(int col, String value, int rowIndex) {
		if (rowIndex <= MAX_SAMPLED_ROWS && value != null) {
			observe(col, Math.min(value.length(), MAX_TEXT_CHARS));
		}
	}

	private void observe(int col, int chars) {
		if (chars > maxChars[col]) {
			maxChars[col] = chars;
		}
	}

	/** Applies the estimated width (clamped to {@code [MIN_CHARS, MAX_TEXT_CHARS] + padding}) to every column of {@code sheet}. */
	public void applyTo(Sheet sheet) {
		for (int col = 0; col < maxChars.length; col++) {
			int chars = Math.max(MIN_CHARS, Math.min(maxChars[col], MAX_TEXT_CHARS)) + PADDING_CHARS;
			// POI column width is in units of 1/256th of a character.
			sheet.setColumnWidth(col, chars * 256);
		}
	}
}
