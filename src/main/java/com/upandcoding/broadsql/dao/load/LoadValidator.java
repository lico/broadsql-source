package com.upandcoding.broadsql.dao.load;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVRecord;

/**
 * Builds a {@link LoadPlan} from a resolved {@link LoadTarget} and the rows read by
 * {@link LoadSourceReader} - source-to-column mapping (SPRINT 0912B section 6), per-value
 * conversion (section 8), and the NULL/blank/omitted rule (section 9), all in a single pass so
 * preview and execution never re-read or re-parse the source file.
 *
 * <p><b>All-or-nothing preflight</b>: this engine's chosen v1 rule (the spec leaves the choice open,
 * "Preflight does not need to duplicate every database constraint") is that <em>any</em> unmapped
 * header or <em>any</em> row conversion failure refuses the <em>entire</em> load - never a silent
 * partial load of only the rows that happened to convert cleanly. A source column the user expected
 * loaded, silently dropped because its header didn't match, would be exactly the kind of surprise
 * this sprint exists to eliminate for identifiers; the same reasoning applies to rows. See
 * {@code docs/SQL_LOAD.md}.
 */
public final class LoadValidator {

	private LoadValidator() {
	}

	public static LoadPlan validate(LoadTarget target, String sourceFile, LoadSourceReader source) {
		List<String> headers = source.getHeaders();
		List<CSVRecord> records = source.getRecords();

		List<String> unmappedHeaders = new ArrayList<>();
		List<LoadTargetColumn> mappedColumns = new ArrayList<>(headers.size());
		List<String> insertColumns = new ArrayList<>(headers.size());

		for (String header : headers) {
			LoadTargetColumn column = target.findColumn(header);
			if (column == null) {
				unmappedHeaders.add(header);
				mappedColumns.add(null);
			} else {
				mappedColumns.add(column);
				insertColumns.add(column.getName());
			}
		}

		List<LoadRowIssue> issues = new ArrayList<>();
		List<Object[]> boundRows = new ArrayList<>();

		if (unmappedHeaders.isEmpty()) {
			int rowNumber = 0;
			for (CSVRecord record : records) {
				rowNumber++;
				Object[] boundRow = new Object[insertColumns.size()];
				boolean rowOk = true;
				int boundIndex = 0;
				for (int col = 0; col < headers.size(); col++) {
					LoadTargetColumn column = mappedColumns.get(col);
					String rawValue = col < record.size() ? record.get(col) : null;
					try {
						boundRow[boundIndex++] = LoadValueConverter.convert(rawValue, column);
					} catch (LoadValueConversionException e) {
						issues.add(new LoadRowIssue(rowNumber, headers.get(col), column.getName(), column.getTypeName(),
								rawValue, e.getMessage()));
						rowOk = false;
					}
				}
				if (rowOk) {
					boundRows.add(boundRow);
				}
			}
		}

		int sourceRowCount = records.size();
		int validRowCount = unmappedHeaders.isEmpty() ? boundRows.size() : 0;

		return new LoadPlan(target, sourceFile, insertColumns, unmappedHeaders, sourceRowCount, validRowCount, issues, boundRows);
	}
}
