package com.upandcoding.broadsql.dao.load;

import java.util.List;

/**
 * A {@code LOAD} target table resolved against database metadata (see {@link LoadTargetResolver}):
 * schema/table/column names here are always the database's own, echoed back from
 * {@code DatabaseMetaData} - never the raw text the user typed on the command line.
 */
public final class LoadTarget {

	private final String schema;
	private final String tableName;
	private final List<LoadTargetColumn> columns;

	public LoadTarget(String schema, String tableName, List<LoadTargetColumn> columns) {
		this.schema = schema;
		this.tableName = tableName;
		this.columns = columns;
	}

	public String getSchema() {
		return schema;
	}

	public String getTableName() {
		return tableName;
	}

	public String getQualifiedName() {
		return (schema != null && !schema.isBlank()) ? schema + "." + tableName : tableName;
	}

	public List<LoadTargetColumn> getColumns() {
		return columns;
	}

	/**
	 * Resolves a source header to exactly one column: an exact (case-sensitive) name match wins
	 * outright; otherwise a case-insensitive match is used only if it is unique. Returns
	 * {@code null} for no match or an ambiguous one - the caller is responsible for telling those
	 * two apart if it needs to (see {@link #countCaseInsensitiveMatches}).
	 */
	public LoadTargetColumn findColumn(String header) {
		for (LoadTargetColumn column : columns) {
			if (column.getName().equals(header)) {
				return column;
			}
		}
		LoadTargetColumn onlyMatch = null;
		int matches = 0;
		for (LoadTargetColumn column : columns) {
			if (column.getName().equalsIgnoreCase(header)) {
				matches++;
				onlyMatch = column;
			}
		}
		return matches == 1 ? onlyMatch : null;
	}

	public int countCaseInsensitiveMatches(String header) {
		int matches = 0;
		for (LoadTargetColumn column : columns) {
			if (column.getName().equalsIgnoreCase(header)) {
				matches++;
			}
		}
		return matches;
	}
}
