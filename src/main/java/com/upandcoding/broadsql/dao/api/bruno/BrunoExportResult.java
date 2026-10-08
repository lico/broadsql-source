package com.upandcoding.broadsql.dao.api.bruno;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Diagnostics from one {@link BrunoCollectionExporter} run - the export-side mirror of
 * {@link BrunoImportResult}. Currently only tracks authentication configurations that could not be
 * represented exactly in the exported OpenCollection YAML (docs/SPRINT XT02-sub sprint 5 - API
 * Configuration GUI + Bruno YAML Round-trip.md, section 38 - "warn the user before export... do not
 * silently corrupt semantics"); the export dialog surfaces {@link #getUnrepresentableAuth()} as its
 * warning list.
 */
public final class BrunoExportResult {

	private final List<String> unrepresentableAuth = new ArrayList<>();

	void recordUnrepresentableAuth(String message) {
		unrepresentableAuth.add(message);
	}

	/** One human-readable line per authentication configuration that could only be approximated on export - empty when everything exported exactly. */
	public List<String> getUnrepresentableAuth() {
		return Collections.unmodifiableList(unrepresentableAuth);
	}

	public boolean hasWarnings() {
		return !unrepresentableAuth.isEmpty();
	}

	@Override
	public String toString() {
		return "unrepresentableAuth=" + unrepresentableAuth;
	}
}
