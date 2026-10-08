package com.upandcoding.broadsql.controller.shell.scriptlibrary;

/**
 * What produced one {@link RevisionSummary} - informational, shown by {@code HistoryDialog}/
 * {@code Compare}; never itself a gating condition for whether a revision is created (see
 * {@code RevisionVault}'s own javadoc, "Revision creation semantics").
 */
public enum RevisionOrigin {

	/** The asset's very first recorded revision - either a brand-new asset, or the baseline BroadSQL observed the first time it opened a previously-unmanaged file. */
	CREATE,
	/** The saved body content changed (metadata may also have changed at the same time). */
	EDIT_SAVE,
	/** Only metadata changed; the body text is byte-identical to the previous revision. */
	METADATA_ONLY,
	/** Only the asset's path changed (rename/move); body and metadata are byte-identical to the previous revision. */
	RENAME,
	/** Produced by restoring a historical revision - always creates a new revision, even if the restored state happens to match the current one. */
	RESTORE
}
