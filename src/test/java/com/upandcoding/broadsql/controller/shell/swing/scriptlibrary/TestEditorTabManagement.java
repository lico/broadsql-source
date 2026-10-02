package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptAsset;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibrarySession;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader;
import com.upandcoding.broadsql.controller.shell.swing.api.UnsavedChangesDialog;

/**
 * SPRINT 3009A (#187): the editor tabs' context menu. Every action acts relative to the tab that was right-clicked
 * (here never the active one, to prove it), and goes through the one close guard: a clean tab closes silently, a
 * dirty tab is never discarded without its Save/Discard/Cancel prompt.
 */
class TestEditorTabManagement {

	private static final class Harness {
		final ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		final ScriptLibrarySession session = new ScriptLibrarySession();
		final List<String> prompts = new ArrayList<>();
		UnsavedChangesDialog.Decision answer = UnsavedChangesDialog.Decision.DISCARD;
		final ScriptTabCloser closer = new ScriptTabCloser(pane, session, tab -> {
			prompts.add(tab.asset().displayName());
			return answer;
		}, tab -> {
			tab.markSaved(tab.asset());
			return true;
		});

		/** Opens A..E (or the given names); the last opened is active. */
		Harness(String... names) {
			for (String name : names) {
				String content = "select 1;";
				pane.openOrFocus(new ScriptAsset(name, name + ".sql", ScriptMetadataHeader.parse(content), content, 0L));
				session.openOrFocus(name, name + ".sql");
			}
		}

		ScriptEditorTab tab(String name) {
			return pane.tabForAsset(name);
		}

		void dirty(String... names) {
			for (String name : names) {
				tab(name).textArea().insert("-- edit\n", 0);
			}
		}

		List<String> open() {
			List<String> names = new ArrayList<>();
			for (int i = 0; i < pane.getTabCount(); i++) {
				names.add(((ScriptEditorTab) pane.getComponentAt(i)).asset().assetId());
			}
			return names;
		}

		/** Clicks the context-menu entry {@code label} of the tab {@code name}. */
		void click(String name, String label) {
			JPopupMenu menu = ScriptTabContextMenu.menuFor(pane, closer, tab(name), s -> {
			});
			for (java.awt.Component c : menu.getComponents()) {
				if (c instanceof JMenuItem item && item.getText().equals(label)) {
					Assertions.assertTrue(item.isEnabled(), label + " is enabled");
					item.doClick();
					return;
				}
			}
			Assertions.fail("no entry " + label);
		}
	}

	@Test
	void theMenuListsTheCloseActionsThenThePathActions() {
		Harness h = new Harness("A", "B");
		List<String> labels = ScriptTabContextMenu.labels(ScriptTabContextMenu.menuFor(h.pane, h.closer, h.tab("A"), s -> {
		}));
		Assertions.assertEquals(Arrays.asList("Close", "Close Other Tabs", "Close Tabs to the Right", "Close Tabs to the Left", "Close All Tabs", null,
				"Copy Library Path", "Copy Full Path", "Copy CLI Command", "Send to CLI"), labels);
	}

	@Test
	void closeClosesTheRightClickedTabNotTheActiveOne() {
		Harness h = new Harness("A", "B", "C");
		Assertions.assertEquals("C", h.pane.activeTab().asset().assetId());
		h.click("A", "Close");
		Assertions.assertEquals(List.of("B", "C"), h.open());
		Assertions.assertEquals(2, h.session.tabCount());
	}

	@Test
	void closeOthersKeepsOnlyTheRightClickedTab() {
		Harness h = new Harness("A", "B", "C", "D");
		h.click("B", "Close Other Tabs");
		Assertions.assertEquals(List.of("B"), h.open());
	}

	@Test
	void closeToTheRightAndToTheLeftAreRelativeToTheRightClickedTab() {
		Harness h = new Harness("A", "B", "C", "D", "E");
		h.click("C", "Close Tabs to the Right");
		Assertions.assertEquals(List.of("A", "B", "C"), h.open());
		h.click("B", "Close Tabs to the Left");
		Assertions.assertEquals(List.of("B", "C"), h.open());
	}

	@Test
	void closeAllClosesEveryTab() {
		Harness h = new Harness("A", "B", "C");
		h.click("B", "Close All Tabs");
		Assertions.assertEquals(List.of(), h.open());
		Assertions.assertEquals(0, h.session.tabCount());
	}

	@Test
	void entriesWithNothingToCloseAreDisabled() {
		Harness h = new Harness("A", "B");
		JPopupMenu first = ScriptTabContextMenu.menuFor(h.pane, h.closer, h.tab("A"), s -> {
		});
		Harness single = new Harness("X");
		JPopupMenu only = ScriptTabContextMenu.menuFor(single.pane, single.closer, single.tab("X"), s -> {
		});
		Assertions.assertFalse(item(first, "Close Tabs to the Left").isEnabled());
		Assertions.assertTrue(item(first, "Close Tabs to the Right").isEnabled());
		Assertions.assertFalse(item(only, "Close Other Tabs").isEnabled());
	}

	private static JMenuItem item(JPopupMenu menu, String label) {
		for (java.awt.Component c : menu.getComponents()) {
			if (c instanceof JMenuItem mi && mi.getText().equals(label)) {
				return mi;
			}
		}
		throw new AssertionError(label);
	}

	@Test
	void onlyDirtyTabsAreAskedAndACancelledOneStaysOpenAndDirty() {
		Harness h = new Harness("A", "B", "C", "D");
		h.dirty("A", "D");
		h.answer = UnsavedChangesDialog.Decision.CANCEL;

		h.click("B", "Close Other Tabs");

		Assertions.assertEquals(List.of("A.sql", "D.sql"), h.prompts, "one prompt per dirty tab, none for clean ones");
		Assertions.assertEquals(List.of("A", "B", "D"), h.open(), "the clean C closed; the cancelled dirty tabs stay");
		Assertions.assertTrue(h.tab("A").isDirty());
		Assertions.assertTrue(h.tab("D").isDirty());
	}

	@Test
	void dirtyTabsAreNeverDiscardedSilentlyByAnyBatchClose() {
		for (String action : List.of("Close", "Close Other Tabs", "Close Tabs to the Right", "Close Tabs to the Left", "Close All Tabs")) {
			Harness h = new Harness("A", "B", "C");
			h.dirty("A", "B", "C");
			h.answer = UnsavedChangesDialog.Decision.CANCEL;

			h.click("B", action);

			Assertions.assertEquals(List.of("A", "B", "C"), h.open(), action + ": Cancel keeps every dirty tab");
			Assertions.assertFalse(h.prompts.isEmpty(), action + " asked");
		}
	}

	@Test
	void saveAndDiscardAnswersAreHonouredPerTab() {
		Harness h = new Harness("A", "B", "C");
		h.dirty("A", "C");
		h.answer = UnsavedChangesDialog.Decision.SAVE;

		h.click("B", "Close Tabs to the Right");

		Assertions.assertEquals(List.of("C.sql"), h.prompts);
		Assertions.assertEquals(List.of("A", "B"), h.open());
		Assertions.assertTrue(h.tab("A").isDirty(), "a tab outside the scope is untouched");
	}

	@Test
	void theTabsToCloseAreFixedBeforeTheFirstPrompt() {
		Harness h = new Harness("A", "B", "C", "D");
		ScriptEditorTab b = h.tab("B");
		Assertions.assertEquals(List.of(h.tab("C"), h.tab("D")), h.closer.tabsRelativeTo(b, ScriptTabCloser.Scope.TO_THE_RIGHT));
		Assertions.assertEquals(List.of(h.tab("A")), h.closer.tabsRelativeTo(b, ScriptTabCloser.Scope.TO_THE_LEFT));
		Assertions.assertTrue(h.closer.closeRelativeTo(b, ScriptTabCloser.Scope.TO_THE_RIGHT));
	}

	@Test
	void thePathActionsActOnTheRightClickedTab() {
		Harness h = new Harness("A", "B");
		List<String> calls = new ArrayList<>();
		JPopupMenu menu = ScriptTabContextMenu.menuFor(h.pane, h.closer, h.tab("A"), calls::add);
		item(menu, "Copy Library Path").doClick();
		item(menu, "Send to CLI").doClick();
		Assertions.assertEquals(List.of("copy:LIBRARY_PATH:A.sql", "send:A.sql"), calls);
	}
}
