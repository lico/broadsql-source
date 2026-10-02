package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.util.Map;

/**
 * Tooltip text for {@link MetadataPanel}'s fields (SPRINT 0917-01, spec section 18: "explains fields
 * through labels/tooltips/help"). Reflects the actual current metadata model
 * ({@code EntryMetadata}'s own Javadoc) - no field/wording invented here that the parser doesn't
 * itself support.
 */
final class MetadataFieldHelp {

	private MetadataFieldHelp() {
	}

	static final String DESCRIPTION = "A short, one-line description, shown in LIB LIST and LIB FIND grids.";
	static final String INSTANCE = "The Database Group(s) this script applies to, or ALL. Click or type to choose from the known "
			+ "Database Groups; Up/Down and Enter select, Backspace removes the last one. None selected (NONE) if it applies everywhere / hasn't been scoped yet.";
	static final String ENVIRONMENT = "The environment(s) this script applies to (e.g. PROD, QA), or ALL. Click or type to choose from the "
			+ "known Environments; Up/Down and Enter select, Backspace removes the last one. None selected (NONE) if it applies everywhere / hasn't been scoped yet.";
	static final String TAGS = "Comma-separated free-text tags, shown in the grid and matched by FIND.";
	static final String STATUS = "Purely informational: draft, stable, or deprecated. Never changes what LIST/FIND return.";

	static final Map<String, String> BY_KEY = Map.of(
			"description", DESCRIPTION,
			"instance", INSTANCE,
			"environment", ENVIRONMENT,
			"tags", TAGS,
			"status", STATUS);
}
