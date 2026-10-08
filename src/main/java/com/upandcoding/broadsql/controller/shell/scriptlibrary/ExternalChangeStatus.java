package com.upandcoding.broadsql.controller.shell.scriptlibrary;

/** Result of {@link ScriptLibraryService#detectExternalChange} - whether a managed asset's on-disk file changed since a tab last loaded/saved it, spec section 29. */
public enum ExternalChangeStatus {
	/** The working file's last-modified timestamp matches what the caller already knows about. */
	UNCHANGED,
	/** The working file was modified outside BroadSQL since the caller's last known state. */
	CHANGED,
	/** The working file no longer exists on disk (removed outside BroadSQL). */
	DELETED_EXTERNALLY
}
