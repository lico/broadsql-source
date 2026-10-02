package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plain, non-Swing model of the Script Library workspace's session state: whether the workspace is
 * considered open, which assets currently have a tab, which tab is active, and each tab's dirty flag -
 * the open/focus decision logic the sprint's headless-testing strategy requires living outside any
 * {@link javax.swing.JFrame} (see
 * {@code docs/plans/SPRINT_0917-01_IMPLEMENTATION_PLAN.md}, "Headless vs. real GUI verification").
 * {@code ScriptLibraryFrame} is a thin renderer over this class, never the other way around.
 *
 * <p>Not thread-safe by design - like every other Swing-adjacent session model in this codebase, all
 * calls are expected to happen on the EDT (the frame marshals onto it; see the frame's own class
 * Javadoc).
 */
public final class ScriptLibrarySession {

	private boolean open;
	private final Map<String, TabState> tabs = new LinkedHashMap<>();
	private String activeAssetId;

	public boolean isOpen() {
		return open;
	}

	public void markOpen() {
		open = true;
	}

	/** Window X (hide) never calls this - per the window-lifecycle binding decision, hiding must never discard tabs/session state. Provided for tests and for a genuine full-shutdown path only. */
	public void markClosed() {
		open = false;
	}

	public boolean hasTab(String assetId) {
		return tabs.containsKey(assetId);
	}

	/**
	 * Registers a new tab for {@code assetId} if none exists yet, or simply refreshes/reselects the
	 * existing one - repeated open requests for the same asset never duplicate a tab. Always makes the
	 * asset the active tab.
	 *
	 * @return {@code true} if a new tab was created; {@code false} if an existing tab was reused.
	 */
	public boolean openOrFocus(String assetId, String relativePath) {
		TabState existing = tabs.get(assetId);
		boolean created = existing == null;
		if (created) {
			tabs.put(assetId, new TabState(assetId, relativePath));
		} else {
			existing.relativePath = relativePath;
		}
		activeAssetId = assetId;
		return created;
	}

	public String activeAssetId() {
		return activeAssetId;
	}

	public void setActiveAssetId(String assetId) {
		if (assetId == null || tabs.containsKey(assetId)) {
			activeAssetId = assetId;
		}
	}

	public TabState tab(String assetId) {
		return tabs.get(assetId);
	}

	public Collection<String> openAssetIds() {
		return List.copyOf(tabs.keySet());
	}

	public void setDirty(String assetId, boolean dirty) {
		TabState tab = tabs.get(assetId);
		if (tab != null) {
			tab.dirty = dirty;
		}
	}

	public boolean isDirty(String assetId) {
		TabState tab = tabs.get(assetId);
		return tab != null && tab.dirty;
	}

	public boolean isAnyDirty() {
		for (TabState tab : tabs.values()) {
			if (tab.dirty) {
				return true;
			}
		}
		return false;
	}

	public List<String> dirtyAssetIds() {
		List<String> dirty = new ArrayList<>();
		for (TabState tab : tabs.values()) {
			if (tab.dirty) {
				dirty.add(tab.assetId);
			}
		}
		return dirty;
	}

	/** Closes a tab (a real close, not the window's own hide-on-X) - the caller is responsible for the Save/Discard/Cancel dirty guard before calling this. */
	public void closeTab(String assetId) {
		tabs.remove(assetId);
		if (Objects.equals(activeAssetId, assetId)) {
			activeAssetId = tabs.isEmpty() ? null : tabs.keySet().iterator().next();
		}
	}

	public int tabCount() {
		return tabs.size();
	}

	/** One open tab's state: which asset, its display path (kept current across a rename made elsewhere in the same session), and whether it has unsaved edits. */
	public static final class TabState {
		public final String assetId;
		public String relativePath;
		public boolean dirty;

		TabState(String assetId, String relativePath) {
			this.assetId = assetId;
			this.relativePath = relativePath;
		}
	}
}
