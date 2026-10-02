package com.upandcoding.broadsql.controller.shell.scriptlibrary;

/**
 * One revision's metadata, without its content (see {@link Revision} for the full snapshot) - what
 * {@code RevisionVault#listRevisions}/{@code HistoryDialog} work with, since a history list should not
 * have to load every revision's full text just to display the list.
 */
public final class RevisionSummary {

	private final String assetId;
	private final int revisionNumber;
	private final long timestampMillis;
	private final String comment;
	private final RevisionOrigin origin;
	private final String relativePathAtRevision;
	private final String bodyHash;
	private final String metadataHash;

	public RevisionSummary(String assetId, int revisionNumber, long timestampMillis, String comment, RevisionOrigin origin,
			String relativePathAtRevision, String bodyHash, String metadataHash) {
		this.assetId = assetId;
		this.revisionNumber = revisionNumber;
		this.timestampMillis = timestampMillis;
		this.comment = comment;
		this.origin = origin;
		this.relativePathAtRevision = relativePathAtRevision;
		this.bodyHash = bodyHash;
		this.metadataHash = metadataHash;
	}

	public String assetId() {
		return assetId;
	}

	public int revisionNumber() {
		return revisionNumber;
	}

	public long timestampMillis() {
		return timestampMillis;
	}

	/** {@code null}/blank for a normal Save; set by {@code Save with Comment...} or {@code Restore}. */
	public String comment() {
		return comment;
	}

	public RevisionOrigin origin() {
		return origin;
	}

	public String relativePathAtRevision() {
		return relativePathAtRevision;
	}

	public String bodyHash() {
		return bodyHash;
	}

	public String metadataHash() {
		return metadataHash;
	}
}
