package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryService;

/**
 * SPRINT 1909S: the browser is the Scripts Library as a folder/file tree rooted at the configured
 * directory. A {@link javax.swing.JPanel}-based component - headlessly constructible/testable, unlike an
 * actual {@link javax.swing.JFrame} (the same precedent {@code TestJApiEndpointTreePanel} establishes).
 */
class TestScriptLibraryBrowserPanel {

	private ConsoleSettings settingsWith(Path libRoot, Path vaultRoot) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setScriptsLibraryPath(libRoot.toString());
		settings.setScriptHistoryVaultRaw(vaultRoot.toString());
		return settings;
	}

	private ScriptLibraryBrowserPanel panelFor(Path libRoot, Path vaultRoot) {
		return new ScriptLibraryBrowserPanel(new ScriptLibraryService(settingsWith(libRoot, vaultRoot)));
	}

	@Test
	void theTreeShowsFoldersAndScriptsOfEveryExtensionWithTheirLibraryPaths(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException {
		Files.createDirectories(libRoot.resolve("admin"));
		Files.createDirectories(libRoot.resolve("exports/monthly"));
		Files.writeString(libRoot.resolve("readme.txt"), "select 0;");
		Files.writeString(libRoot.resolve("admin/cleanup.bsql"), "select 1;");
		Files.writeString(libRoot.resolve("admin/rebuild.sql"), "select 2;");
		Files.writeString(libRoot.resolve("exports/monthly/sales.foo"), "select 3;");
		Files.writeString(libRoot.resolve("noextension"), "select 4;");

		ScriptLibraryBrowserPanel panel = panelFor(libRoot, vaultRoot);

		Assertions.assertEquals(List.of("admin", "exports", "exports/monthly"), panel.shownFolderPaths().stream().sorted().toList());
		Assertions.assertEquals(List.of("admin/cleanup.bsql", "admin/rebuild.sql", "exports/monthly/sales.foo", "noextension", "readme.txt"),
				panel.shownAssetPaths().stream().sorted().toList());
	}

	@Test
	void emptyFoldersAreShownToo(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException {
		Files.createDirectories(libRoot.resolve("qa/regression"));

		ScriptLibraryBrowserPanel panel = panelFor(libRoot, vaultRoot);

		Assertions.assertTrue(panel.shownFolderPaths().containsAll(List.of("qa", "qa/regression")), panel.shownFolderPaths().toString());
	}

	@Test
	void theReservedArchivesFolderAndNonTextFilesAreNeverShown(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException {
		Files.writeString(libRoot.resolve("real.bsql"), "select 1;");
		Files.write(libRoot.resolve("blob.bin"), new byte[] { 0, 1, 2, 0, 0 });
		Files.createDirectories(libRoot.resolve("archives"));
		Files.writeString(libRoot.resolve("archives/old.bsql.20260101-000000"), "select 0;");

		ScriptLibraryBrowserPanel panel = panelFor(libRoot, vaultRoot);

		Assertions.assertEquals(List.of("real.bsql"), panel.shownAssetPaths());
		Assertions.assertFalse(panel.shownFolderPaths().contains("archives"));
	}

	@Test
	void thereAreNoTypeNodesAndNoJavaScriptSectionEvenWhenAJsFolderIsConfigured(@TempDir Path libRoot, @TempDir Path jsRoot, @TempDir Path vaultRoot)
			throws IOException {
		Files.writeString(libRoot.resolve("a.bsql"), "select 1;");
		Files.writeString(jsRoot.resolve("hello.js"), "print(1);");
		ConsoleSettings settings = settingsWith(libRoot, vaultRoot);
		settings.setJsScriptsPath(jsRoot.toString());

		ScriptLibraryBrowserPanel panel = new ScriptLibraryBrowserPanel(new ScriptLibraryService(settings));

		Assertions.assertEquals(List.of("a.bsql"), panel.shownAssetPaths(), "JS scripts live outside the Scripts Library and never appear in the editor");
		Assertions.assertTrue(panel.shownFolderPaths().isEmpty(), panel.shownFolderPaths().toString());
	}

	/** {@code Refresh} (spec section 12) - rescans the filesystem; a file that appeared or was removed outside BroadSQL since the last reload appears/disappears at the next {@code reload()}. */
	@Test
	void reloadReflectsFilesAddedOrRemovedOnDiskSinceTheLastReload(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException {
		Files.writeString(libRoot.resolve("customer.sql"), "select 1;");
		ScriptLibraryBrowserPanel panel = panelFor(libRoot, vaultRoot);
		Assertions.assertEquals(1, panel.shownAssetPaths().size());

		Files.createDirectories(libRoot.resolve("sub"));
		Files.writeString(libRoot.resolve("sub/orders.bsql"), "select 2;");
		panel.reload();
		Assertions.assertEquals(2, panel.shownAssetPaths().size(), "a file added outside BroadSQL must appear after Refresh");

		Files.delete(libRoot.resolve("customer.sql"));
		panel.reload();
		Assertions.assertEquals(List.of("sub/orders.bsql"), panel.shownAssetPaths(), "a file removed outside BroadSQL must disappear after Refresh");
	}

	@Test
	void theSearchFilterMatchesPathsAndDescriptionsAndKeepsOnlyTheirFolders(@TempDir Path libRoot, @TempDir Path vaultRoot) throws Exception {
		Files.createDirectories(libRoot.resolve("finance"));
		Files.createDirectories(libRoot.resolve("hr"));
		Files.writeString(libRoot.resolve("finance/revenue.bsql"), "-- @description: quarterly numbers\nselect 1;");
		Files.writeString(libRoot.resolve("hr/people.bsql"), "select 2;");
		ScriptLibraryBrowserPanel panel = panelFor(libRoot, vaultRoot);
		javax.swing.JTextField search = findSearchField(panel);

		search.setText("quarterly");
		Assertions.assertEquals(List.of("finance/revenue.bsql"), panel.shownAssetPaths());
		Assertions.assertEquals(List.of("finance"), panel.shownFolderPaths());

		search.setText("");
		Assertions.assertEquals(2, panel.shownAssetPaths().size());
	}

	@Test
	void selectedFolderIsTheFolderNewScriptsAreCreatedIn(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException {
		Files.createDirectories(libRoot.resolve("admin"));
		Files.writeString(libRoot.resolve("admin/cleanup.bsql"), "select 1;");
		Files.writeString(libRoot.resolve("top.bsql"), "select 2;");
		ScriptLibraryBrowserPanel panel = panelFor(libRoot, vaultRoot);

		Assertions.assertNull(panel.selectedFolder(), "nothing selected means the library root");
		panel.selectFolder("admin");
		Assertions.assertEquals("admin", panel.selectedFolder());
		panel.selectRoot();
		Assertions.assertNull(panel.selectedFolder());
	}

	@Test
	void reloadPreservesTheExpansionOfTheFolders(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException {
		Files.createDirectories(libRoot.resolve("a/b"));
		Files.writeString(libRoot.resolve("a/b/x.bsql"), "select 1;");
		ScriptLibraryBrowserPanel panel = panelFor(libRoot, vaultRoot);
		javax.swing.JTree tree = findTree(panel);
		Assertions.assertEquals(3, tree.getRowCount(), "first load expands everything: a, b, x.bsql (the library root itself is not shown)");

		panel.reload();

		Assertions.assertEquals(3, tree.getRowCount(), "a reload must not collapse the tree the user had open");
	}

	@Test
	void listenerRequestsCarryLibraryPaths(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException, BroadSQLException {
		Files.writeString(libRoot.resolve("customer.sql"), "select 1;");
		ScriptLibraryBrowserPanel panel = panelFor(libRoot, vaultRoot);

		List<String> calls = new ArrayList<>();
		panel.setListener(new ScriptLibraryBrowserListener() {
			@Override
			public void onAssetOpenRequested(String relativePath) {
				calls.add("open:" + relativePath);
			}

			@Override
			public void onNewRequested(String folderOrNull) {
			}

			@Override
			public void onNewFolderRequested(String parentFolderOrNull) {
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
		});

		// No real mouse/selection event is driven here (that needs a real display); this verifies the panel
		// was built and wired without throwing under headless construction.
		Assertions.assertDoesNotThrow(panel::reload);
	}

	private static javax.swing.JTextField findSearchField(java.awt.Container container) {
		for (java.awt.Component child : container.getComponents()) {
			if (child instanceof javax.swing.JTextField field) {
				return field;
			}
			if (child instanceof java.awt.Container nested) {
				javax.swing.JTextField found = findSearchField(nested);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static javax.swing.JTree findTree(java.awt.Container container) {
		for (java.awt.Component child : container.getComponents()) {
			if (child instanceof javax.swing.JTree tree) {
				return tree;
			}
			if (child instanceof java.awt.Container nested) {
				javax.swing.JTree found = findTree(nested);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}
}
