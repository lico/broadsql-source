package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTree;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptAsset;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryService;

/**
 * SPRINT 3009A (#186, #191): the Editor's "Scripts" pane. The library root is not shown (its content is the top
 * level); every folder lists folders first, then Scripts, alphabetically without regard to case, whatever order the
 * disk lists them in, and keeps that order through every change; folders and Scripts have distinct icons; the
 * context menu belongs to the item it was opened on; and drops that can never be a valid move are refused.
 */
class TestScriptsPane {

	private static ScriptLibraryService serviceFor(Path lib, Path vault) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setScriptsLibraryPath(lib.toString());
		settings.setScriptHistoryVaultRaw(vault.toString());
		return new ScriptLibraryService(settings);
	}

	/** The screenshot's library, created in a deliberately scrambled order. */
	private static void sampleLibrary(Path lib) throws IOException {
		for (String file : List.of("reports/QR10.sql", "myscript10.sql", "queries/myquery2.sql", "reports/QR1.sql", "queries/mysql100.sql",
				"queries/myquery.sql", "reports/qr11.sql")) {
			Files.createDirectories(lib.resolve(file).getParent());
			Files.writeString(lib.resolve(file), "select 1;");
		}
		Files.createDirectories(lib.resolve("maintenance"));
		Files.createDirectories(lib.resolve("Archive"));
	}

	@Test
	void theLibraryRootIsHiddenAndItsContentIsTheTopLevel(@TempDir Path lib, @TempDir Path vault) throws IOException {
		sampleLibrary(lib);
		ScriptLibraryBrowserPanel panel = new ScriptLibraryBrowserPanel(serviceFor(lib, vault));
		JTree tree = panel.tree();

		Assertions.assertFalse(tree.isRootVisible(), "no artificial 'Scripts Library' node");
		Assertions.assertTrue(tree.getShowsRootHandles(), "top-level folders still show their expand handle");
		Assertions.assertEquals("Archive", tree.getPathForRow(0).getLastPathComponent().toString(), "the first row is the first top-level item");
	}

	@Test
	void foldersComeFirstThenScriptsAlphabeticallyWithoutRegardToCase(@TempDir Path lib, @TempDir Path vault) throws IOException {
		sampleLibrary(lib);
		ScriptLibraryBrowserPanel panel = new ScriptLibraryBrowserPanel(serviceFor(lib, vault));

		Assertions.assertEquals(List.of("Archive/", "maintenance/", "queries/", "reports/", "myscript10.sql"), panel.shownChildren(null));
		Assertions.assertEquals(List.of("myquery.sql", "myquery2.sql", "mysql100.sql"), panel.shownChildren("queries"));
		Assertions.assertEquals(List.of("QR1.sql", "QR10.sql", "qr11.sql"), panel.shownChildren("reports"));
	}

	@Test
	void theOrderIsDeterministicEvenForNamesDifferingOnlyByCase() {
		javax.swing.tree.DefaultMutableTreeNode a = new javax.swing.tree.DefaultMutableTreeNode("a.sql");
		javax.swing.tree.DefaultMutableTreeNode bigA = new javax.swing.tree.DefaultMutableTreeNode("A.sql");
		List<javax.swing.tree.DefaultMutableTreeNode> one = new ArrayList<>(List.of(a, bigA));
		List<javax.swing.tree.DefaultMutableTreeNode> other = new ArrayList<>(List.of(bigA, a));
		one.sort(ScriptLibraryBrowserPanel.DISPLAY_ORDER);
		other.sort(ScriptLibraryBrowserPanel.DISPLAY_ORDER);
		Assertions.assertEquals(one, other);
	}

	@Test
	void theOrderSurvivesCreateRenameDuplicateMoveAndDelete(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		sampleLibrary(lib);
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptLibraryBrowserPanel panel = new ScriptLibraryBrowserPanel(service);

		service.create("reports/QR0", "select 1;");
		service.createFolder("reports/2026");
		ScriptAsset qr1 = service.open("reports/QR1.sql");
		service.rename(qr1.assetId(), "reports/QR1.sql", "reports/AA.sql", null);
		service.duplicate("reports/QR10.sql", "reports/zz.sql");
		service.moveAsset("myscript10.sql", "reports");
		service.delete("reports/qr11.sql");
		panel.reload();

		Assertions.assertEquals(List.of("2026/", "AA.sql", "myscript10.sql", "QR0.bsql", "QR10.sql", "zz.sql"), panel.shownChildren("reports"));
	}

	@Test
	void foldersShowClosedOrOpenFolderIconsAndScriptsADocumentIcon(@TempDir Path lib, @TempDir Path vault) throws IOException {
		sampleLibrary(lib);
		ScriptLibraryBrowserPanel panel = new ScriptLibraryBrowserPanel(serviceFor(lib, vault));
		JTree tree = panel.tree();

		Assertions.assertEquals(EditorIcons.Kind.FOLDER_OPEN, panel.iconFor("queries", true), "expanded on first load");
		tree.collapseRow(tree.getRowForPath(pathOf(tree, "queries")));
		Assertions.assertEquals(EditorIcons.Kind.FOLDER_CLOSED, panel.iconFor("queries", true));
		Assertions.assertEquals(EditorIcons.Kind.FOLDER_CLOSED, panel.iconFor("maintenance", true), "an empty folder is a folder, not a file");
		Assertions.assertEquals(EditorIcons.Kind.SCRIPT, panel.iconFor("reports/QR1.sql", false));
	}

	private static javax.swing.tree.TreePath pathOf(JTree tree, String label) {
		for (int i = 0; i < tree.getRowCount(); i++) {
			if (tree.getPathForRow(i).getLastPathComponent().toString().equals(label)) {
				return tree.getPathForRow(i);
			}
		}
		throw new AssertionError(label);
	}

	@Test
	void thePaneIsTitledScriptsWithTheSearchFieldAboveTheTreeAndNoButtonRow(@TempDir Path lib, @TempDir Path vault) {
		ScriptLibraryBrowserPanel panel = new ScriptLibraryBrowserPanel(serviceFor(lib, vault));

		List<String> labels = new ArrayList<>();
		List<JButton> buttons = new ArrayList<>();
		collect(panel, labels, buttons);
		Assertions.assertTrue(labels.contains("Scripts"), labels.toString());
		Assertions.assertFalse(labels.contains("Scripts Library"), labels.toString());
		Assertions.assertTrue(buttons.isEmpty(), "New, New Folder and Open moved to the toolbar, menus and context menu");
	}

	private static void collect(Container container, List<String> labels, List<JButton> buttons) {
		for (Component c : container.getComponents()) {
			if (c instanceof JLabel l) {
				labels.add(l.getText());
			}
			if (c instanceof JButton b) {
				buttons.add(b);
			}
			// FlatLaf's own parts (the search field's clear button, scroll bar arrows) are not action buttons.
			if (c instanceof Container inner && !(c instanceof JTree) && !(c instanceof javax.swing.JTextField) && !(c instanceof javax.swing.JScrollBar)) {
				collect(inner, labels, buttons);
			}
		}
	}

	@Test
	void theContextMenuMatchesTheItemItWasOpenedOn(@TempDir Path lib, @TempDir Path vault) throws IOException {
		sampleLibrary(lib);
		ScriptLibraryBrowserPanel panel = new ScriptLibraryBrowserPanel(serviceFor(lib, vault));

		Assertions.assertEquals(Arrays.asList("Open", null, "New Script", "New Folder...", null, "Rename...", "Duplicate...", "Delete...", null,
				"Copy Library Path", "Copy Full Path", "Copy CLI Command", "Send to CLI", null, "Refresh"), panel.contextMenuLabels("reports/QR1.sql", false));
		Assertions.assertEquals(Arrays.asList("New Script", "New Folder...", null, "Rename...", "Delete...", null, "Copy Library Path", "Copy Full Path", null,
				"Refresh"), panel.contextMenuLabels("reports", true));
		Assertions.assertEquals(Arrays.asList("New Script", "New Folder...", null, "Refresh"), panel.contextMenuLabels(null, true));
	}

	@Test
	void newFolderFromAFolderContextMenuCreatesInsideThatFolder(@TempDir Path lib, @TempDir Path vault) throws IOException {
		sampleLibrary(lib);
		ScriptLibraryBrowserPanel panel = new ScriptLibraryBrowserPanel(serviceFor(lib, vault));
		List<String> calls = new ArrayList<>();
		panel.setListener(new RecordingListener(calls));

		for (Component c : panel.contextMenuFor(null).getComponents()) {
			if (c instanceof javax.swing.JMenuItem item && item.getText().equals("New Folder...")) {
				item.doClick();
			}
		}
		panel.selectFolder("reports");
		Assertions.assertTrue(panel.openSelected() == false, "a folder is not opened");
		Assertions.assertEquals(List.of("newFolder:null"), calls);
	}

	@Test
	void openSelectedOpensTheSelectedScript(@TempDir Path lib, @TempDir Path vault) throws IOException {
		sampleLibrary(lib);
		ScriptLibraryBrowserPanel panel = new ScriptLibraryBrowserPanel(serviceFor(lib, vault));
		List<String> calls = new ArrayList<>();
		panel.setListener(new RecordingListener(calls));

		panel.selectAsset("reports/QR10.sql");
		Assertions.assertTrue(panel.openSelected());
		Assertions.assertEquals(List.of("open:reports/QR10.sql"), calls);
	}

	@Test
	void theSelectionSurvivesAReload(@TempDir Path lib, @TempDir Path vault) throws IOException {
		sampleLibrary(lib);
		ScriptLibraryBrowserPanel panel = new ScriptLibraryBrowserPanel(serviceFor(lib, vault));
		panel.selectAsset("queries/myquery2.sql");

		panel.reload();

		Assertions.assertEquals("queries/myquery2.sql", panel.selectedAssetPath());
	}

	@Test
	void dropsThatCanNeverBeValidMovesAreRefused() {
		ScriptLibraryBrowserPanel.MovedItem folder = new ScriptLibraryBrowserPanel.MovedItem("reports", true);
		ScriptLibraryBrowserPanel.MovedItem script = new ScriptLibraryBrowserPanel.MovedItem("reports/QR1.sql", false);

		Assertions.assertNotNull(ScriptLibraryBrowserPanel.moveRefusal(folder, "reports"), "into itself");
		Assertions.assertNotNull(ScriptLibraryBrowserPanel.moveRefusal(folder, "reports/2026"), "into a subfolder");
		Assertions.assertNotNull(ScriptLibraryBrowserPanel.moveRefusal(folder, "Reports/2026/q1"), "into a deeper subfolder, compared without case");
		Assertions.assertNotNull(ScriptLibraryBrowserPanel.moveRefusal(folder, null), "already at the top level");
		Assertions.assertNotNull(ScriptLibraryBrowserPanel.moveRefusal(script, "reports"), "already in that folder");

		Assertions.assertNull(ScriptLibraryBrowserPanel.moveRefusal(folder, "queries"));
		Assertions.assertNull(ScriptLibraryBrowserPanel.moveRefusal(folder, "reports2"), "a sibling whose name starts the same is not a subfolder");
		Assertions.assertNull(ScriptLibraryBrowserPanel.moveRefusal(script, null));
		Assertions.assertNull(ScriptLibraryBrowserPanel.moveRefusal(script, "queries"));
	}

	@Test
	void theTreeAcceptsOnlyItsOwnItemsForMoves(@TempDir Path lib, @TempDir Path vault) {
		ScriptLibraryBrowserPanel panel = new ScriptLibraryBrowserPanel(serviceFor(lib, vault));
		JTree tree = panel.tree();
		Assertions.assertEquals(!java.awt.GraphicsEnvironment.isHeadless(), tree.getDragEnabled(), "dragging needs a display; the gesture itself is a manual check");
		Assertions.assertEquals(javax.swing.DropMode.ON, tree.getDropMode());
		Assertions.assertEquals(javax.swing.TransferHandler.MOVE, tree.getTransferHandler().getSourceActions(tree));
		java.awt.datatransfer.Transferable outsideFiles = new java.awt.datatransfer.Transferable() {
			@Override
			public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
				return new java.awt.datatransfer.DataFlavor[] { java.awt.datatransfer.DataFlavor.javaFileListFlavor };
			}

			@Override
			public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
				return java.awt.datatransfer.DataFlavor.javaFileListFlavor.equals(flavor);
			}

			@Override
			public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) {
				return List.of(lib.toFile());
			}
		};
		Assertions.assertFalse(tree.getTransferHandler().canImport(new javax.swing.TransferHandler.TransferSupport(tree, outsideFiles)),
				"files from outside the tree are not imported (and a paste is not a move)");
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
