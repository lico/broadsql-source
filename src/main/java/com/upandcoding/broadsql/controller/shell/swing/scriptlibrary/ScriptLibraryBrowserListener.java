package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

/**
 * What {@link ScriptLibraryBrowserPanel} asks its owner ({@code ScriptLibraryFrame}) to do - the panel
 * itself never touches {@code ScriptLibraryService}/history directly, keeping filesystem/history mutation
 * in one place. Paths are relative to the Scripts Library root, with forward slashes; a folder path is
 * {@code null} for the library root itself.
 */
public interface ScriptLibraryBrowserListener {

	void onAssetOpenRequested(String relativePath);

	/** {@code folderOrNull} is the folder that was selected (or that contains the selected Script) when New was invoked; {@code null} for the library root. */
	void onNewRequested(String folderOrNull);

	void onNewFolderRequested(String parentFolderOrNull);

	void onRenameRequested(String relativePath);

	void onDuplicateRequested(String relativePath);

	void onDeleteRequested(String relativePath);

	void onRenameFolderRequested(String folderPath);

	void onDeleteFolderRequested(String folderPath);

	void onRefreshRequested();

	/**
	 * The selected Scripts and folders were dragged onto {@code destinationFolderOrNull} ({@code null}: the library
	 * root), already normalized ({@link ScriptLibraryBrowserPanel#normalize}: nothing inside a selected folder, no
	 * duplicate). The panel has refused the drops that can never be valid (a folder onto itself or a subfolder,
	 * everything already there); the owner moves them as one operation and reports the rest.
	 */
	default void onMoveRequested(java.util.List<ScriptLibraryBrowserPanel.MovedItem> items, String destinationFolderOrNull) {
	}

	/** Copy Path from the tree's context menu, for a Script or a folder. */
	default void onCopyPathRequested(String relativePath, ScriptPathText.Kind kind) {
	}

	/** Send to CLI from the tree's context menu. */
	default void onSendToCliRequested(String relativePath) {
	}
}
