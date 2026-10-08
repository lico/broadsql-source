package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.List;
import java.util.stream.Collectors;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Thrown by {@link ScriptLibraryService#save} when the asset's metadata would corrupt catalog semantics
 * (unknown Database Group/environment, invalid pair, duplicate alias, invalid status). Nothing was written:
 * the caller keeps its tab dirty and can focus the field of {@link #issues()}'s first entry.
 */
public final class MetadataIntegrityException extends BroadSQLException {

	private static final long serialVersionUID = 1L;

	private final transient List<MetadataIssue> issues;

	public MetadataIntegrityException(List<MetadataIssue> issues) {
		super(issues.stream().map(MetadataIssue::message).collect(Collectors.joining("\n")));
		this.issues = List.copyOf(issues);
	}

	public List<MetadataIssue> issues() {
		return issues;
	}
}
