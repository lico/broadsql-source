package com.upandcoding.broadsql.dao.load;

/**
 * One rejected value found during {@code LOAD} preflight validation - never renders a value that
 * cannot be converted as anything other than data (SPRINT 0912B section 8: "Conversion errors must
 * identify source row, source column, target column, target type, offending value and reason").
 */
public final class LoadRowIssue {

	private final int sourceRow;
	private final String sourceHeader;
	private final String targetColumn;
	private final String targetTypeName;
	private final String rawValue;
	private final String reason;

	public LoadRowIssue(int sourceRow, String sourceHeader, String targetColumn, String targetTypeName, String rawValue, String reason) {
		this.sourceRow = sourceRow;
		this.sourceHeader = sourceHeader;
		this.targetColumn = targetColumn;
		this.targetTypeName = targetTypeName;
		this.rawValue = rawValue;
		this.reason = reason;
	}

	public int getSourceRow() {
		return sourceRow;
	}

	public String getSourceHeader() {
		return sourceHeader;
	}

	public String getTargetColumn() {
		return targetColumn;
	}

	public String getTargetTypeName() {
		return targetTypeName;
	}

	public String getRawValue() {
		return rawValue;
	}

	public String getReason() {
		return reason;
	}

	@Override
	public String toString() {
		return "Row " + sourceRow + " / " + sourceHeader + " -> " + targetColumn + " (" + targetTypeName + "): value \""
				+ rawValue + "\" " + reason;
	}
}
