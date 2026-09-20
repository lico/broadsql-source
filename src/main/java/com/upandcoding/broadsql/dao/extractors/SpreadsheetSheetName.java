package com.upandcoding.broadsql.dao.extractors;

/**
 * Sheet-name sanitization shared by every spreadsheet-based extractor ({@link QueryExtractorToExcel2007},
 * {@link QueryExtractorToODS}). The rules applied are Excel's (the strictest of the two formats
 * BroadSQL writes) so the same sanitized name is safe in either format.
 */
final class SpreadsheetSheetName {

	private static final String ILLEGAL_CHARS = "[\\\\/:\\?\\*\\[\\]]";
	private static final int MAX_LENGTH = 31;
	private static final String DEFAULT_NAME = "Data";

	private SpreadsheetSheetName() {
	}

	/**
	 * Makes a table name (guessed from the query by {@link IQueryExtractor#getTableNameFromQuery(String)})
	 * safe to use as a sheet name: strips characters Excel forbids in a sheet name
	 * ({@code \ / : ? * [ ]}), trims leading/trailing apostrophes (also forbidden), truncates to
	 * Excel's 31-character limit, falls back to {@value #DEFAULT_NAME} if that leaves nothing usable,
	 * and renames away from "Query" so it cannot collide with a query-recap sheet created alongside it.
	 */
	static String sanitize(String rawName) {
		String name = rawName == null ? "" : rawName;
		name = name.replaceAll(ILLEGAL_CHARS, "_");
		name = name.replaceAll("^'+|'+$", "");
		name = name.trim();
		if (name.length() > MAX_LENGTH) {
			name = name.substring(0, MAX_LENGTH);
		}
		if (name.isEmpty() || name.equalsIgnoreCase("Query")) {
			name = DEFAULT_NAME;
		}
		return name;
	}
}
