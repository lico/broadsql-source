package com.upandcoding.broadsql.controller.shell.scriptlibrary;

/** One metadata field that differs between two revisions (spec section 10.5) - e.g. {@code Description: "Cleanup inactive customer" -> "Cleanup inactive customer records older than 90 days"}. */
public final class MetadataFieldDiff {

	private final String fieldName;
	private final String oldValue;
	private final String newValue;

	public MetadataFieldDiff(String fieldName, String oldValue, String newValue) {
		this.fieldName = fieldName;
		this.oldValue = oldValue;
		this.newValue = newValue;
	}

	public String fieldName() {
		return fieldName;
	}

	public String oldValue() {
		return oldValue;
	}

	public String newValue() {
		return newValue;
	}
}
