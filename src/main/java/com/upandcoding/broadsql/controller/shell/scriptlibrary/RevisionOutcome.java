package com.upandcoding.broadsql.controller.shell.scriptlibrary;

/** The result of a {@code RevisionVault} record attempt: whether a new revision was actually created, and either way, the now-latest revision. */
public final class RevisionOutcome {

	private final boolean revisionCreated;
	private final RevisionSummary latestRevision;

	public RevisionOutcome(boolean revisionCreated, RevisionSummary latestRevision) {
		this.revisionCreated = revisionCreated;
		this.latestRevision = latestRevision;
	}

	/** {@code false} for a Save that changed nothing (unchanged body, metadata and path) - see {@code RevisionVault}'s "Revision creation semantics." */
	public boolean revisionCreated() {
		return revisionCreated;
	}

	public RevisionSummary latestRevision() {
		return latestRevision;
	}
}
