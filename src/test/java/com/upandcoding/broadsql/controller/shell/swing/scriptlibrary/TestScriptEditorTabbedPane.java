package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptAsset;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader;

/**
 * {@link ScriptEditorTab}/{@link ScriptEditorTabbedPane} are {@link javax.swing.JPanel}/
 * {@link javax.swing.JTabbedPane}-based, headlessly constructible - no {@link javax.swing.JFrame}
 * involved - same precedent as {@link TestScriptLibraryBrowserPanel}.
 */
class TestScriptEditorTabbedPane {

	private ScriptAsset asset(String assetId, String relativePath, String content) {
		return new ScriptAsset(assetId, relativePath, ScriptMetadataHeader.parse(content), content, 0L);
	}

	@Test
	void openOrFocusReusesTheExistingTabForTheSameAsset() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptAsset asset = asset("asset-1", "customer.sql", "select 1;");

		ScriptEditorTab first = pane.openOrFocus(asset);
		ScriptEditorTab second = pane.openOrFocus(asset);

		Assertions.assertSame(first, second);
		Assertions.assertEquals(1, pane.getTabCount());
	}

	@Test
	void distinctAssetsGetDistinctTabs() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		pane.openOrFocus(asset("asset-1", "a.sql", "x"));
		pane.openOrFocus(asset("asset-2", "b.sql", "y"));

		Assertions.assertEquals(2, pane.getTabCount());
	}

	@Test
	void closingATabRemovesItFromThePane() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		pane.openOrFocus(asset("asset-1", "a.sql", "x"));

		pane.closeTab("asset-1");

		Assertions.assertEquals(0, pane.getTabCount());
		Assertions.assertNull(pane.tabForAsset("asset-1"));
	}

	@Test
	void editingTheBufferMarksTheTabDirtyAndUpdatesTheTabTitle() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptAsset asset = asset("asset-1", "customer.sql", "select 1;");
		ScriptEditorTab tab = pane.openOrFocus(asset);

		Assertions.assertEquals("customer.sql", pane.getTitleAt(0));
		Assertions.assertFalse(tab.isDirty());

		tab.textArea().setText("select 2;");

		Assertions.assertTrue(tab.isDirty());
		Assertions.assertEquals("customer.sql *", pane.getTitleAt(0));
	}

	@Test
	void markSavedClearsDirtyAndUpdatesTheTrackedAsset() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptAsset asset = asset("asset-1", "customer.sql", "select 1;");
		ScriptEditorTab tab = pane.openOrFocus(asset);
		tab.textArea().setText("select 2;");
		Assertions.assertTrue(tab.isDirty());

		ScriptAsset saved = asset("asset-1", "customer.sql", "select 2;");
		tab.markSaved(saved);

		Assertions.assertFalse(tab.isDirty());
		Assertions.assertEquals("select 2;", tab.asset().content());
	}

	@Test
	void retargetUpdatesIdentityWithoutTouchingTheDirtyBufferOrDirtyFlag() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptAsset asset = asset("asset-1", "old-name.sql", "select 1;");
		ScriptEditorTab tab = pane.openOrFocus(asset);
		tab.textArea().setText("unsaved edit");
		Assertions.assertTrue(tab.isDirty());

		ScriptAsset renamed = asset("asset-1", "new-name.sql", "select 1;");
		tab.retarget(renamed);

		Assertions.assertTrue(tab.isDirty(), "a rename must never silently clear an unsaved buffer's dirty flag");
		Assertions.assertEquals("unsaved edit", tab.currentText(), "a rename must never touch the editor buffer");
		Assertions.assertEquals("new-name.sql", tab.asset().relativePath());
	}

	@Test
	void undoRedoRoundTripsThroughTheStandardSwingUndoManager() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptAsset asset = asset("asset-1", "customer.sql", "select 1;");
		ScriptEditorTab tab = pane.openOrFocus(asset);

		Assertions.assertFalse(tab.canUndo());
		tab.textArea().append(" -- comment");
		Assertions.assertTrue(tab.canUndo());

		tab.undo();
		Assertions.assertEquals("select 1;", tab.currentText());
		Assertions.assertTrue(tab.canRedo());

		tab.redo();
		Assertions.assertEquals("select 1; -- comment", tab.currentText());
	}
}
