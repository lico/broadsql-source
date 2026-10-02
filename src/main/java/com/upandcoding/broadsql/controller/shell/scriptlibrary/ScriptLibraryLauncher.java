package com.upandcoding.broadsql.controller.shell.scriptlibrary;

/**
 * The seam {@code EDIT}/{@code LIB EDIT} use to open/focus the BroadSQL Editor
 * workspace, without any command depending directly on the actual Swing frame - lets the command tests
 * verify the open/focus <em>decision</em> headlessly (a fake launcher recording calls), while the
 * production implementation
 * ({@code controller.shell.swing.scriptlibrary.ScriptLibraryWorkspaceLauncher}) does the real,
 * not-headlessly-testable {@link javax.swing.JFrame} work. There is exactly one production launcher and
 * one Editor session: no command has a second way to open an editor. The Editor shows the Scripts
 * Library (SPRINT 1909S), so there is nothing else to select.
 */
public interface ScriptLibraryLauncher {

	/**
	 * Opens/focuses the workspace with no Script opened.
	 *
	 * @param context this invocation's execution collaborators (connection, interpreter, vault) -
	 *                refreshed into the workspace on every call, since they can change between
	 *                invocations (e.g. {@code CONNECT}/{@code DISCONNECT}). Only {@code Run} ever reads
	 *                it - every other capability works the same regardless of its contents.
	 */
	void openWorkspace(ScriptRunContext context);

	/** Opens/focuses the workspace and opens/focuses the given Script (reuses an existing tab rather than duplicating one). See {@link #openWorkspace} for {@code context}. */
	void openAsset(String relativePath, ScriptRunContext context);

	/**
	 * The workspace is already open/focused (via {@link #openWorkspace} or {@link #openAsset}); additionally
	 * offer to create a new Script named {@code requestedName} - nothing matched it.
	 */
	void offerCreate(String requestedName, ScriptRunContext context);
}
