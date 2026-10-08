package com.upandcoding.broadsql.controller.shell.swing.api;

/**
 * The small common contract {@link JApiEndpointTreePanel} (and, in principle, any other host that
 * swaps between per-object editor panels) uses to address whichever editor - {@link
 * JApiFolderEditorPanel} or {@link JApiEndpointEditorPanel} - is currently shown, without needing to
 * know which one it is. API Quality and UX Consolidation sprint, Phase 2: introduced so the relocated
 * endpoint "Save" action and the unsaved-changes navigation guard ({@link UnsavedChangesDialog}) can
 * be written once against this interface rather than duplicated per concrete editor type.
 */
public interface ApiObjectEditor {

	/** Whether the currently loaded object has real, unsaved local edits - a value-snapshot comparison, never a listener-driven flag (see each implementation for why). */
	boolean isDirty();

	/**
	 * Validates and persists the currently loaded object, exactly as its own "Save" button would.
	 *
	 * @return {@code true} if the object was actually persisted (validation passed); {@code false} if
	 *         validation failed (an error dialog has already been shown - the caller must not proceed
	 *         with whatever navigation prompted this call, and must leave the current object selected).
	 */
	boolean trySave();
}
