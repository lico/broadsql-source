package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.Collections;
import java.util.List;

/**
 * The result of {@link ScriptDiffService#diffMetadata} - spec section 10.5: "the user must not miss
 * metadata-only revisions," so this is always its own, always-shown section, never folded into the
 * text diff. Empty ({@link #fields()}) when no known metadata field changed between the two revisions.
 */
public final class MetadataDiffResult {

	private final List<MetadataFieldDiff> fields;

	public MetadataDiffResult(List<MetadataFieldDiff> fields) {
		this.fields = Collections.unmodifiableList(fields);
	}

	public List<MetadataFieldDiff> fields() {
		return fields;
	}

	public boolean hasChanges() {
		return !fields.isEmpty();
	}
}
