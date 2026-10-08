package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

class TestRevisionVault {

	@Test
	void baselineRegistrationCreatesRevisionOne(@TempDir Path vaultRoot) throws BroadSQLException {
		RevisionVault vault = new RevisionVault(vaultRoot);
		String assetId = vault.resolveOrRegisterAssetId("root1", "customer.sql");

		RevisionOutcome outcome = vault.recordRevisionIfChanged(assetId, "SELECT 1;", "customer.sql", null);

		Assertions.assertTrue(outcome.revisionCreated());
		Assertions.assertEquals(1, outcome.latestRevision().revisionNumber());
		Assertions.assertEquals(RevisionOrigin.CREATE, outcome.latestRevision().origin());
	}

	@Test
	void unchangedSaveIsANoOp(@TempDir Path vaultRoot) throws BroadSQLException {
		RevisionVault vault = new RevisionVault(vaultRoot);
		String assetId = vault.resolveOrRegisterAssetId("root1", "customer.sql");
		vault.recordRevisionIfChanged(assetId, "SELECT 1;", "customer.sql", null);

		RevisionOutcome again = vault.recordRevisionIfChanged(assetId, "SELECT 1;", "customer.sql", null);

		Assertions.assertFalse(again.revisionCreated());
		Assertions.assertEquals(1, again.latestRevision().revisionNumber());
		Assertions.assertEquals(1, vault.listRevisions(assetId).size());
	}

	@Test
	void changedBodyCreatesTheNextRevisionNumberedExactly(@TempDir Path vaultRoot) throws BroadSQLException {
		RevisionVault vault = new RevisionVault(vaultRoot);
		String assetId = vault.resolveOrRegisterAssetId("root1", "customer.sql");
		vault.recordRevisionIfChanged(assetId, "SELECT 1;", "customer.sql", null);

		RevisionOutcome v2 = vault.recordRevisionIfChanged(assetId, "SELECT 2;", "customer.sql", null);
		RevisionOutcome v3 = vault.recordRevisionIfChanged(assetId, "SELECT 3;", "customer.sql", "comment");

		Assertions.assertEquals(2, v2.latestRevision().revisionNumber());
		Assertions.assertEquals(RevisionOrigin.EDIT_SAVE, v2.latestRevision().origin());
		Assertions.assertEquals(3, v3.latestRevision().revisionNumber());
		Assertions.assertEquals("comment", v3.latestRevision().comment());
		Assertions.assertEquals(3, vault.listRevisions(assetId).size());
	}

	@Test
	void metadataOnlyChangeCreatesARevisionEvenWhenBodyIsByteIdentical(@TempDir Path vaultRoot) throws BroadSQLException {
		RevisionVault vault = new RevisionVault(vaultRoot);
		String assetId = vault.resolveOrRegisterAssetId("root1", "customer.sql");
		vault.recordRevisionIfChanged(assetId, "-- @description: old\nSELECT 1;", "customer.sql", null);

		RevisionOutcome outcome = vault.recordRevisionIfChanged(assetId, "-- @description: new\nSELECT 1;", "customer.sql", null);

		Assertions.assertTrue(outcome.revisionCreated());
		Assertions.assertEquals(RevisionOrigin.METADATA_ONLY, outcome.latestRevision().origin());
	}

	@Test
	void renameOnlyChangeCreatesARevisionAndUpdatesTheIndex(@TempDir Path vaultRoot) throws BroadSQLException {
		RevisionVault vault = new RevisionVault(vaultRoot);
		String assetId = vault.resolveOrRegisterAssetId("root1", "old.sql");
		vault.recordRevisionIfChanged(assetId, "SELECT 1;", "old.sql", null);

		RevisionOutcome outcome = vault.recordRenameRevision(assetId, "new.sql", null);

		Assertions.assertTrue(outcome.revisionCreated());
		Assertions.assertEquals(RevisionOrigin.RENAME, outcome.latestRevision().origin());
		Assertions.assertEquals("new.sql", outcome.latestRevision().relativePathAtRevision());
		// The index must now resolve the new path to the SAME asset id, and the old path must be free.
		Assertions.assertEquals(assetId, vault.resolveOrRegisterAssetId("root1", "new.sql"));
		String freedPathAssetId = vault.resolveOrRegisterAssetId("root1", "old.sql");
		Assertions.assertNotEquals(assetId, freedPathAssetId, "the old path must be free for a new, unrelated asset");
	}

	@Test
	void workspaceIdentityNonCollisionAcrossTwoDifferentRootsWithTheSameRelativePath(@TempDir Path vaultRoot) throws BroadSQLException {
		RevisionVault vault = new RevisionVault(vaultRoot);
		String assetInRoot1 = vault.resolveOrRegisterAssetId("root1", "customer.sql");
		String assetInRoot2 = vault.resolveOrRegisterAssetId("root2", "customer.sql");

		Assertions.assertNotEquals(assetInRoot1, assetInRoot2);
	}

	@Test
	void restoreCreatesANewRevisionAndPreservesLaterOnes(@TempDir Path vaultRoot) throws BroadSQLException {
		RevisionVault vault = new RevisionVault(vaultRoot);
		String assetId = vault.resolveOrRegisterAssetId("root1", "customer.sql");
		vault.recordRevisionIfChanged(assetId, "v1", "customer.sql", null); // v1
		vault.recordRevisionIfChanged(assetId, "v2", "customer.sql", null); // v2
		vault.recordRevisionIfChanged(assetId, "v3", "customer.sql", null); // v3

		RestoreOutcome restored = vault.restore(assetId, 1, null);

		Assertions.assertEquals(4, restored.newRevision().revisionNumber(), "restore must never rewrite history - it always creates a new revision");
		Assertions.assertEquals(RevisionOrigin.RESTORE, restored.newRevision().origin());
		Assertions.assertEquals("v1", restored.restoredContent());
		List<RevisionSummary> all = vault.listRevisions(assetId);
		Assertions.assertEquals(4, all.size());
		Assertions.assertEquals("v2", vault.getRevision(assetId, 2).content(), "v2 must remain intact");
		Assertions.assertEquals("v3", vault.getRevision(assetId, 3).content(), "v3 must remain intact");
	}

	@Test
	void restoreAlwaysCreatesARevisionEvenIfContentMatchesCurrent(@TempDir Path vaultRoot) throws BroadSQLException {
		RevisionVault vault = new RevisionVault(vaultRoot);
		String assetId = vault.resolveOrRegisterAssetId("root1", "customer.sql");
		vault.recordRevisionIfChanged(assetId, "same", "customer.sql", null);

		RestoreOutcome restored = vault.restore(assetId, 1, null);

		Assertions.assertEquals(2, restored.newRevision().revisionNumber(), "Restore never a no-op, even matching the current state");
	}

	// ---- SPRINT 1909S: the vault follows ScriptsLibrary's archive; it does not define deletion ----

	@Test
	void archivingFreesThePathAndAnEntirelyNewFileAtTheSamePathStartsANewLineage(@TempDir Path root, @TempDir Path vaultRoot) throws Exception {
		RevisionVault vault = new RevisionVault(vaultRoot);
		String rootIdentity = RevisionVault.rootIdentityOf(root.toString());
		String original = vault.resolveOrRegisterAssetId(rootIdentity, "customer.bsql");
		vault.recordRevisionIfChanged(original, "SELECT 1;", "customer.bsql", null);

		vault.archived(root, "customer.bsql", "archives/customer.bsql.20260101-000000");

		Assertions.assertEquals(1, vault.listRevisions(original).size(), "history must remain fully retained after archive");
		String fresh = vault.resolveOrRegisterAssetId(rootIdentity, "customer.bsql");
		Assertions.assertNotEquals(original, fresh, "a genuinely new file at the freed path must never inherit the archived Script's history");
		Assertions.assertTrue(vault.listRevisions(fresh).isEmpty());
	}

	@Test
	void restoringTheArchivedFileContinuesTheOriginalHistory(@TempDir Path root, @TempDir Path vaultRoot) throws Exception {
		RevisionVault vault = new RevisionVault(vaultRoot);
		String rootIdentity = RevisionVault.rootIdentityOf(root.toString());
		String original = vault.resolveOrRegisterAssetId(rootIdentity, "customer.bsql");
		vault.recordRevisionIfChanged(original, "SELECT 1;", "customer.bsql", null);

		vault.archived(root, "customer.bsql", "archives/customer.bsql.20260101-000000");
		vault.restored(root, "archives/customer.bsql.20260101-000000", "customer.bsql");

		Assertions.assertEquals(original, vault.resolveOrRegisterAssetId(rootIdentity, "customer.bsql"), "restore re-attaches the same lineage");
		vault.recordRevisionIfChanged(original, "SELECT 2;", "customer.bsql", null);
		Assertions.assertEquals(2, vault.listRevisions(original).size());
	}

	@Test
	void deleteThenCreateNewThenRestoreOldKeepsTwoSeparateLineages(@TempDir Path root, @TempDir Path vaultRoot) throws Exception {
		RevisionVault vault = new RevisionVault(vaultRoot);
		String rootIdentity = RevisionVault.rootIdentityOf(root.toString());
		String oldAsset = vault.resolveOrRegisterAssetId(rootIdentity, "a.bsql");
		vault.recordRevisionIfChanged(oldAsset, "old", "a.bsql", null);
		vault.archived(root, "a.bsql", "archives/a.bsql.20260101-000000");

		String newAsset = vault.resolveOrRegisterAssetId(rootIdentity, "a.bsql");
		vault.recordRevisionIfChanged(newAsset, "new", "a.bsql", null);
		Assertions.assertNotEquals(oldAsset, newAsset);

		// the library refuses the restore while the path is occupied; if the new file is archived first, the old lineage resumes
		vault.archived(root, "a.bsql", "archives/a.bsql.20260202-000000");
		vault.restored(root, "archives/a.bsql.20260101-000000", "a.bsql");
		Assertions.assertEquals(oldAsset, vault.resolveOrRegisterAssetId(rootIdentity, "a.bsql"));
		Assertions.assertEquals("new", vault.getLatestRevision(newAsset).content(), "the other lineage is untouched");
	}

	@Test
	void archivingAScriptThatWasNeverOpenedInTheEditorIsANoOpAndNeverThrows(@TempDir Path root, @TempDir Path vaultRoot) {
		RevisionVault vault = new RevisionVault(vaultRoot);
		Assertions.assertDoesNotThrow(() -> vault.archived(root, "never-seen.bsql", "archives/never-seen.bsql.20260101-000000"));
		Assertions.assertDoesNotThrow(() -> vault.restored(root, "archives/never-seen.bsql.20260101-000000", "never-seen.bsql"));
	}

	@Test
	void archiveAndRestoreThroughTheLibraryDriveTheVaultHook(@TempDir Path root, @TempDir Path vaultRoot) throws Exception {
		RevisionVault vault = new RevisionVault(vaultRoot);
		com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary.setHistoryLinkage(vault);
		try {
			java.nio.file.Files.writeString(root.resolve("x.bsql"), "SELECT 1;");
			com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary library =
					new com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary(root.toString());
			String rootIdentity = RevisionVault.rootIdentityOf(library.getRoot().toString());
			String assetId = vault.resolveOrRegisterAssetId(rootIdentity, "x.bsql");
			vault.recordRevisionIfChanged(assetId, "SELECT 1;", "x.bsql", null);

			library.archive(root.resolve("x.bsql"));
			Assertions.assertNotEquals(assetId, vault.resolveOrRegisterAssetId(rootIdentity, "x.bsql"), "archive freed the path in the vault");

			// give the fresh id no history and drop it again so the path is free, then restore
			com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary again =
					new com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary(root.toString());
			vault.archived(library.getRoot(), "x.bsql", "archives/placeholder.20260101-000000");
			again.restore("x.bsql");
			Assertions.assertEquals(assetId, vault.resolveOrRegisterAssetId(rootIdentity, "x.bsql"), "LIB RESTORE re-attached the original history");
		} finally {
			com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary.setHistoryLinkage(null);
		}
	}
}
