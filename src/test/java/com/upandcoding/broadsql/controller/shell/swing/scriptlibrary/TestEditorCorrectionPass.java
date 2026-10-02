package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JMenuItem;
import javax.swing.JTree;
import javax.swing.tree.TreeSelectionModel;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.MetadataIntegrityContext;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptAsset;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryService;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibrarySession;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader;
import com.upandcoding.broadsql.controller.shell.swing.api.UnsavedChangesDialog;

/**
 * SPRINT 3009A correction pass, Swing side, on the real components the Editor window assembles (the window itself
 * cannot be built headless): new unsaved Scripts and startup, the tree following the active tab, the New Folder
 * destination rule, multiple selection, Run's enabled state, the first Save of a new Script, Metadata synchronized
 * on Save and never destroying text, and dirty open Scripts in a batch move. Each wiring copies the frame's exactly.
 */
class TestEditorCorrectionPass {

	private static ScriptLibraryService serviceFor(Path lib, Path vault) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setScriptsLibraryPath(lib.toString());
		settings.setScriptHistoryVaultRaw(vault.toString());
		return new ScriptLibraryService(settings);
	}

	private static void files(Path lib, String... paths) throws IOException {
		for (String path : paths) {
			Files.createDirectories(lib.resolve(path).getParent());
			Files.writeString(lib.resolve(path), "select 1;");
		}
	}

	/** The frame's wiring: a tab change makes the tree mirror the active tab. */
	private static ScriptEditorTabbedPane wiredPane(ScriptLibraryBrowserPanel tree) {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		pane.addChangeListener(e -> tree.mirror(pane.activeTab()));
		return pane;
	}

	// ---- A. Startup and new Scripts ----

	@Test
	void aNewScriptIsOneUnsavedCleanTabReadyToType() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();

		ScriptEditorTab tab = pane.newUnsavedScript(ScriptLibraryService.newAssetSeedContent(), null);

		Assertions.assertEquals(1, pane.getTabCount());
		Assertions.assertSame(tab, pane.activeTab());
		Assertions.assertTrue(tab.asset().isUnsaved());
		Assertions.assertNull(tab.asset().relativePath(), "no path until its first Save");
		Assertions.assertFalse(tab.isDirty(), "closing it untouched asks nothing");
		Assertions.assertEquals("New Script 1", pane.getTitleAt(0));
		Assertions.assertEquals(ScriptEditorTabbedPane.UNSAVED_TOOLTIP, pane.getToolTipTextAt(0));
		Assertions.assertEquals("draft", ScriptMetadataHeader.parse(tab.currentText()).metadata().getStatus(), "the same seed as every new Script");
	}

	@Test
	void startingTheEditorTwiceReusesTheUntouchedNewScriptAndNeverDuplicatesIt() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		// openWorkspaceWithNewScript: reuse the untouched new Script, otherwise create one.
		Runnable startup = () -> {
			if (pane.untouchedUnsavedScript() == null) {
				pane.newUnsavedScript(ScriptLibraryService.newAssetSeedContent(), null);
			}
		};
		startup.run();
		startup.run();
		Assertions.assertEquals(1, pane.getTabCount());

		pane.activeTab().textArea().append("select 1;");
		Assertions.assertNull(pane.untouchedUnsavedScript(), "once typed in, it is the user's work");
		startup.run();
		Assertions.assertEquals(2, pane.getTabCount());
		Assertions.assertEquals("New Script 2", pane.getTitleAt(1));
	}

	@Test
	void aNamedScriptOpensAloneAndIsSelectedInTheTree(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		files(lib, "reports/toto.sql", "a.sql");
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptLibraryBrowserPanel tree = new ScriptLibraryBrowserPanel(service);
		tree.selectAsset("a.sql"); // an unrelated earlier selection
		ScriptEditorTabbedPane pane = wiredPane(tree);

		pane.openOrFocus(service.open("reports/toto.sql"));

		Assertions.assertEquals(1, pane.getTabCount(), "no extra blank tab");
		Assertions.assertEquals(List.of("reports/toto.sql"), tree.selectedPaths());
	}

	@Test
	void theFirstSaveNamesTheNewScriptAndTheTabBecomesThatFile(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptLibrarySession session = new ScriptLibrarySession();
		ScriptEditorTab tab = pane.newUnsavedScript(ScriptLibraryService.newAssetSeedContent(), "reports");
		session.openOrFocus(tab.asset().assetId(), null);
		tab.textArea().append("select 42;\n");
		String unsavedId = tab.asset().assetId();
		Assertions.assertEquals("reports", tab.suggestedFolder());

		// saveNewScript: create with the buffer (metadata checked), then adopt.
		ScriptAsset created = service.create("reports/answer", tab.currentText(), MetadataIntegrityContext.unchecked());
		pane.adoptSaved(tab, created);
		session.closeTab(unsavedId);
		session.openOrFocus(created.assetId(), created.relativePath());

		Assertions.assertEquals("reports/answer.bsql", tab.asset().relativePath());
		Assertions.assertEquals("-- @status: draft\nselect 42;\n", Files.readString(lib.resolve("reports/answer.bsql")));
		Assertions.assertFalse(tab.isDirty());
		Assertions.assertEquals("answer.bsql", pane.getTitleAt(0));
		Assertions.assertEquals("reports/answer.bsql", pane.getToolTipTextAt(0));
		Assertions.assertSame(tab, pane.tabForAsset(created.assetId()));
		Assertions.assertNull(pane.tabForAsset(unsavedId));
	}

	@Test
	void closingADirtyNewScriptWithSaveClosesItUnderItsNewIdentity(@TempDir Path lib, @TempDir Path vault) {
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptLibrarySession session = new ScriptLibrarySession();
		ScriptTabCloser closer = new ScriptTabCloser(pane, session, t -> UnsavedChangesDialog.Decision.SAVE, t -> {
			try {
				pane.adoptSaved(t, service.create("saved.sql", t.currentText()));
				return true;
			} catch (BroadSQLException e) {
				return false;
			}
		});
		ScriptEditorTab tab = pane.newUnsavedScript("select 1;", null);
		session.openOrFocus(tab.asset().assetId(), null);
		tab.textArea().append("\n-- edited");

		Assertions.assertTrue(closer.close(tab));
		Assertions.assertEquals(0, pane.getTabCount(), "the tab closes even though Save changed its asset id");
		Assertions.assertTrue(Files.exists(lib.resolve("saved.sql")));
	}

	// ---- B. The tree follows the active tab ----

	@Test
	void switchingTabsMovesTheTreeSelectionAndRevealsNestedScripts(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		files(lib, "a.sql", "reports/2026/deep.sql");
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptLibraryBrowserPanel tree = new ScriptLibraryBrowserPanel(service);
		ScriptEditorTabbedPane pane = wiredPane(tree);
		ScriptEditorTab a = pane.openOrFocus(service.open("a.sql"));
		ScriptEditorTab deep = pane.openOrFocus(service.open("reports/2026/deep.sql"));
		JTree jtree = tree.tree();
		collapseAll(jtree); // so revealing must expand the parents again

		pane.setSelectedComponent(a);
		Assertions.assertEquals(List.of("a.sql"), tree.selectedPaths());
		pane.setSelectedComponent(deep);
		Assertions.assertEquals(List.of("reports/2026/deep.sql"), tree.selectedPaths());
		Assertions.assertTrue(jtree.isVisible(jtree.getSelectionPath()), "its folders were expanded");
	}

	private static boolean collapseAll(JTree tree) {
		boolean collapsed = false;
		for (int i = tree.getRowCount() - 1; i >= 0; i--) {
			if (tree.isExpanded(i)) {
				tree.collapseRow(i);
				collapsed = true;
			}
		}
		return collapsed;
	}

	@Test
	void aNewUnsavedScriptClearsTheSelectionInsteadOfShowingAnUnrelatedScript(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		files(lib, "a.sql");
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptLibraryBrowserPanel tree = new ScriptLibraryBrowserPanel(service);
		ScriptEditorTabbedPane pane = wiredPane(tree);
		ScriptEditorTab a = pane.openOrFocus(service.open("a.sql"));

		pane.newUnsavedScript("select 1;", null);
		Assertions.assertEquals(List.of(), tree.selectedPaths());

		pane.setSelectedComponent(a);
		Assertions.assertEquals(List.of("a.sql"), tree.selectedPaths());
	}

	@Test
	void afterDuplicateRenameAndMoveTheTreeShowsTheActiveScriptsNewNode(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		files(lib, "a.sql");
		Files.createDirectories(lib.resolve("archive"));
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptLibraryBrowserPanel tree = new ScriptLibraryBrowserPanel(service);
		ScriptEditorTabbedPane pane = wiredPane(tree);
		pane.openOrFocus(service.open("a.sql"));

		// Duplicate: the duplicate opens and becomes active; the frame then reloads the tree and mirrors again.
		ScriptAsset b = service.duplicate("a.sql", "b.sql");
		pane.openOrFocus(b);
		tree.reload();
		tree.mirror(pane.activeTab());
		Assertions.assertEquals(List.of("b.sql"), tree.selectedPaths(), "the duplicate, not the original");

		// Rename of the active Script.
		ScriptAsset renamed = service.rename(b.assetId(), "b.sql", "c.sql", null);
		pane.retargetOpenTab(renamed);
		tree.reload();
		tree.mirror(pane.activeTab());
		Assertions.assertEquals(List.of("c.sql"), tree.selectedPaths());

		// Move of the active Script.
		ScriptAsset moved = service.moveAsset("c.sql", "archive");
		pane.retargetOpenTab(moved);
		tree.reload();
		tree.mirror(pane.activeTab());
		Assertions.assertEquals(List.of("archive/c.sql"), tree.selectedPaths());
	}

	@Test
	void selectingInTheTreeNeverOpensOrSwitchesAnything(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		files(lib, "a.sql", "b.sql");
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptLibraryBrowserPanel tree = new ScriptLibraryBrowserPanel(service);
		List<String> opened = new ArrayList<>();
		tree.setListener(new RecordingListener(opened));
		ScriptEditorTabbedPane pane = wiredPane(tree);
		pane.openOrFocus(service.open("a.sql"));

		tree.selectAsset("b.sql");

		Assertions.assertEquals(List.of(), opened, "no feedback loop: selection is only a reflection");
		Assertions.assertEquals("a.sql", pane.activeTab().asset().relativePath());
	}

	// ---- C. New Folder destination ----

	@Test
	void newFolderTargetsTheSelectedFolderOrTheSelectedScriptsFolderOrTheRoot(@TempDir Path lib, @TempDir Path vault) throws IOException {
		files(lib, "top.sql", "reports/a.sql", "reports/2026/q1.sql");
		ScriptLibraryBrowserPanel tree = new ScriptLibraryBrowserPanel(serviceFor(lib, vault));

		tree.selectFolder("reports");
		Assertions.assertEquals("reports", tree.targetFolderForNew(), "root-level folder: inside it");
		tree.selectFolder("reports/2026");
		Assertions.assertEquals("reports/2026", tree.targetFolderForNew(), "nested folder: inside it");
		tree.selectAsset("top.sql");
		Assertions.assertNull(tree.targetFolderForNew(), "root-level Script: beside it, at the root");
		tree.selectAsset("reports/2026/q1.sql");
		Assertions.assertEquals("reports/2026", tree.targetFolderForNew(), "nested Script: beside it, never under the file");
		tree.clearSelection();
		Assertions.assertNull(tree.targetFolderForNew(), "nothing selected: the root");
	}

	@Test
	void theContextMenuUsesTheSameDestinationAsTheToolbarAndFileMenu(@TempDir Path lib, @TempDir Path vault) throws IOException {
		files(lib, "top.sql", "reports/a.sql");
		ScriptLibraryBrowserPanel tree = new ScriptLibraryBrowserPanel(serviceFor(lib, vault));
		List<String> calls = new ArrayList<>();
		tree.setListener(new RecordingListener(calls));

		for (Object[] selection : new Object[][] { { "reports", true }, { "reports/a.sql", false }, { "top.sql", false } }) {
			calls.clear();
			if ((Boolean) selection[1]) {
				tree.selectFolder((String) selection[0]);
			} else {
				tree.selectAsset((String) selection[0]);
			}
			click(tree, "New Folder...", (String) selection[0], (Boolean) selection[1]);
			// The toolbar and File > New Folder call newFolder(tree.targetFolderForNew()).
			Assertions.assertEquals(List.of("newFolder:" + tree.targetFolderForNew()), calls, selection[0].toString());
		}
	}

	private static void click(ScriptLibraryBrowserPanel tree, String label, String path, boolean folder) {
		for (Component c : tree.contextMenuFor(itemAt(tree, path, folder)).getComponents()) {
			if (c instanceof JMenuItem item && item.getText().equals(label)) {
				item.doClick();
				return;
			}
		}
		Assertions.fail(label);
	}

	/** The user object of the shown node for {@code path} (through the selection, which the test just set). */
	private static Object itemAt(ScriptLibraryBrowserPanel tree, String path, boolean folder) {
		Object last = tree.tree().getSelectionPath().getLastPathComponent();
		return ((javax.swing.tree.DefaultMutableTreeNode) last).getUserObject();
	}

	// ---- D. Multiple selection ----

	@Test
	void theTreeAllowsAdditiveSelectionAndNormalizesWhatADropMoves(@TempDir Path lib, @TempDir Path vault) throws IOException {
		files(lib, "a.sql", "b.sql", "reports/january.sql", "reports/february.sql");
		ScriptLibraryBrowserPanel tree = new ScriptLibraryBrowserPanel(serviceFor(lib, vault));
		Assertions.assertEquals(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION, tree.tree().getSelectionModel().getSelectionMode());

		tree.selectPaths(List.of("a.sql", "b.sql", "reports", "reports/january.sql"));
		Assertions.assertEquals(List.of("reports", "reports/january.sql", "a.sql", "b.sql"), tree.selectedPaths(), "tree order");

		List<ScriptLibraryBrowserPanel.MovedItem> selection = List.of(new ScriptLibraryBrowserPanel.MovedItem("reports", true),
				new ScriptLibraryBrowserPanel.MovedItem("reports/january.sql", false), new ScriptLibraryBrowserPanel.MovedItem("a.sql", false));
		Assertions.assertEquals(List.of("reports", "a.sql"), ScriptLibraryBrowserPanel.normalize(selection).stream().map(ScriptLibraryBrowserPanel.MovedItem::path).toList());
	}

	@Test
	void aDropIsRefusedOnlyWhenItCanNeverBeValid() {
		var a = new ScriptLibraryBrowserPanel.MovedItem("a.sql", false);
		var reports = new ScriptLibraryBrowserPanel.MovedItem("reports", true);
		var archived = new ScriptLibraryBrowserPanel.MovedItem("archive/old.sql", false);

		Assertions.assertNull(ScriptLibraryBrowserPanel.moveRefusal(List.of(a, reports), "archive"));
		Assertions.assertNotNull(ScriptLibraryBrowserPanel.moveRefusal(List.of(a, reports), "reports/2026"), "one folder into its own subfolder refuses the whole drop");
		Assertions.assertNull(ScriptLibraryBrowserPanel.moveRefusal(List.of(a, archived), "archive"), "an item already there is simply skipped");
		Assertions.assertNotNull(ScriptLibraryBrowserPanel.moveRefusal(List.of(archived), "archive"), "nothing to move");
	}

	@Test
	void dirtyOpenScriptsMovedTogetherKeepTheirEditsAndSaveToTheirNewPaths(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		files(lib, "a.sql", "reports/r.sql", "other.sql");
		Files.createDirectories(lib.resolve("archive"));
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptEditorTab a = pane.openOrFocus(service.open("a.sql"));
		ScriptEditorTab r = pane.openOrFocus(service.open("reports/r.sql"));
		a.textArea().setText("select 'a edited';");
		r.textArea().setText("select 'r edited';");

		ScriptLibraryService.MoveOutcome outcome = service.moveItems(
				List.of(new ScriptLibraryService.MoveRequest("a.sql", false), new ScriptLibraryService.MoveRequest("reports", true)), "archive");
		for (ScriptAsset moved : outcome.movedScripts()) {
			pane.retargetOpenTab(moved);
		}

		Assertions.assertEquals("archive/a.sql", a.asset().relativePath());
		Assertions.assertEquals("archive/reports/r.sql", r.asset().relativePath());
		Assertions.assertTrue(a.isDirty() && r.isDirty(), "unsaved edits survive");
		for (ScriptEditorTab tab : List.of(a, r)) {
			service.save(tab.asset().assetId(), tab.asset().relativePath(), tab.currentText(), null);
		}
		Assertions.assertEquals("select 'a edited';", Files.readString(lib.resolve("archive/a.sql")));
		Assertions.assertEquals("select 'r edited';", Files.readString(lib.resolve("archive/reports/r.sql")));
		Assertions.assertFalse(Files.exists(lib.resolve("a.sql")), "old paths are never recreated");
		Assertions.assertFalse(Files.exists(lib.resolve("reports")));
	}

	// ---- E/F. Metadata ----

	@Test
	void metadataTypedInTheEditorShowsInTheMetadataTabAfterSaveAndTheTabStaysClean(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		Files.writeString(lib.resolve("s.sql"), "-- @status: draft\n-- @tags: a\nselect 1;\n");
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptEditorTab tab = new ScriptEditorTabbedPane().openOrFocus(service.open("s.sql"));
		MetadataPanel panel = new MetadataPanel();
		List<MetadataPanel.Change> commits = new ArrayList<>();
		panel.setCommitListener(commits::add);
		panel.load(ScriptMetadataHeader.parse(tab.currentText()));

		String text = tab.currentText().replace("@status: draft", "@status: stable").replace("@tags: a", "@tags: a,b,c");
		tab.textArea().setText(text);
		// Save, then reloadMetadataAfterSave: Metadata reloads from the saved text.
		panel.commitPendingEdits();
		service.save(tab.asset().assetId(), "s.sql", tab.currentText(), null);
		tab.markSaved(service.open("s.sql"));
		panel.load(ScriptMetadataHeader.parse(tab.currentText()));

		Assertions.assertEquals("stable", panel.fieldText("status"));
		Assertions.assertEquals("a,b,c", panel.fieldText("tags").replace(" ", ""));
		Assertions.assertTrue(commits.isEmpty(), "reloading never writes back");
		panel.commitPendingEdits();
		Assertions.assertTrue(commits.isEmpty());
		Assertions.assertFalse(tab.isDirty(), "edit, save, parse, refresh: the document converges clean");
	}

	@Test
	void aPaneEditAfterTypingCommentsInTheHeaderKeepsThoseComments() {
		String original = "-- @instance: MyWorld\n-- @tags: toto\n\nselect 1;\n";
		ScriptEditorTab tab = new ScriptEditorTab(new ScriptAsset("id", "s.sql", ScriptMetadataHeader.parse(original), original, 0L));
		MetadataPanel panel = new MetadataPanel();
		panel.setCommitListener(change -> ScriptLibraryFrame.replaceChangedRegion(tab.textArea(), change.applyTo(tab.currentText())));
		panel.load(ScriptMetadataHeader.parse(tab.currentText()));

		// After the pane was loaded, the user types comments into the header in the editor.
		tab.textArea().insert("/*\n Hello World\n*/\n", 0);
		tab.textArea().insert("-- toto\n/* my comments */\n", tab.currentText().indexOf("-- @tags"));
		String typed = tab.currentText();

		((javax.swing.text.JTextComponent) panel.field("tags")).setText("toto,popo");
		panel.commitPendingEdits();

		Assertions.assertEquals(typed.replace("-- @tags: toto\n", "-- @tags: toto,popo\n"), tab.currentText(),
				"only the tags line changed; the comments typed after the pane loaded are all still there");
	}

	@Test
	void replacingOnlyTheChangedRegionKeepsTheRestOfTheDocument() {
		javax.swing.JTextArea area = new javax.swing.JTextArea("-- @tags: a\nselect 1;\n");
		List<String> edits = new ArrayList<>();
		area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e) {
				edits.add("insert@" + e.getOffset() + "+" + e.getLength());
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e) {
				edits.add("remove@" + e.getOffset() + "+" + e.getLength());
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e) {
			}
		});
		ScriptLibraryFrame.replaceChangedRegion(area, "-- @tags: a,b\nselect 1;\n");
		Assertions.assertEquals("-- @tags: a,b\nselect 1;\n", area.getText());
		Assertions.assertEquals(List.of("insert@11+2"), edits, "only the two new characters are inserted; nothing else is replaced");
	}

	// ---- H. Run's enabled state ----

	@Test
	void runIsEnabledForAnyActiveScriptCleanOrDirtyAndOnlyWithAConnection() {
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptEditorTab clean = pane.openOrFocus(new ScriptAsset("id", "s.sql", ScriptMetadataHeader.parse("select 1;"), "select 1;", 0L));

		Assertions.assertFalse(EditorActions.runEnabled(null, true, false), "no active Script");
		Assertions.assertTrue(EditorActions.runEnabled(clean, true, false), "a clean Script runs");
		clean.textArea().append(" ");
		Assertions.assertTrue(EditorActions.runEnabled(clean, true, false), "a dirty Script runs too (Save and Run)");
		Assertions.assertFalse(EditorActions.runEnabled(clean, false, false), "no connection");
		Assertions.assertFalse(EditorActions.runEnabled(clean, true, true), "a run in progress");
	}

	@Test
	void theToolbarNoLongerOffersValidateOrRefresh() {
		List<String> names = new ArrayList<>();
		for (EditorActions.Command command : EditorActions.Command.values()) {
			names.add(command.label);
		}
		Assertions.assertFalse(names.contains("Validate"));
		Assertions.assertFalse(names.contains("Refresh"));
	}

	private static final class RecordingListener implements ScriptLibraryBrowserListener {
		private final List<String> calls;

		RecordingListener(List<String> calls) {
			this.calls = calls;
		}

		@Override
		public void onAssetOpenRequested(String relativePath) {
			calls.add("open:" + relativePath);
		}

		@Override
		public void onNewRequested(String folderOrNull) {
			calls.add("new:" + folderOrNull);
		}

		@Override
		public void onNewFolderRequested(String parentFolderOrNull) {
			calls.add("newFolder:" + parentFolderOrNull);
		}

		@Override
		public void onRenameRequested(String relativePath) {
		}

		@Override
		public void onDuplicateRequested(String relativePath) {
		}

		@Override
		public void onDeleteRequested(String relativePath) {
		}

		@Override
		public void onRenameFolderRequested(String folderPath) {
		}

		@Override
		public void onDeleteFolderRequested(String folderPath) {
		}

		@Override
		public void onRefreshRequested() {
		}
	}
}
