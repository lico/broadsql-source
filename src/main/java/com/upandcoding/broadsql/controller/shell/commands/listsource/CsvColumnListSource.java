package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * {@code <@csv:<path>:<column>>}: reads one named column from a CSV file - e.g.
 * {@code <@csv:c:\temp\customers.csv:CUSTOMER_ID>} (docs/TODO.md, "Spreadsheet/clipboard data as query
 * input", "Excel/CSV column as list input"). Uses Apache Commons CSV ({@code commons-csv}, Apache-2.0)
 * for real RFC 4180 parsing (quoted values, embedded separators/quotes) rather than a hand-rolled
 * splitter - no CSV-reading library existed anywhere in this project before this class (verified: the
 * only prior CSV handling was {@code DatabaseConnection}'s {@code directLoad*} family, a plain delimiter
 * split with no quote support, and the write-only {@code PullToTextExporter}/{@code QueryExtractorToFile}).
 *
 * <p><b>Column selection</b> is by header name, matched exactly first, then case-insensitively - never
 * by physical column letter/position, so reordering columns in the source file doesn't silently change
 * which values are used. A header appearing more than once is rejected as ambiguous rather than silently
 * picking the first.
 *
 * <p><b>Blank cells</b> in the target column are skipped, same convention as {@link FileListSource}/
 * {@link ClipboardListSource} skipping blank lines.
 *
 * <p><b>Encoding</b>: read as UTF-8; a leading byte-order-mark on the first header (common when the file
 * was itself produced by Excel) is stripped before header matching, so it never causes a spurious
 * "column not found".
 */
public class CsvColumnListSource implements ListSource {

	private static final String USAGE = "<@csv:...> needs the form <@csv:<path>:<column>>, e.g. <@csv:c:\\temp\\customers.csv:CUSTOMER_ID>";

	private final String filePath;
	private final String columnName;

	CsvColumnListSource(String filePath, String columnName) {
		this.filePath = filePath;
		this.columnName = columnName;
	}

	/**
	 * Splits {@code spec} (the token content after the {@code csv:} prefix) at its <em>last</em> colon -
	 * the column name never contains one, while a Windows path may (its drive letter) - so
	 * {@code c:\temp\customers.csv:CUSTOMER_ID} splits into path {@code c:\temp\customers.csv} and column
	 * {@code CUSTOMER_ID} correctly regardless of how many colons the path itself contains.
	 */
	static CsvColumnListSource parse(String spec) throws BroadSQLException {
		int lastColon = spec.lastIndexOf(':');
		if (lastColon <= 0 || lastColon == spec.length() - 1) {
			throw new BroadSQLException(USAGE);
		}
		String path = spec.substring(0, lastColon).trim();
		String column = spec.substring(lastColon + 1).trim();
		if (path.isEmpty() || column.isEmpty()) {
			throw new BroadSQLException(USAGE);
		}
		return new CsvColumnListSource(path, column);
	}

	@Override
	public List<String> values() throws BroadSQLException {
		File file = new File(filePath);
		if (!file.isFile()) {
			throw new BroadSQLException("File " + filePath + " not found");
		}

		CSVFormat format = CSVFormat.DEFAULT.builder()
				.setHeader()
				.setSkipHeaderRecord(true)
				.setIgnoreSurroundingSpaces(true)
				.setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
				.build();

		List<String> values = new ArrayList<>();
		try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
				CSVParser parser = format.parse(reader)) {

			String matchedHeader = resolveHeader(parser.getHeaderMap());
			for (CSVRecord record : parser) {
				String value = record.get(matchedHeader);
				if (value != null) {
					String trimmed = value.trim();
					if (!trimmed.isEmpty()) {
						values.add(trimmed);
					}
				}
			}
		} catch (IOException e) {
			throw new BroadSQLException("Error reading CSV file " + filePath + ": " + e.getLocalizedMessage());
		} catch (IllegalArgumentException iae) {
			// commons-csv throws this for a malformed header (e.g. a duplicate column name, caught by
			// DuplicateHeaderMode.DISALLOW above) or malformed record structure.
			throw new BroadSQLException("CSV file " + filePath + " could not be parsed: " + iae.getLocalizedMessage());
		}
		return values;
	}

	private String resolveHeader(Map<String, Integer> headerMap) throws BroadSQLException {
		String wanted = stripLeadingBom(columnName);
		String caseInsensitiveMatch = null;
		for (String header : headerMap.keySet()) {
			String stripped = stripLeadingBom(header);
			if (stripped.equals(wanted)) {
				return header;
			}
			if (caseInsensitiveMatch == null && stripped.equalsIgnoreCase(wanted)) {
				caseInsensitiveMatch = header;
			}
		}
		if (caseInsensitiveMatch != null) {
			return caseInsensitiveMatch;
		}
		throw new BroadSQLException("Column '" + columnName + "' not found in CSV file " + filePath
				+ " - available columns: " + String.join(", ", headerMap.keySet()));
	}

	private String stripLeadingBom(String s) {
		return (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') ? s.substring(1) : s;
	}
}
