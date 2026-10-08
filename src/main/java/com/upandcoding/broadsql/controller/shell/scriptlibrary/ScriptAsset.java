package com.upandcoding.broadsql.controller.shell.scriptlibrary;

/**
 * An immutable snapshot of one Script in the Scripts Library, as loaded by {@link ScriptLibraryService}
 * - the domain object the Swing layer renders, never a raw {@link java.io.File}/path directly (SPRINT
 * 0917-01, spec section 14 - "do not make the Swing tree directly manipulate File objects throughout the
 * UI"). SPRINT 1909S: there is a single kind of Script, so there is no asset type.
 */
public final class ScriptAsset {

	private final String assetId;
	private final String relativePath;
	private final ScriptMetadataHeader metadataHeader;
	private final String content;
	private final long lastModifiedMillis;

	/** The name shown for a new, not yet saved Script (which has no path); {@code null} for a saved one. */
	private final String unsavedName;

	public ScriptAsset(String assetId, String relativePath, ScriptMetadataHeader metadataHeader, String content, long lastModifiedMillis) {
		this(assetId, relativePath, metadataHeader, content, lastModifiedMillis, null);
	}

	private ScriptAsset(String assetId, String relativePath, ScriptMetadataHeader metadataHeader, String content, long lastModifiedMillis, String unsavedName) {
		this.assetId = assetId;
		this.relativePath = relativePath;
		this.metadataHeader = metadataHeader;
		this.content = content;
		this.lastModifiedMillis = lastModifiedMillis;
		this.unsavedName = unsavedName;
	}

	/**
	 * A new Script the Editor holds only in memory (SPRINT 3009A): no file, no path ({@link #relativePath()} is
	 * {@code null}), no revision history, until its first Save names it. {@code assetId} is a session-local key,
	 * never a vault id.
	 */
	public static ScriptAsset unsaved(String assetId, String name, String content) {
		return new ScriptAsset(assetId, null, ScriptMetadataHeader.parse(content), content, 0L, name);
	}

	/** Whether this is a new Script not saved yet ({@link #unsaved}). */
	public boolean isUnsaved() {
		return relativePath == null;
	}

	/** Stable, immutable identity - independent of {@link #relativePath()}. See {@code RevisionVault}. */
	public String assetId() {
		return assetId;
	}

	/** Path relative to the Scripts Library root, with forward slashes. Mutable in the sense that a rename changes it without changing {@link #assetId()}. */
	public String relativePath() {
		return relativePath;
	}

	public ScriptMetadataHeader metadataHeader() {
		return metadataHeader;
	}

	/** Full file content, header included. */
	public String content() {
		return content;
	}

	public long lastModifiedMillis() {
		return lastModifiedMillis;
	}

	/** The last path segment of {@link #relativePath()} - what the browser tree/tab title shows. */
	public String displayName() {
		if (relativePath == null) {
			return unsavedName;
		}
		int slash = relativePath.lastIndexOf('/');
		return slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
	}
}
