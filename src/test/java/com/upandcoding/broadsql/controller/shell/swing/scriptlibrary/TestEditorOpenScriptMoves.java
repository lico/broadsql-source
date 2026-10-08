package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.FolderMoveOutcome;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptAsset;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryService;

/**
 * SPRINT 3009A (#191): a Script open in an editor tab, with unsaved edits, is moved (alone or with its folder) or
 * renamed. The tab keeps representing it with its buffer and dirty state; its path, title and tooltip follow; and
 * the next Save, which writes {@code tab.asset()}'s path exactly as the frame's Save does, goes to the new path and
 * never recreates the old one.
 */
class TestEditorOpenScriptMoves {

	private static ScriptLibraryService serviceFor(Path lib, Path vault) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setScriptsLibraryPath(lib.toString());
		settings.setScriptHistoryVaultRaw(vault.toString());
		return new ScriptLibraryService(settings);
	}

	/** The frame's Save, minus the dialogs. */
	private static void save(ScriptLibraryService service, ScriptEditorTab tab) throws BroadSQLException {
		ScriptAsset asset = tab.asset();
		service.save(asset.assetId(), asset.relativePath(), tab.currentText(), null);
		tab.markSaved(service.open(asset.relativePath()));
	}

	@Test
	void aMovedOpenScriptKeepsItsUnsavedEditsAndSavesToItsNewPath(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		Files.createDirectories(lib.resolve("reports"));
		Files.writeString(lib.resolve("QR13.sql"), "select 1;");
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptEditorTab tab = pane.openOrFocus(service.open("QR13.sql"));
		tab.textArea().setText("select 2;");

		ScriptAsset moved = service.moveAsset("QR13.sql", "reports");
		Assertions.assertSame(tab, pane.retargetOpenTab(moved));

		Assertions.assertTrue(tab.isDirty(), "the unsaved edit survives the move");
		Assertions.assertEquals("select 2;", tab.currentText());
		Assertions.assertEquals("reports/QR13.sql", tab.asset().relativePath());
		Assertions.assertEquals("QR13.sql *", pane.getTitleAt(0));
		Assertions.assertEquals("reports/QR13.sql", pane.getToolTipTextAt(0));

		save(service, tab);

		Assertions.assertEquals("select 2;", Files.readString(lib.resolve("reports/QR13.sql")));
		Assertions.assertFalse(Files.exists(lib.resolve("QR13.sql")), "Save never recreates the old path");
		Assertions.assertFalse(tab.isDirty());
	}

	@Test
	void everyOpenScriptOfAMovedFolderFollowsIt(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		Files.createDirectories(lib.resolve("reports/monthly"));
		Files.createDirectories(lib.resolve("archive"));
		Files.writeString(lib.resolve("reports/a.sql"), "select 1;");
		Files.writeString(lib.resolve("reports/monthly/b.sql"), "select 2;");
		Files.writeString(lib.resolve("other.sql"), "select 3;");
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptEditorTab a = pane.openOrFocus(service.open("reports/a.sql"));
		ScriptEditorTab b = pane.openOrFocus(service.open("reports/monthly/b.sql"));
		ScriptEditorTab other = pane.openOrFocus(service.open("other.sql"));
		b.textArea().setText("select 22;");

		FolderMoveOutcome outcome = service.moveFolder("reports", "archive");
		for (ScriptAsset moved : outcome.movedScripts()) {
			pane.retargetOpenTab(moved);
		}

		Assertions.assertEquals("archive/reports/a.sql", a.asset().relativePath());
		Assertions.assertEquals("archive/reports/monthly/b.sql", b.asset().relativePath());
		Assertions.assertEquals("other.sql", other.asset().relativePath(), "a tab outside the folder is untouched");
		Assertions.assertTrue(b.isDirty());
		Assertions.assertFalse(a.isDirty());

		save(service, b);
		Assertions.assertEquals("select 22;", Files.readString(lib.resolve("archive/reports/monthly/b.sql")));
		Assertions.assertFalse(Files.exists(lib.resolve("reports")), "the old folder is not recreated by the save");
	}

	@Test
	void aFailedMoveLeavesTheTabOnItsOriginalPath(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		Files.createDirectories(lib.resolve("reports"));
		Files.writeString(lib.resolve("x.sql"), "select 1;");
		Files.writeString(lib.resolve("reports/x.sql"), "occupied");
		ScriptLibraryService service = serviceFor(lib, vault);
		ScriptEditorTabbedPane pane = new ScriptEditorTabbedPane();
		ScriptEditorTab tab = pane.openOrFocus(service.open("x.sql"));
		tab.textArea().setText("select 9;");

		Assertions.assertThrows(BroadSQLException.class, () -> service.moveAsset("x.sql", "reports"));
		save(service, tab);

		Assertions.assertEquals("select 9;", Files.readString(lib.resolve("x.sql")));
		Assertions.assertEquals("occupied", Files.readString(lib.resolve("reports/x.sql")), "the conflicting file was never overwritten");
	}
}
