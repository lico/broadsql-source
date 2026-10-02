package com.upandcoding.broadsql.dao.model.metadata;

/**
 * SPRINT 2409K: one column pair of a foreign key, normalized from {@code DatabaseMetaData.getImportedKeys}/
 * {@code getExportedKeys} (same row shape for both). A composite foreign key is several instances sharing
 * {@link #getFkName()} and the referencing table, ordered by {@link #getKeySeq()}.
 */
public class ForeignKeyMetadata {

	private final String fkName;
	private final String fkCatalog;
	private final String fkSchema;
	private final String fkTable;
	private final String fkColumn;
	private final String pkCatalog;
	private final String pkSchema;
	private final String pkTable;
	private final String pkColumn;
	private final int keySeq;

	public ForeignKeyMetadata(String fkName, String fkCatalog, String fkSchema, String fkTable, String fkColumn, String pkCatalog, String pkSchema,
			String pkTable, String pkColumn, int keySeq) {
		this.fkName = fkName;
		this.fkCatalog = fkCatalog;
		this.fkSchema = fkSchema;
		this.fkTable = fkTable;
		this.fkColumn = fkColumn;
		this.pkCatalog = pkCatalog;
		this.pkSchema = pkSchema;
		this.pkTable = pkTable;
		this.pkColumn = pkColumn;
		this.keySeq = keySeq;
	}

	/** The constraint name, or {@code ""} when the driver reports none. */
	public String getFkName() {
		return fkName == null ? "" : fkName;
	}

	public String getFkCatalog() {
		return fkCatalog;
	}

	public String getFkSchema() {
		return fkSchema;
	}

	/** The referencing (declaring) table. */
	public String getFkTable() {
		return fkTable;
	}

	/** The referencing column. */
	public String getFkColumn() {
		return fkColumn;
	}

	public String getPkCatalog() {
		return pkCatalog;
	}

	public String getPkSchema() {
		return pkSchema;
	}

	/** The referenced table. */
	public String getPkTable() {
		return pkTable;
	}

	/** The referenced column. */
	public String getPkColumn() {
		return pkColumn;
	}

	/** 1-based position of this column pair within the foreign key. */
	public int getKeySeq() {
		return keySeq;
	}

	/** Identifies the foreign key this column pair belongs to (the name alone is not unique across tables, and may be blank). */
	public String constraintKey() {
		return fkCatalog + "|" + fkSchema + "|" + fkTable + "|" + getFkName() + "|" + pkSchema + "|" + pkTable;
	}
}
