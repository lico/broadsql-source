package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;

/**
 * SPRINT 3009A (#191): moving Scripts and folders inside the Scripts Library, the service behind the Editor's drag
 * and drop. A move is never an overwrite, never lands a folder inside itself, changes nothing when refused or when
 * the filesystem fails, and keeps every moved Script's identity (asset id) and revision history.
 */
class TestScriptLibraryMoves {

	private static ScriptLibraryService serviceFor(Path libRoot, Path vaultRoot) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setScriptsLibraryPath(libRoot.toString());
		settings.setScriptHistoryVaultRaw(vaultRoot.toString());
		return new ScriptLibraryService(settings);
	}

	@Test
	void aScriptMovesIntoAFolderKeepingItsNameIdentityAndHistory(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		Files.createDirectories(lib.resolve("reports"));
		Files.writeString(lib.resolve("noextension"), "select 1;");
		ScriptLibraryService service = serviceFor(lib, vault);
		String id = service.open("noextension").assetId();

		ScriptAsset moved = service.moveAsset("noextension", "reports");

		Assertions.assertEquals("reports/noextension", moved.relativePath(), "the name is kept exactly: no extension is added by a move");
		Assertions.assertEquals(id, moved.assetId());
		Assertions.assertFalse(Files.exists(lib.resolve("noextension")));
		Assertions.assertEquals("select 1;", Files.readString(lib.resolve("reports/noextension")));
		List<RevisionSummary> revisions = service.revisionVault().listRevisions(id);
		Assertions.assertEquals("reports/noextension", revisions.get(revisions.size() - 1).relativePathAtRevision(), "the history follows the new path");
		Assertions.assertEquals(id, service.open("reports/noextension").assetId(), "reopening the moved Script finds the same identity");
	}

	@Test
	void aScriptMovesBackToTheLibraryRoot(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		Files.createDirectories(lib.resolve("a"));
		Files.writeString(lib.resolve("a/x.sql"), "select 1;");
		ScriptLibraryService service = serviceFor(lib, vault);

		Assertions.assertEquals("x.sql", service.moveAsset("a/x.sql", null).relativePath());
		Assertions.assertTrue(Files.exists(lib.resolve("x.sql")));
	}

	@Test
	void aFolderMovesIntoAnotherWithEveryScriptKeepingItsIdentity(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		Files.createDirectories(lib.resolve("reports/monthly"));
		Files.createDirectories(lib.resolve("archive2025"));
		Files.writeString(lib.resolve("reports/QR1.sql"), "select 1;");
		Files.writeString(lib.resolve("reports/monthly/sales.sql"), "select 2;");
		ScriptLibraryService service = serviceFor(lib, vault);
		String qr1 = service.open("reports/QR1.sql").assetId();
		String sales = service.open("reports/monthly/sales.sql").assetId();

		FolderMoveOutcome outcome = service.moveFolder("reports", "archive2025");

		Assertions.assertEquals("archive2025/reports", outcome.newFolderKey());
		Assertions.assertNull(outcome.historyWarning());
		Assertions.assertFalse(Files.exists(lib.resolve("reports")));
		Assertions.assertTrue(Files.exists(lib.resolve("archive2025/reports/monthly/sales.sql")));
		Assertions.assertEquals(List.of("archive2025/reports/QR1.sql", "archive2025/reports/monthly/sales.sql"),
				outcome.movedScripts().stream().map(ScriptAsset::relativePath).sorted().toList());
		Assertions.assertEquals(qr1, service.open("archive2025/reports/QR1.sql").assetId());
		Assertions.assertEquals(sales, service.open("archive2025/reports/monthly/sales.sql").assetId());
		for (ScriptAsset moved : outcome.movedScripts()) {
			Assertions.assertTrue(List.of(qr1, sales).contains(moved.assetId()), "each moved Script carries its original asset id");
		}
	}

	@Test
	void aFolderRenameAlsoKeepsTheIdentityOfTheScriptsInside(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		Files.createDirectories(lib.resolve("old"));
		Files.writeString(lib.resolve("old/x.sql"), "select 1;");
		ScriptLibraryService service = serviceFor(lib, vault);
		String id = service.open("old/x.sql").assetId();

		FolderMoveOutcome outcome = service.renameFolder("old", "new");

		Assertions.assertEquals("new", outcome.newFolderKey());
		Assertions.assertEquals(id, service.open("new/x.sql").assetId(), "before this sprint a folder rename silently gave every Script inside a new, empty history");
	}

	@Test
	void aSameNameConflictIsRefusedAndNothingIsOverwritten(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		Files.createDirectories(lib.resolve("reports"));
		Files.createDirectories(lib.resolve("queries/reports"));
		Files.writeString(lib.resolve("x.sql"), "outer");
		Files.writeString(lib.resolve("reports/x.sql"), "inner");
		ScriptLibraryService service = serviceFor(lib, vault);

		BroadSQLException scriptConflict = Assertions.assertThrows(BroadSQLException.class, () -> service.moveAsset("x.sql", "reports"));
		Assertions.assertTrue(scriptConflict.getMessage().contains("already exists"), scriptConflict.getMessage());
		Assertions.assertEquals("outer", Files.readString(lib.resolve("x.sql")));
		Assertions.assertEquals("inner", Files.readString(lib.resolve("reports/x.sql")));

		BroadSQLException folderConflict = Assertions.assertThrows(BroadSQLException.class, () -> service.moveFolder("reports", "queries"));
		Assertions.assertTrue(folderConflict.getMessage().contains("already exists"), folderConflict.getMessage());
		Assertions.assertTrue(Files.exists(lib.resolve("reports/x.sql")));
	}

	@Test
	void aFolderCannotMoveIntoItselfOrItsDescendants(@TempDir Path lib, @TempDir Path vault) throws IOException {
		Files.createDirectories(lib.resolve("a/b/c"));
		ScriptLibraryService service = serviceFor(lib, vault);

		BroadSQLException itself = Assertions.assertThrows(BroadSQLException.class, () -> service.moveFolder("a", "a"));
		Assertions.assertTrue(itself.getMessage().contains("into itself"), itself.getMessage());
		BroadSQLException child = Assertions.assertThrows(BroadSQLException.class, () -> service.moveFolder("a", "a/b"));
		Assertions.assertTrue(child.getMessage().contains("subfolder"), child.getMessage());
		BroadSQLException grandChild = Assertions.assertThrows(BroadSQLException.class, () -> service.moveFolder("a", "A/B/C"));
		Assertions.assertTrue(grandChild.getMessage().contains("subfolder"), "compared without case: " + grandChild.getMessage());
		Assertions.assertTrue(Files.isDirectory(lib.resolve("a/b/c")));
	}

	@Test
	void aMoveToWhereTheItemAlreadyIsOrToANonFolderIsRefused(@TempDir Path lib, @TempDir Path vault) throws IOException {
		Files.createDirectories(lib.resolve("a/b"));
		Files.writeString(lib.resolve("a/x.sql"), "select 1;");
		Files.writeString(lib.resolve("file.sql"), "select 2;");
		ScriptLibraryService service = serviceFor(lib, vault);

		Assertions.assertThrows(BroadSQLException.class, () -> service.moveAsset("a/x.sql", "a"));
		Assertions.assertThrows(BroadSQLException.class, () -> service.moveFolder("a/b", "a"));
		Assertions.assertThrows(BroadSQLException.class, () -> service.moveAsset("a/x.sql", "file.sql"), "a Script is not a destination folder");
		Assertions.assertThrows(BroadSQLException.class, () -> service.moveAsset("a/x.sql", "missing"));
		Assertions.assertThrows(BroadSQLException.class, () -> service.moveAsset("a/x.sql", "archives"), "the reserved archives folder is never a destination");
	}

	@Test
	void aFilesystemFailureLeavesTheScriptItsHistoryAndTheFolderUnchanged(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		Files.createDirectories(lib.resolve("reports"));
		Files.createDirectories(lib.resolve("dest"));
		Files.writeString(lib.resolve("x.sql"), "select 1;");
		Files.writeString(lib.resolve("reports/y.sql"), "select 2;");
		ScriptLibraryService service = serviceFor(lib, vault);
		String x = service.open("x.sql").assetId();
		String y = service.open("reports/y.sql").assetId();
		int xRevisions = service.revisionVault().listRevisions(x).size();
		service.setPathMoverForTesting((source, target) -> {
			throw new java.nio.file.AccessDeniedException(source.toString());
		});

		BroadSQLException scriptFailure = Assertions.assertThrows(BroadSQLException.class, () -> service.moveAsset("x.sql", "dest"));
		Assertions.assertTrue(scriptFailure.getMessage().startsWith("Could not move"), scriptFailure.getMessage());
		BroadSQLException folderFailure = Assertions.assertThrows(BroadSQLException.class, () -> service.moveFolder("reports", "dest"));
		Assertions.assertTrue(folderFailure.getMessage().startsWith("Could not move the folder"), folderFailure.getMessage());

		Assertions.assertTrue(Files.exists(lib.resolve("x.sql")));
		Assertions.assertTrue(Files.exists(lib.resolve("reports/y.sql")));
		Assertions.assertFalse(Files.exists(lib.resolve("dest/x.sql")));
		Assertions.assertFalse(Files.exists(lib.resolve("dest/reports")));
		Assertions.assertEquals(xRevisions, service.revisionVault().listRevisions(x).size(), "no revision recorded for a move that did not happen");
		Assertions.assertEquals(x, service.open("x.sql").assetId());
		Assertions.assertEquals(y, service.open("reports/y.sql").assetId());
	}

	@Test
	void theAbsolutePathOfALibraryItemIsInsideTheLibrary(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		Files.createDirectories(lib.resolve("reports"));
		Files.writeString(lib.resolve("reports/QR13.sql"), "select 1;");
		ScriptLibraryService service = serviceFor(lib, vault);

		Path absolute = service.absolutePathOf("reports/QR13.sql");

		Assertions.assertTrue(absolute.isAbsolute());
		Assertions.assertTrue(Files.isSameFile(lib.resolve("reports/QR13.sql"), absolute));
	}
}
