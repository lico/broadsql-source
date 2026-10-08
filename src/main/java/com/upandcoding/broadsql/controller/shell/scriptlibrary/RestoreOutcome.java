package com.upandcoding.broadsql.controller.shell.scriptlibrary;

/** The result of {@code RevisionVault#restore(assetId, revisionNumber, comment)} - restoring a historical revision's content/metadata into the current asset, at its current path. */
public final class RestoreOutcome {

	private final RevisionSummary newRevision;
	private final String restoredContent;

	public RestoreOutcome(RevisionSummary newRevision, String restoredContent) {
		this.newRevision = newRevision;
		this.restoredContent = restoredContent;
	}

	/** The newly created revision recording this restore (never the restored-from revision itself - restore always creates a new revision). */
	public RevisionSummary newRevision() {
		return newRevision;
	}

	/** Full content (header + body) to write to the working file at the asset's current path. */
	public String restoredContent() {
		return restoredContent;
	}
}
