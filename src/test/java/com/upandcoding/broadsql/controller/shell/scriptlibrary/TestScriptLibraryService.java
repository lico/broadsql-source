package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;

class TestScriptLibraryService {

	private ConsoleSettings settingsFor(Path libRoot, Path vaultRoot) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setScriptsLibraryPath(libRoot.toString());
		settings.setScriptHistoryVaultRaw(vaultRoot.toString());
		return settings;
	}

	@Test
	void createOpensTheNewAssetAndEstablishesABaselineRevision(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException, BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));

		ScriptAsset asset = service.create("customer.sql", "SELECT 1;");

		Assertions.assertEquals("SELECT 1;", asset.content());
		Assertions.assertTrue(Files.exists(libRoot.resolve("customer.sql")));
		Assertions.assertEquals(1, service.revisionVault().listRevisions(asset.assetId()).size());
	}

	@Test
	void createFailsIfTheNameAlreadyExists(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		Files.writeString(libRoot.resolve("customer.sql"), "select 1;");

		Assertions.assertThrows(BroadSQLException.class, () -> service.create("customer.sql", "x"));
	}

	// ------------------------------------------------------------------
	// Filename normalization (SPRINT 0917-01 corrective acceptance pass, defect 6: New/Rename/Duplicate
	// must auto-append the required extension, never require the user to type it, and never double it).
	// ------------------------------------------------------------------

	@Test
	void createAppendsTheDefaultBsqlExtensionWhenMissing(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));

		ScriptAsset asset = service.create("myquery", "SELECT 1;");

		Assertions.assertEquals("myquery.bsql", asset.relativePath());
		Assertions.assertTrue(Files.exists(libRoot.resolve("myquery.bsql")));
	}

	@Test
	void createDoesNotDoubleAnAlreadyCorrectExtension(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));

		ScriptAsset asset = service.create("myquery.sql", "SELECT 1;");

		Assertions.assertEquals("myquery.sql", asset.relativePath());
	}

	@Test
	void createPreservesAnAlreadyCorrectExtensionsCaseWithoutDoubling(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));

		ScriptAsset asset = service.create("myquery.SQL", "SELECT 1;");

		Assertions.assertEquals("myquery.SQL", asset.relativePath(), "must never become myquery.SQL.sql");
	}

	@Test
	void anyOtherExtensionIsAcceptedAsTypedBecauseExtensionsCarryNoMeaning(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));

		for (String name : new String[] { "a.txt", "b.foo", "c.sql", "d.SQL", "e.bsql" }) {
			Assertions.assertEquals(name, service.create(name, "SELECT 1;").relativePath());
			Assertions.assertFalse(Files.exists(libRoot.resolve(name + ".bsql")), "an explicit extension must never get .bsql appended");
		}
	}

	@Test
	void aNewScriptInASubfolderGetsItsFoldersCreatedAndTheDefaultExtension(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));

		ScriptAsset asset = service.create("maintenance/nightly/cleanup", "SELECT 1;");

		Assertions.assertEquals("maintenance/nightly/cleanup.bsql", asset.relativePath());
		Assertions.assertTrue(Files.exists(libRoot.resolve("maintenance").resolve("nightly").resolve("cleanup.bsql")));
		Assertions.assertTrue(service.listFolders().contains("maintenance/nightly"));
	}

	@Test
	void renameAppendsTheDefaultExtensionWhenMissing(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		ScriptAsset asset = service.create("old-name.sql", "SELECT 1;");

		ScriptAsset renamed = service.rename(asset.assetId(), "old-name.sql", "renamedquery", null);

		Assertions.assertEquals("renamedquery.bsql", renamed.relativePath());
		Assertions.assertEquals(asset.assetId(), renamed.assetId(), "a rename keeps the asset id and history");
	}

	@Test
	void createCollisionIsRejected(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		service.create("myquery", "SELECT 1;");

		Assertions.assertThrows(BroadSQLException.class, () -> service.create("myquery", "SELECT 2;"), "'myquery' normalizes to the already-existing 'myquery.bsql'");
		Assertions.assertThrows(BroadSQLException.class, () -> service.create("myquery.bsql", "SELECT 2;"));
	}

	@Test
	void openingAPreExistingUnmanagedFileRegistersABaselineWithoutModifyingIt(@TempDir Path libRoot, @TempDir Path vaultRoot)
			throws IOException, BroadSQLException {
		Files.writeString(libRoot.resolve("legacy.sql"), "SELECT * FROM LEGACY;");
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));

		ScriptAsset asset = service.open("legacy.sql");

		Assertions.assertEquals("SELECT * FROM LEGACY;", Files.readString(libRoot.resolve("legacy.sql"), StandardCharsets.UTF_8), "opening must never modify the working file");
		Assertions.assertEquals(1, service.revisionVault().listRevisions(asset.assetId()).size());
		Assertions.assertEquals(RevisionOrigin.CREATE, service.revisionVault().getLatestRevisionSummary(asset.assetId()).origin());
	}

	@Test
	void savingChangedContentCreatesARevisionAndSavingUnchangedContentDoesNot(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		ScriptAsset asset = service.create("customer.sql", "SELECT 1;");

		SaveOutcome changed = service.save(asset.assetId(), "customer.sql", "SELECT 2;", null);
		SaveOutcome unchanged = service.save(asset.assetId(), "customer.sql", "SELECT 2;", null);

		Assertions.assertEquals(SaveOutcome.Status.SAVED, changed.status());
		Assertions.assertTrue(changed.revisionCreated());
		Assertions.assertEquals(SaveOutcome.Status.SAVED, unchanged.status());
		Assertions.assertFalse(unchanged.revisionCreated());
		Assertions.assertEquals(2, service.revisionVault().listRevisions(asset.assetId()).size());
	}

	@Test
	void saveWithCommentRecordsTheComment(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		ScriptAsset asset = service.create("customer.sql", "SELECT 1;");

		service.save(asset.assetId(), "customer.sql", "SELECT 2;", "Added a safety check");

		Assertions.assertEquals("Added a safety check", service.revisionVault().getLatestRevisionSummary(asset.assetId()).comment());
	}

	@Test
	void renamePreservesAssetIdAndHistory(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		ScriptAsset asset = service.create("old-name.sql", "SELECT 1;");
		service.save(asset.assetId(), "old-name.sql", "SELECT 2;", null);

		ScriptAsset renamed = service.rename(asset.assetId(), "old-name.sql", "new-name.sql", null);

		Assertions.assertEquals(asset.assetId(), renamed.assetId());
		Assertions.assertEquals("new-name.sql", renamed.relativePath());
		Assertions.assertFalse(Files.exists(libRoot.resolve("old-name.sql")));
		Assertions.assertTrue(Files.exists(libRoot.resolve("new-name.sql")));
		Assertions.assertEquals(3, service.revisionVault().listRevisions(asset.assetId()).size(), "create + one edit + rename = 3 revisions");
	}

	@Test
	void duplicateCreatesAnIndependentAssetWithItsOwnHistory(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		ScriptAsset source = service.create("source.sql", "SELECT 1;");
		service.save(source.assetId(), "source.sql", "SELECT 2;", null);

		ScriptAsset duplicate = service.duplicate("source.sql", "copy.sql");

		Assertions.assertNotEquals(source.assetId(), duplicate.assetId());
		Assertions.assertEquals("SELECT 2;", duplicate.content());
		Assertions.assertEquals(1, service.revisionVault().listRevisions(duplicate.assetId()).size(), "the duplicate has its own fresh v1, not the source's 2 revisions");
	}

	@Test
	void deleteArchivesTheScriptTheSameWayLibDelDoesAndKeepsItsHistory(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		ScriptAsset asset = service.create("customer.sql", "SELECT 1;");

		service.delete("customer.sql");

		Assertions.assertFalse(Files.exists(libRoot.resolve("customer.sql")));
		Assertions.assertEquals(1, service.listArchived().size(), "the editor's Delete is the library's archive: exactly what LIB DEL does");
		Assertions.assertEquals("customer.sql", service.listArchived().get(0).getOriginalRelativePath());
		Assertions.assertEquals(1, service.revisionVault().listRevisions(asset.assetId()).size(), "history stays retained");
	}

	@Test
	void deletingThenCreatingAnUnrelatedFileAtTheSamePathStartsANewLineage(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		ScriptAsset original = service.create("customer.sql", "original content");
		service.delete("customer.sql");

		ScriptAsset recreated = service.create("customer.sql", "unrelated new content");

		Assertions.assertNotEquals(original.assetId(), recreated.assetId());
		Assertions.assertEquals(1, service.revisionVault().listRevisions(recreated.assetId()).size());
		Assertions.assertEquals(1, service.revisionVault().listRevisions(original.assetId()).size(), "the archived lineage is untouched");
	}

	@Test
	void detectExternalChangeReportsUnchangedChangedAndDeleted(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException, BroadSQLException, InterruptedException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		ScriptAsset asset = service.create("customer.sql", "SELECT 1;");

		Assertions.assertEquals(ExternalChangeStatus.UNCHANGED, service.detectExternalChange("customer.sql", asset.lastModifiedMillis()));

		Thread.sleep(15);
		Files.writeString(libRoot.resolve("customer.sql"), "SELECT 2; -- changed outside BroadSQL");
		Assertions.assertEquals(ExternalChangeStatus.CHANGED, service.detectExternalChange("customer.sql", asset.lastModifiedMillis()));

		Files.delete(libRoot.resolve("customer.sql"));
		Assertions.assertEquals(ExternalChangeStatus.DELETED_EXTERNALLY, service.detectExternalChange("customer.sql", asset.lastModifiedMillis()));
	}

	@Test
	void restoreRevisionWritesTheHistoricalContentBackToTheCurrentPath(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException, BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		ScriptAsset asset = service.create("customer.sql", "v1 content");
		service.save(asset.assetId(), "customer.sql", "v2 content", null);

		ScriptAsset restored = service.restoreRevision(asset.assetId(), 1, null);

		Assertions.assertEquals("v1 content", restored.content());
		Assertions.assertEquals("v1 content", Files.readString(libRoot.resolve("customer.sql"), StandardCharsets.UTF_8));
		Assertions.assertEquals(3, service.revisionVault().listRevisions(asset.assetId()).size(), "restore creates a new revision, never rewrites the previous two");
	}

	@Test
	void restoringAnArchivedScriptBringsBackTheFileAndItsOwnHistory(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException, BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		ScriptAsset asset = service.create("customer.sql", "recoverable content");
		service.save(asset.assetId(), "customer.sql", "recoverable content v2", null);
		service.delete("customer.sql");

		ScriptAsset restored = service.restoreArchived(service.listArchived().get(0));

		Assertions.assertEquals("recoverable content v2", Files.readString(libRoot.resolve("customer.sql"), StandardCharsets.UTF_8));
		Assertions.assertEquals(asset.assetId(), restored.assetId(), "restoring the archive continues the same history lineage");
		Assertions.assertEquals(2, service.revisionVault().listRevisions(asset.assetId()).size());
		Assertions.assertTrue(service.listArchived().isEmpty());
	}

	@Test
	void restoringIsRefusedWhileTheOriginalPathIsOccupiedAgain(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		service.create("customer.sql", "old");
		service.delete("customer.sql");
		service.create("customer.sql", "new");

		Assertions.assertThrows(BroadSQLException.class, () -> service.restoreArchived(service.listArchived().get(0)));
	}

	@Test
	void anArchivedScriptFromTheShellIsTheSameObjectTheEditorSeesInRecentlyDeleted(@TempDir Path libRoot, @TempDir Path vaultRoot) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		Files.writeString(libRoot.resolve("viashell.bsql"), "SELECT 1;");
		new com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary(libRoot.toString()).archive(libRoot.resolve("viashell.bsql")); // what LIB DEL does

		Assertions.assertEquals("viashell.bsql", service.listArchived().get(0).getOriginalRelativePath());
		service.restoreArchived(service.listArchived().get(0));
		Assertions.assertTrue(Files.exists(libRoot.resolve("viashell.bsql")));
	}

	@Test
	void foldersCanBeCreatedRenamedAndOnlyEmptyOnesDeleted(@TempDir Path libRoot, @TempDir Path vaultRoot) throws BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));

		service.createFolder("qa/regression");
		Assertions.assertTrue(service.listFolders().containsAll(java.util.List.of("qa", "qa/regression")));
		service.create("qa/regression/t.bsql", "SELECT 1;");
		Assertions.assertThrows(BroadSQLException.class, () -> service.deleteEmptyFolder("qa/regression"), "a non-empty folder is refused");
		service.renameFolder("qa", "quality");
		Assertions.assertTrue(Files.exists(libRoot.resolve("quality/regression/t.bsql")));
		service.createFolder("empty");
		service.deleteEmptyFolder("empty");
		Assertions.assertFalse(service.listFolders().contains("empty"));
		Assertions.assertFalse(service.listFolders().contains("archives"), "the reserved archive folder is never a tree folder");
	}

	@Test
	void everyOperationIsConfinedToTheLibrary(@TempDir Path libRoot, @TempDir Path vaultRoot) {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));

		Assertions.assertThrows(BroadSQLException.class, () -> service.create("../escape.bsql", "x"));
		Assertions.assertThrows(BroadSQLException.class, () -> service.createFolder("../escape"));
		Assertions.assertThrows(BroadSQLException.class, () -> service.open("../escape.bsql"));
		Assertions.assertThrows(BroadSQLException.class, () -> service.delete("../escape.bsql"));
		Assertions.assertThrows(BroadSQLException.class, () -> service.create("archives/x.bsql", "x"), "archives is reserved");
	}

	@Test
	void savingKeepsTheFilesOwnEncodingAndRefusesTextItCannotEncode(@TempDir Path libRoot, @TempDir Path vaultRoot) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		byte[] body = "SELECT 'a';".getBytes(StandardCharsets.UTF_8);
		byte[] bom = new byte[body.length + 3];
		bom[0] = (byte) 0xEF;
		bom[1] = (byte) 0xBB;
		bom[2] = (byte) 0xBF;
		System.arraycopy(body, 0, bom, 3, body.length);
		Files.write(libRoot.resolve("bom.bsql"), bom);
		ScriptAsset asset = service.open("bom.bsql");
		Assertions.assertEquals("SELECT 'a';", asset.content(), "the BOM is not part of the text");

		service.save(asset.assetId(), "bom.bsql", "SELECT 'é';", null);

		byte[] after = Files.readAllBytes(libRoot.resolve("bom.bsql"));
		Assertions.assertEquals((byte) 0xEF, after[0], "the BOM state is preserved");
		Assertions.assertEquals("SELECT 'é';", new String(after, 3, after.length - 3, StandardCharsets.UTF_8));
	}

	@Test
	void binaryFilesAreNeverListedOrOpened(@TempDir Path libRoot, @TempDir Path vaultRoot) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		Files.write(libRoot.resolve("blob.bin"), new byte[] { 0, 1, 2, 0, 0 });
		service.create("real.bsql", "SELECT 1;");

		Assertions.assertEquals(1, service.listAssets().size());
		Assertions.assertThrows(BroadSQLException.class, () -> service.open("blob.bin"));
	}

	/**
	 * Simulates the Save/history partial-failure scenario (Phase A cross-cutting design): the working
	 * file was saved successfully but the corresponding history write failed at the time, so the vault's
	 * last known revision does not reflect the file's actual current content. The next {@code open()}
	 * (or an explicit {@code Refresh}) must catch this up automatically, per
	 * {@code RevisionVault#reconcileIfNeeded} - {@code ScriptLibraryService.open()} already performs
	 * exactly this reconciliation on every load, so no separate wiring was needed for this phase; this
	 * test proves the catch-up actually happens end to end.
	 */
	@Test
	void openingAnAssetWhoseHistoryFellBehindTheWorkingFileCatchesUpAutomatically(@TempDir Path libRoot, @TempDir Path vaultRoot)
			throws IOException, BroadSQLException {
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));
		ScriptAsset asset = service.create("customer.sql", "v1 content");
		Assertions.assertEquals(1, service.revisionVault().listRevisions(asset.assetId()).size());

		// Simulate a save whose history write failed: the working file changes on disk, but the vault
		// is never told (bypasses ScriptLibraryService.save() entirely, writing directly like an
		// external editor - or a completed working-file write followed by a failed history write - would).
		Files.writeString(libRoot.resolve("customer.sql"), "v2 content, history write never recorded", StandardCharsets.UTF_8);

		ScriptAsset reopened = service.open("customer.sql");

		Assertions.assertEquals("v2 content, history write never recorded", reopened.content());
		List<RevisionSummary> revisions = service.revisionVault().listRevisions(asset.assetId());
		Assertions.assertEquals(2, revisions.size(), "the missed revision must be caught up, not silently skipped");
		Assertions.assertEquals(RevisionOrigin.EDIT_SAVE, revisions.get(1).origin());
	}

	@Test
	void listAssetsAssignsStableIdsWithoutSpammingHistory(@TempDir Path libRoot, @TempDir Path vaultRoot) throws IOException, BroadSQLException {
		Files.writeString(libRoot.resolve("a.sql"), "select 1;");
		Files.writeString(libRoot.resolve("b.sql"), "select 2;");
		ScriptLibraryService service = new ScriptLibraryService(settingsFor(libRoot, vaultRoot));

		List<ScriptAsset> assets = service.listAssets();

		Assertions.assertEquals(2, assets.size());
		for (ScriptAsset asset : assets) {
			Assertions.assertTrue(service.revisionVault().listRevisions(asset.assetId()).isEmpty(), "merely listing must not create a baseline revision");
		}
	}
}
