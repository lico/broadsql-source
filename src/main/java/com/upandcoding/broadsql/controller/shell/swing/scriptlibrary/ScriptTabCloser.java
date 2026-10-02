package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibrarySession;
import com.upandcoding.broadsql.controller.shell.swing.api.UnsavedChangesDialog;

/**
 * The editor's tab-closing rules, kept free of any {@code JFrame} so they are testable headlessly: the
 * Save/Discard/Cancel guard for a dirty tab, closing every tab (window close), and the application-exit
 * protection. A clean tab never prompts. {@code ScriptLibraryFrame} supplies the real dialog and the real
 * save action; tests supply counting fakes.
 */
final class ScriptTabCloser {

	private final ScriptEditorTabbedPane pane;
	private final ScriptLibrarySession session;
	private final Function<ScriptEditorTab, UnsavedChangesDialog.Decision> prompt;
	private final Predicate<ScriptEditorTab> saver;

	/**
	 * @param prompt asks the user what to do with a dirty tab
	 * @param saver  saves a tab, returning {@code true} only if the save succeeded
	 */
	ScriptTabCloser(ScriptEditorTabbedPane pane, ScriptLibrarySession session, Function<ScriptEditorTab, UnsavedChangesDialog.Decision> prompt,
			Predicate<ScriptEditorTab> saver) {
		this.pane = pane;
		this.session = session;
		this.prompt = prompt;
		this.saver = saver;
	}

	/** Closes {@code tab}. A dirty tab asks first; Cancel, or a failed Save, leaves it open and dirty. @return {@code true} if the tab was closed. */
	boolean close(ScriptEditorTab tab) {
		if (tab.isDirty()) {
			UnsavedChangesDialog.Decision decision = prompt.apply(tab);
			switch (decision) {
				case CANCEL -> {
					return false;
				}
				case SAVE -> {
					if (!saver.test(tab)) {
						return false;
					}
				}
				case DISCARD -> {
					// proceed: throw away the in-memory buffer
				}
			}
		}
		// Read after the prompt: the first Save of a new Script gives the tab its real asset id.
		String assetId = tab.asset().assetId();
		pane.closeTab(assetId);
		session.closeTab(assetId);
		return true;
	}

	/** Closes every tab, each through {@link #close}. @return {@code true} if no tab remains open (nothing was cancelled). */
	boolean closeAll() {
		for (String assetId : List.copyOf(pane.openAssetIds())) {
			ScriptEditorTab tab = pane.tabForAsset(assetId);
			if (tab != null) {
				close(tab);
			}
		}
		return pane.getTabCount() == 0;
	}

	/** Which tabs a batch close acts on, relative to the tab the tab context menu was opened on. */
	enum Scope {
		/** Every tab except the reference tab. */
		OTHERS,
		/** The tabs after the reference tab, in tab order. */
		TO_THE_RIGHT,
		/** The tabs before the reference tab, in tab order. */
		TO_THE_LEFT
	}

	/**
	 * Closes the tabs {@code scope} designates relative to {@code reference} (the tab that was right-clicked, not
	 * necessarily the active one), each through {@link #close}: exactly the guard of a single close and of
	 * {@link #closeAll}, so a clean tab closes silently, a dirty one asks Save/Discard/Cancel, and a Cancel (or a
	 * failed Save) leaves that tab open while the others continue. The set of tabs is fixed before the first
	 * prompt, so a tab closing never changes which tabs are "to the right".
	 *
	 * @return {@code true} if every designated tab was closed
	 */
	boolean closeRelativeTo(ScriptEditorTab reference, Scope scope) {
		List<ScriptEditorTab> targets = tabsRelativeTo(reference, scope);
		boolean allClosed = true;
		for (ScriptEditorTab tab : targets) {
			allClosed &= close(tab);
		}
		return allClosed;
	}

	/** The tabs {@link #closeRelativeTo} would close, in tab order. */
	List<ScriptEditorTab> tabsRelativeTo(ScriptEditorTab reference, Scope scope) {
		int referenceIndex = pane.indexOfComponent(reference);
		List<ScriptEditorTab> targets = new java.util.ArrayList<>();
		if (referenceIndex < 0) {
			return targets;
		}
		for (int i = 0; i < pane.getTabCount(); i++) {
			boolean wanted = switch (scope) {
				case OTHERS -> i != referenceIndex;
				case TO_THE_RIGHT -> i > referenceIndex;
				case TO_THE_LEFT -> i < referenceIndex;
			};
			if (wanted && pane.getComponentAt(i) instanceof ScriptEditorTab tab) {
				targets.add(tab);
			}
		}
		return targets;
	}

	/**
	 * Application exit: offers Save/Discard for every tab that is really dirty. A tab that was closed earlier
	 * is no longer in the pane, and a clean tab is never asked about. Cancel here can only mean "leave this
	 * buffer unsaved": the application is already exiting.
	 *
	 * @return the number of prompts shown
	 */
	int protectDirtyTabsAtExit() {
		int prompts = 0;
		for (String assetId : List.copyOf(pane.openAssetIds())) {
			ScriptEditorTab tab = pane.tabForAsset(assetId);
			if (tab != null && tab.isDirty()) {
				prompts++;
				if (prompt.apply(tab) == UnsavedChangesDialog.Decision.SAVE) {
					saver.test(tab);
				}
			}
		}
		return prompts;
	}
}
