package com.upandcoding.broadsql.dao.load;

import java.util.Collections;
import java.util.List;

/**
 * The full result of {@code LOAD} preflight ({@link LoadValidator}): everything a preview needs to
 * display, and - only when {@link #isReadyToExecute()} - everything {@link LoadExecutor} needs to
 * run, already converted to bound JDBC values. Preflight and execution never re-parse the source a
 * second time.
 *
 * <p>Per SPRINT 0912B section 11 ("no database write may occur before preflight ... is complete") and
 * this engine's own all-or-nothing rule (documented on {@link LoadValidator}): a plan is ready to
 * execute only when every source header resolved to a real column <em>and</em> every row converted
 * cleanly. Any unmapped header or any row-level issue refuses the whole load, not just the affected
 * rows - there is no silent partial/best-effort load.
 */
public final class LoadPlan {

	private final LoadTarget target;
	private final String sourceFile;
	private final List<String> insertColumns;
	private final List<String> unmappedHeaders;
	private final int sourceRowCount;
	private final int validRowCount;
	private final List<LoadRowIssue> issues;
	private final List<Object[]> boundRows;

	public LoadPlan(LoadTarget target, String sourceFile, List<String> insertColumns, List<String> unmappedHeaders,
			int sourceRowCount, int validRowCount, List<LoadRowIssue> issues, List<Object[]> boundRows) {
		this.target = target;
		this.sourceFile = sourceFile;
		this.insertColumns = insertColumns;
		this.unmappedHeaders = unmappedHeaders;
		this.sourceRowCount = sourceRowCount;
		this.validRowCount = validRowCount;
		this.issues = issues;
		this.boundRows = boundRows;
	}

	public LoadTarget getTarget() {
		return target;
	}

	public String getSourceFile() {
		return sourceFile;
	}

	public List<String> getInsertColumns() {
		return insertColumns;
	}

	public List<String> getUnmappedHeaders() {
		return unmappedHeaders;
	}

	public int getSourceRowCount() {
		return sourceRowCount;
	}

	public int getValidRowCount() {
		return validRowCount;
	}

	public int getRejectedRowCount() {
		return sourceRowCount - validRowCount;
	}

	public List<LoadRowIssue> getIssues() {
		return Collections.unmodifiableList(issues);
	}

	public List<Object[]> getBoundRows() {
		return Collections.unmodifiableList(boundRows);
	}

	public boolean isReadyToExecute() {
		return unmappedHeaders.isEmpty() && issues.isEmpty();
	}
}
