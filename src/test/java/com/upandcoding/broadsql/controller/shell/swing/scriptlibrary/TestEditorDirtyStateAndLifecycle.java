package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JTextField;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptAsset;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibrarySession;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader;
import com.upandcoding.broadsql.controller.shell.swing.api.UnsavedChangesDialog;

/**
 * SPRINT 1909S manual-test regressions: (1) opening a saved Script must not mark it dirty, (2) a closed editor
 * is closed, so application exit does not ask again about tabs that were already closed. Everything runs
 * headlessly on the real tab, tab pane, metadata panel and the frame-free {@link ScriptTabCloser} the frame uses.
 */
class TestEditorDirtyStateAndLifecycle {

	/** Header shapes that the old commit path reordered or reformatted, making an untouched Script look edited. */
	private static final String[] HEADERS = {
			"-- @description: nightly\n-- @instance MYWORLD\n-- @environment PROD\n-- @tags a, b\n-- @alias QR1\n-- @status stable\nselect 1;",
			"-- @alias QR1\n-- @status: draft\n-- @description: x\nselect 1;",
			"/* @environment PROD */\n-- @description  spaced   value  \nselect 1;",
			"-- @instance: A\n-- @instance: B\nselect 1;",
			"-- plain explanatory comment\n-- @tags   one,two\nselect 1;",
			"select 1;",
	};

	private static ScriptAsset asset(String id, String path, String content) {
		return new ScriptAsset(id, path, ScriptMetadataHeader.parse(content), content, 0L);
	}

	private static List<JTextField> textFields(Container container) {
		List<JTextField> found = new ArrayList<>();
		for (Component c : container.getComponents()) {
			if (c instanceof JTextField f) {
				found.add(f);
			}
			if (c instanceof Container inner) {
				found.addAll(textFields(inner));
			}
		}
		return found;
	}

	/** Mirrors ScriptLibraryFrame: the panel's commit listener patches the bound tab's buffer. */
	private static void bind(MetadataPanel panel, ScriptEditorTab tab) {
		panel.setCommitListener(change -> ScriptLibraryFrame.replaceChangedRegion(tab.textArea(), change.applyTo(tab.currentText())));
		panel.load(ScriptMetadataHeader.parse(tab.currentText()));
	}

	// ---- Invariant A: opening is not editing ----

	@Test
	void openingAnUntouchedScriptLeavesItClean() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptEditorTab tab = pane.openOrFocus(asset("a", "a.sql", "select 1;"));
		Assertions.assertFalse(tab.isDirty());
		Assertions.assertEquals("a.sql", pane.getTitleAt(0), "no asterisk on a freshly opened tab");
	}

	@Test
	void editingMakesItDirtyAndSavingMakesItCleanAgain() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptAsset a = asset("a", "a.sql", "select 1;");
		ScriptEditorTab tab = pane.openOrFocus(a);

		tab.textArea().insert("-- edit\n", 0);
		Assertions.assertTrue(tab.isDirty());
		Assertions.assertEquals("a.sql *", pane.getTitleAt(0));

		tab.markSaved(asset("a", "a.sql", tab.currentText()));
		Assertions.assertFalse(tab.isDirty());
		Assertions.assertEquals("a.sql", pane.getTitleAt(0));
	}

	@Test
	void programmaticInitializationOfTheMetadataControlsNeverDirtiesTheTab() {
		for (String content : HEADERS) {
			ScriptEditorTab tab = new ScriptEditorTab(asset("a", "a.sql", content));
			MetadataPanel panel = new MetadataPanel();
			List<MetadataPanel.Change> commits = new ArrayList<>();
			panel.setCommitListener(commits::add);

			panel.load(ScriptMetadataHeader.parse(tab.currentText()));
			panel.commitPendingEdits(); // what a tab switch, focus loss or Save does
			panel.commitPendingEdits();

			Assertions.assertTrue(commits.isEmpty(), "loading must not rewrite the header of:\n" + content + "\n-> " + commits);
			Assertions.assertFalse(tab.isDirty(), content);
		}
	}

	@Test
	void theExactChainThatDirtiedTabsIsFixedOpeningSeveralScriptsInTurnWithTheMetadataPanelBoundLikeTheFrameDoes() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		MetadataPanel panel = new MetadataPanel();
		ScriptEditorTab previous = null;
		List<ScriptEditorTab> tabs = new ArrayList<>();
		for (int i = 0; i < HEADERS.length; i++) {
			// frame.refreshMetadataPanel(): commit pending edits for the tab that WAS bound, then bind the new one
			if (previous != null) {
				panel.commitPendingEdits();
			}
			ScriptEditorTab tab = pane.openOrFocus(asset("id" + i, "S" + i + ".sql", HEADERS[i]));
			bind(panel, tab);
			tabs.add(tab);
			previous = tab;
		}
		panel.commitPendingEdits();
		for (ScriptEditorTab tab : tabs) {
			Assertions.assertFalse(tab.isDirty(), "an unedited tab must stay clean: " + tab.asset().relativePath());
		}
	}

	@Test
	void aRealMetadataEditStillDirtiesTheTabAndTouchesOnlyThatField() {
		String content = "-- @instance MYWORLD\n-- @description old\n-- @alias QR1\n-- @environment PROD\nselect 1;";
		ScriptEditorTab tab = new ScriptEditorTab(asset("a", "a.sql", content));
		MetadataPanel panel = new MetadataPanel();
		bind(panel, tab);

		((javax.swing.text.JTextComponent) panel.field("description")).setText("new description");
		panel.commitPendingEdits();

		Assertions.assertTrue(tab.isDirty(), "a genuine edit must dirty the tab");
		Assertions.assertTrue(tab.currentText().contains("-- @instance MYWORLD"), "an untouched field keeps its exact original line:\n" + tab.currentText());
		Assertions.assertTrue(tab.currentText().contains("-- @environment PROD"), tab.currentText());
		Assertions.assertTrue(tab.currentText().contains("-- @alias QR1"), tab.currentText());
		// SPRINT 3009A correction pass: the edited line keeps its own format (here, a space separator, no colon).
		Assertions.assertEquals("-- @instance MYWORLD\n-- @description new description\n-- @alias QR1\n-- @environment PROD\nselect 1;", tab.currentText());
		Assertions.assertFalse(tab.currentText().contains("old"), tab.currentText());
	}

	@Test
	void clearingAFieldTheUserActuallyClearedStillWorks() {
		ScriptEditorTab tab = new ScriptEditorTab(asset("a", "a.sql", "-- @description old\n-- @tags x\nselect 1;"));
		MetadataPanel panel = new MetadataPanel();
		bind(panel, tab);

		((javax.swing.text.JTextComponent) panel.field("tags")).setText("");
		panel.commitPendingEdits();

		Assertions.assertTrue(tab.isDirty());
		Assertions.assertFalse(tab.currentText().contains("@tags"), tab.currentText());
		Assertions.assertTrue(tab.currentText().contains("-- @description old"), tab.currentText());
	}

	// ---- Invariant B: a closed editor is closed ----

	private static final class Harness {
		final ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		final ScriptLibrarySession session = new ScriptLibrarySession();
		final List<String> prompts = new ArrayList<>();
		UnsavedChangesDialog.Decision answer = UnsavedChangesDialog.Decision.DISCARD;
		boolean saveSucceeds = true;
		final List<String> saved = new ArrayList<>();
		final ScriptTabCloser closer = new ScriptTabCloser(pane, session, tab -> {
			prompts.add(tab.asset().displayName());
			return answer;
		}, tab -> {
			saved.add(tab.asset().displayName());
			if (saveSucceeds) {
				tab.markSaved(tab.asset());
			}
			return saveSucceeds;
		});

		ScriptEditorTab open(String id, String path) {
			ScriptEditorTab tab = pane.openOrFocus(asset(id, path, "select 1;"));
			session.openOrFocus(id, path);
			return tab;
		}
	}

	@Test
	void openThreeSavedScriptsCloseTheEditorThenExitAsksNothing() {
		Harness h = new Harness();
		h.open("1", "SCRIPT1.SQL");
		h.open("2", "SCRIPT2.SQL");
		h.open("3", "SCRIPT3.SQL");

		Assertions.assertTrue(h.closer.closeAll(), "the window close (the X) closes every tab");
		Assertions.assertEquals(0, h.pane.getTabCount());
		Assertions.assertEquals(0, h.session.tabCount());
		Assertions.assertTrue(h.prompts.isEmpty(), "no prompt for clean tabs: " + h.prompts);

		Assertions.assertEquals(0, h.closer.protectDirtyTabsAtExit(), "application EXIT must not re-close closed tabs");
		Assertions.assertTrue(h.prompts.isEmpty(), h.prompts.toString());
	}

	@Test
	void theExactUserReproductionOneScriptNoChangesCloseEditorExit() {
		Harness h = new Harness();
		h.open("1", "SCRIPT1.SQL");
		Assertions.assertTrue(h.closer.closeAll());
		Assertions.assertEquals(0, h.closer.protectDirtyTabsAtExit());
		Assertions.assertTrue(h.prompts.isEmpty());
	}

	@Test
	void withTheEditorStillOpenAndAllTabsCleanExitAlsoAsksNothing() {
		Harness h = new Harness();
		h.open("1", "A.SQL");
		h.open("2", "B.SQL");
		Assertions.assertEquals(0, h.closer.protectDirtyTabsAtExit());
	}

	@Test
	void aGenuinelyDirtyTabIsStillProtectedOnceOnCloseAndOnExit() {
		Harness h = new Harness();
		ScriptEditorTab tab = h.open("1", "A.SQL");
		tab.textArea().insert("-- changed\n", 0);
		h.answer = UnsavedChangesDialog.Decision.CANCEL;

		Assertions.assertFalse(h.closer.close(tab), "Cancel keeps the tab open");
		Assertions.assertEquals(List.of("A.SQL"), h.prompts, "exactly one Save/Discard/Cancel prompt");
		Assertions.assertEquals(1, h.pane.getTabCount());
		Assertions.assertTrue(tab.isDirty());

		Assertions.assertFalse(h.closer.closeAll(), "a cancelled tab keeps the window open");
		Assertions.assertEquals(2, h.prompts.size());

		Assertions.assertEquals(1, h.closer.protectDirtyTabsAtExit(), "an unsaved edit is still protected at exit");
	}

	@Test
	void discardSaveAndFailedSaveOnADirtyTab() {
		Harness h = new Harness();
		ScriptEditorTab a = h.open("1", "A.SQL");
		a.textArea().insert("x", 0);
		h.answer = UnsavedChangesDialog.Decision.DISCARD;
		Assertions.assertTrue(h.closer.close(a));
		Assertions.assertEquals(0, h.pane.getTabCount());

		ScriptEditorTab b = h.open("2", "B.SQL");
		b.textArea().insert("x", 0);
		h.answer = UnsavedChangesDialog.Decision.SAVE;
		h.saveSucceeds = false;
		Assertions.assertFalse(h.closer.close(b), "a failed save leaves the tab open and dirty");
		Assertions.assertTrue(b.isDirty());
		h.saveSucceeds = true;
		Assertions.assertTrue(h.closer.close(b));
		Assertions.assertEquals(List.of("B.SQL", "B.SQL"), h.saved);
		Assertions.assertEquals(0, h.pane.getTabCount());
	}

	@Test
	void severalDirtyTabsEachGetExactlyOnePromptAndCleanOnesNone() {
		Harness h = new Harness();
		ScriptEditorTab a = h.open("1", "DIRTY1.SQL");
		h.open("2", "CLEAN.SQL");
		ScriptEditorTab c = h.open("3", "DIRTY2.SQL");
		a.textArea().insert("x", 0);
		c.textArea().insert("x", 0);
		h.answer = UnsavedChangesDialog.Decision.DISCARD;

		Assertions.assertTrue(h.closer.closeAll());

		Assertions.assertEquals(List.of("DIRTY1.SQL", "DIRTY2.SQL"), h.prompts);
	}

	@Test
	void afterTheEditorIsClosedItCanBeReopenedWithNoStaleState() {
		Harness h = new Harness();
		h.open("1", "A.SQL");
		h.open("2", "B.SQL");
		Assertions.assertTrue(h.closer.closeAll());
		h.session.markClosed();
		Assertions.assertFalse(h.session.isOpen());

		h.session.markOpen();
		ScriptEditorTab reopened = h.open("1", "A.SQL");

		Assertions.assertEquals(1, h.pane.getTabCount(), "only the newly opened Script is present");
		Assertions.assertEquals(1, h.session.tabCount());
		Assertions.assertFalse(reopened.isDirty());
		Assertions.assertEquals("1", h.session.activeAssetId());
		Assertions.assertEquals(0, h.closer.protectDirtyTabsAtExit());
	}
}
