package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.Component;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JTabbedPane;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptAsset;

/**
 * Hosts every open {@link ScriptEditorTab}, keyed by {@link ScriptAsset#assetId()} - never by path,
 * so a rename made elsewhere in the same session never orphans or duplicates a tab (SPRINT 0917-01).
 *
 * <p>SPRINT 3009A: also holds new Scripts not saved yet ({@link ScriptAsset#unsaved}), keyed by a session-local
 * id until their first Save gives them a file and a real asset id ({@link #adoptSaved}).
 */
public final class ScriptEditorTabbedPane extends JTabbedPane {

	/** The tab tooltip of a new Script, which has no path yet. */
	static final String UNSAVED_TOOLTIP = "New script, not saved yet";
	private static final String UNSAVED_ID_PREFIX = "unsaved:";

	private final Map<String, ScriptEditorTab> tabsByAssetId = new LinkedHashMap<>();
	private int unsavedCounter;

	/** Opens a new tab for {@code asset}, or simply focuses/returns the existing one - repeated calls for the same asset never duplicate a tab. */
	public ScriptEditorTab openOrFocus(ScriptAsset asset) {
		ScriptEditorTab existing = tabsByAssetId.get(asset.assetId());
		if (existing != null) {
			setSelectedComponent(existing);
			return existing;
		}
		ScriptEditorTab tab = new ScriptEditorTab(asset);
		tabsByAssetId.put(asset.assetId(), tab);
		addTab(titleFor(asset, false), null, tab, asset.isUnsaved() ? UNSAVED_TOOLTIP : asset.relativePath());
		setSelectedComponent(tab);
		tab.setDirtyListener(() -> refreshTitle(tab));
		return tab;
	}

	/**
	 * A new, unsaved Script tab ({@code New Script 1}, {@code New Script 2}, ...) holding {@code content}, made the
	 * active tab. It is clean until edited, so closing it untouched asks nothing; its first Save names it.
	 *
	 * @param folderOrNull the folder its first Save proposes ({@code null}: the library root)
	 */
	public ScriptEditorTab newUnsavedScript(String content, String folderOrNull) {
		unsavedCounter++;
		ScriptEditorTab tab = openOrFocus(ScriptAsset.unsaved(UNSAVED_ID_PREFIX + unsavedCounter, "New Script " + unsavedCounter, content));
		tab.setSuggestedFolder(folderOrNull);
		return tab;
	}

	/** An open new Script that has never been edited (EDIT with no name reuses it instead of adding another), or {@code null}. */
	public ScriptEditorTab untouchedUnsavedScript() {
		for (ScriptEditorTab tab : tabsByAssetId.values()) {
			if (tab.asset().isUnsaved() && !tab.isDirty()) {
				return tab;
			}
		}
		return null;
	}

	/**
	 * After the first Save of the new Script in {@code tab}: the tab now represents the saved file {@code saved}
	 * (re-keyed under its real asset id, clean, titled and tooltipped with its name and path), still in the same
	 * place with the same buffer and undo history.
	 */
	public void adoptSaved(ScriptEditorTab tab, ScriptAsset saved) {
		tabsByAssetId.values().remove(tab);
		tabsByAssetId.put(saved.assetId(), tab);
		tab.markSaved(saved);
		int index = indexOfComponent(tab);
		if (index >= 0) {
			setToolTipTextAt(index, saved.relativePath());
		}
	}

	private void refreshTitle(ScriptEditorTab tab) {
		int index = indexOfComponent(tab);
		if (index >= 0) {
			setTitleAt(index, titleFor(tab.asset(), tab.isDirty()));
		}
	}

	private static String titleFor(ScriptAsset asset, boolean dirty) {
		return asset.displayName() + (dirty ? " *" : "");
	}

	public ScriptEditorTab tabForAsset(String assetId) {
		return tabsByAssetId.get(assetId);
	}

	public void closeTab(String assetId) {
		ScriptEditorTab tab = tabsByAssetId.remove(assetId);
		if (tab != null) {
			remove(tab);
		}
	}

	public Collection<String> openAssetIds() {
		return tabsByAssetId.keySet();
	}

	/**
	 * After a rename or move of {@code moved} (matched by asset id): the open tab, if any, now represents the Script at
	 * its new path ({@link ScriptEditorTab#retarget}, so the buffer and dirty state are kept and the next Save writes
	 * to the new path), with its title refreshed. {@code null} if the Script has no open tab.
	 */
	public ScriptEditorTab retargetOpenTab(ScriptAsset moved) {
		ScriptEditorTab tab = tabsByAssetId.get(moved.assetId());
		if (tab != null) {
			tab.retarget(moved);
			refreshTitle(tab);
			int index = indexOfComponent(tab);
			if (index >= 0) {
				setToolTipTextAt(index, moved.relativePath());
			}
		}
		return tab;
	}

	/** Call after a rename changes the active asset's display name/path - refreshes just that tab's title. */
	public void refreshTitleFor(String assetId) {
		ScriptEditorTab tab = tabsByAssetId.get(assetId);
		if (tab != null) {
			refreshTitle(tab);
		}
	}

	public ScriptEditorTab activeTab() {
		Component selected = getSelectedComponent();
		return selected instanceof ScriptEditorTab tab ? tab : null;
	}
}
