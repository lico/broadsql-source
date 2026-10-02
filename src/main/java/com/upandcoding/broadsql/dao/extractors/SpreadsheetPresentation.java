package com.upandcoding.broadsql.dao.extractors;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;

/**
 * AutoFilter + frozen header row for an exported data sheet - shared by {@link QueryExtractorToExcel2007}
 * and {@link com.upandcoding.broadsql.dao.pull.spreadsheet.PullToXlsxExporter}. Deliberately not applied to a
 * metadata/recap sheet (the "Query"/"QUERIES" sheet) - callers only invoke this on the actual data sheet.
 */
public final class SpreadsheetPresentation {

	private SpreadsheetPresentation() {
	}

	/**
	 * Sets an AutoFilter over the full header+data range (still applied, header-row-only, when
	 * {@code lastDataRowIndex} is 0, i.e. an empty result set - so the filter dropdowns are present even
	 * with no rows to filter) and freezes row 0 (the header) so it stays visible while scrolling.
	 *
	 * @param sheet            the data sheet to format
	 * @param columnCount      number of columns (must match the header row actually written)
	 * @param lastDataRowIndex the last row index containing data (0 if only the header row exists)
	 */
	public static void applyAutoFilterAndFreezeHeader(Sheet sheet, int columnCount, int lastDataRowIndex) {
		if (columnCount <= 0) {
			return;
		}
		sheet.setAutoFilter(new CellRangeAddress(0, lastDataRowIndex, 0, columnCount - 1));
		sheet.createFreezePane(0, 1);
	}
}
