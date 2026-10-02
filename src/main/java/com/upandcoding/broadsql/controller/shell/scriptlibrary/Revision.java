package com.upandcoding.broadsql.controller.shell.scriptlibrary;

/** A revision's full metadata plus its complete text content (header and body) - see {@link RevisionSummary} for the lighter, content-free form used by listings. */
public final class Revision {

	private final RevisionSummary summary;
	private final String content;

	public Revision(RevisionSummary summary, String content) {
		this.summary = summary;
		this.content = content;
	}

	public RevisionSummary summary() {
		return summary;
	}

	/** Full asset content (header + body) exactly as it existed at this revision. */
	public String content() {
		return content;
	}
}
