package com.upandcoding.broadsql.dao.pull.spreadsheet;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Shape of the per-file info tab written alongside every data tab by {@code PULL ... AS XLSX/ODS} - see
 * docs/PULL_TO_SPREADSHEET.md, "Info tab". One row per data tab (tab name, query text, date/time, source
 * database connection, row count), upserted by tab name: a tab re-pulled under the same name gets its
 * existing row replaced, not a new one appended. The info tab is created automatically the first time a
 * file gets a data tab written to it - whether that file is brand new, or already has other, unrelated
 * tabs but no info tab yet (docs/PULL_TO_SPREADSHEET.md, "Foreign files") - so it is never something the
 * caller must set up beforehand.
 *
 * <p>Present in both formats, unlike {@code EXPORT}/{@code DUMP}'s Excel-only "Query" sheet
 * (see {@code QueryExtractorToExcel2007}) - PULL builds it independently rather than reusing that code
 * (see docs/PULL_TO_SPREADSHEET.md, "Relationship to PULL ... AS H2" for why the two PULL destination
 * kinds, and this class relative to EXPORT/DUMP's extractors, share nothing but a naming convention).
 * Named {@value #SHEET_NAME}, not the singular "Query" the legacy Excel-only sheet used - renamed
 * 28/08/2026 at the user's request, since this sheet holds one row per pulled tab, plural by nature.
 *
 * <p>{@link #SHEET_NAME} is reserved as a data tab name (rejected at parse time by
 * {@code PullCommandParser.validateSheetName}, and independently rejected again by
 * {@code PullToXlsxExporter}/{@code PullToOdsExporter} themselves before either exporter touches a file -
 * see those classes - so the info tab can never be erased by a `PULL` naming its own destination tab
 * {@value #SHEET_NAME}, regardless of which entry point is used).
 */
final class PullSpreadsheetInfoTab {

	static final String SHEET_NAME = "QUERIES";
	static final String[] HEADERS = {"Tab", "Query", "Date", "Connection", "Rows"};

	private PullSpreadsheetInfoTab() {
	}

	/**
	 * Checked before touching an info tab found in a file PULL did not necessarily create itself
	 * (docs/PULL_TO_SPREADSHEET.md, "Foreign files"): a sheet literally named {@value #SHEET_NAME} that
	 * was not built by a prior PULL - its header row won't match {@link #HEADERS} exactly - is left alone
	 * rather than having rows silently reinterpreted or overwritten.
	 */
	static void validateHeaderRow(String[] actualHeaderRow) throws BroadSQLException {
		boolean matches = actualHeaderRow != null && actualHeaderRow.length == HEADERS.length;
		if (matches) {
			for (int i = 0; i < HEADERS.length; i++) {
				if (!HEADERS[i].equals(actualHeaderRow[i])) {
					matches = false;
					break;
				}
			}
		}
		if (!matches) {
			throw new BroadSQLException("PULL aborted: this file already has a sheet named '" + SHEET_NAME + "' that was not "
					+ "created by PULL (its header row doesn't match the expected " + java.util.Arrays.toString(HEADERS)
					+ ") - rename or remove that sheet first, PULL will not overwrite a sheet it doesn't recognize.");
		}
	}
}
