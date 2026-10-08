package com.upandcoding.broadsql.dao.load;

/** A parsed {@code LOAD}/{@code BATCHLOAD} command line - see {@link LoadCommandParser}. */
public final class LoadStatement {

	/**
	 * {@code PREVIEW}: validate and report only, never write, never ask for confirmation.
	 * {@code CONFIRM}: the default (no trailing keyword) - validate, then ask for confirmation before
	 * writing (interactive), or refuse outright (inside a script - see SPRINT 0912B section 4.4).
	 * {@code EXECUTE}: validate, then write immediately without asking - the only mode that authorizes
	 * a write inside a script.
	 */
	public enum ExecutionMode {
		PREVIEW, CONFIRM, EXECUTE
	}

	private final String tableName;
	private final String fileName;
	private final ExecutionMode executionMode;

	public LoadStatement(String tableName, String fileName, ExecutionMode executionMode) {
		this.tableName = tableName;
		this.fileName = fileName;
		this.executionMode = executionMode;
	}

	public String getTableName() {
		return tableName;
	}

	public String getFileName() {
		return fileName;
	}

	public ExecutionMode getExecutionMode() {
		return executionMode;
	}
}
