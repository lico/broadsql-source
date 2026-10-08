package com.upandcoding.broadsql.dao.model.metadata;

/**
 * SPRINT 2409K: one column of an index, normalized from {@code DatabaseMetaData.getIndexInfo}. A composite
 * index is several instances sharing {@link #getIndexName()} and the table, ordered by
 * {@link #getOrdinalPosition()}. Table-statistics rows ({@code TYPE = tableIndexStatistic}) are never
 * turned into instances: they are not indexes.
 */
public class IndexMetadata {

	private final String catalog;
	private final String schema;
	private final String table;
	private final String indexName;
	private final String column;
	private final int ordinalPosition;
	private final boolean unique;

	public IndexMetadata(String catalog, String schema, String table, String indexName, String column, int ordinalPosition, boolean unique) {
		this.catalog = catalog;
		this.schema = schema;
		this.table = table;
		this.indexName = indexName;
		this.column = column;
		this.ordinalPosition = ordinalPosition;
		this.unique = unique;
	}

	public String getCatalog() {
		return catalog;
	}

	public String getSchema() {
		return schema;
	}

	public String getTable() {
		return table;
	}

	public String getIndexName() {
		return indexName;
	}

	/** The indexed column, or the driver's expression text for an expression index; {@code ""} if neither is reported. */
	public String getColumn() {
		return column == null ? "" : column;
	}

	/** 1-based position of the column within the index. */
	public int getOrdinalPosition() {
		return ordinalPosition;
	}

	public boolean isUnique() {
		return unique;
	}

	/** Identifies the index this column belongs to (index names are only unique per schema, or per table on some databases). */
	public String indexKey() {
		return catalog + "|" + schema + "|" + table + "|" + indexName;
	}
}
