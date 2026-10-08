package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;

/**
 * SPRINT 0917-01 corrective pass: Save enforces catalog/metadata integrity itself (Database
 * Group/Environment validity and relation, controlled status) without requiring finished SQL, and a refused
 * Save writes nothing and records no revision.
 */
class TestScriptLibrarySaveIntegrity {

	private static final MetadataIntegrityContext CDF = new MetadataIntegrityContext(
			new TreeSet<>(Set.of("MYWORLD", "OTHER")), new TreeSet<>(Set.of("DEV", "PROD")),
			(group, environment) -> group.equalsIgnoreCase("MYWORLD") && (environment.equalsIgnoreCase("DEV") || environment.equalsIgnoreCase("PROD")));

	private ConsoleSettings settings(Path root, Path vault) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setScriptsLibraryPath(root.toString());
		settings.setScriptHistoryVaultRaw(vault.toString());
		return settings;
	}

	private ScriptAsset asset(ScriptLibraryService service, Path root, String name, String content) throws IOException, BroadSQLException {
		Files.writeString(root.resolve(name), content);
		return service.open(name);
	}

	private MetadataIntegrityException refused(ScriptLibraryService service, ScriptAsset asset, String content) {
		return Assertions.assertThrows(MetadataIntegrityException.class,
				() -> service.save(asset.assetId(), asset.relativePath(), content, null, CDF));
	}

	// ---------------- Database Group / Environment / status ----------------

	@Test
	void anUnknownDatabaseGroupIsRefused(@TempDir Path root, @TempDir Path vault) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, vault));
		ScriptAsset a = asset(service, root, "A.sql", "select 1;");

		MetadataIntegrityException ex = refused(service, a, "-- @instance: MYWROLD\nselect 1;");

		Assertions.assertEquals("Unknown Database Group 'MYWROLD'.", ex.getMessage());
		Assertions.assertEquals("instance", ex.issues().get(0).field());
	}

	@Test
	void anUnknownEnvironmentIsRefusedNamingTheGroup(@TempDir Path root, @TempDir Path vault) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, vault));
		ScriptAsset a = asset(service, root, "A.sql", "select 1;");

		MetadataIntegrityException ex = refused(service, a, "-- @instance: MYWORLD\n-- @environment: PRDO\nselect 1;");

		Assertions.assertEquals("Unknown environment 'PRDO' for Database Group 'MYWORLD'.", ex.getMessage());
		Assertions.assertEquals("environment", ex.issues().get(0).field());
	}

	@Test
	void aGroupWithoutAConnectionForTheEnvironmentIsRefused(@TempDir Path root, @TempDir Path vault) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, vault));
		ScriptAsset a = asset(service, root, "A.sql", "select 1;");

		MetadataIntegrityException ex = refused(service, a, "-- @instance: OTHER\n-- @environment: PROD\nselect 1;");

		Assertions.assertEquals("Database Group 'OTHER' has no connection for environment 'PROD'.", ex.getMessage());
	}

	@Test
	void undeclaredGroupAndEnvironmentAndTheAllScopeAreAccepted(@TempDir Path root, @TempDir Path vault) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, vault));
		ScriptAsset a = asset(service, root, "A.sql", "select 1;");

		Assertions.assertDoesNotThrow(() -> service.save(a.assetId(), a.relativePath(), "select 1;", null, CDF), "NONE (undeclared) is legal");
		Assertions.assertDoesNotThrow(() -> service.save(a.assetId(), a.relativePath(), "-- @instance: ALL\n-- @environment: ALL\nselect 1;", null, CDF));
		Assertions.assertDoesNotThrow(() -> service.save(a.assetId(), a.relativePath(), "-- @instance: MYWORLD\n-- @environment: DEV,PROD\nselect 1;", null, CDF));
	}

	@Test
	void anInvalidStatusIsRefusedAndEveryValidStatusIsAccepted(@TempDir Path root, @TempDir Path vault) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, vault));
		ScriptAsset a = asset(service, root, "A.sql", "select 1;");

		MetadataIntegrityException ex = refused(service, a, "-- @status: foo\nselect 1;");

		Assertions.assertTrue(ex.getMessage().startsWith("Invalid status 'foo'."), ex.getMessage());
		Assertions.assertEquals("status", ex.issues().get(0).field());
		for (String valid : EntryMetadata.VALID_STATUSES) {
			Assertions.assertDoesNotThrow(() -> service.save(a.assetId(), a.relativePath(), "-- @status: " + valid + "\nselect 1;", null, CDF), valid);
		}
	}

	@Test
	void unfinishedDraftSqlWithValidMetadataStillSaves(@TempDir Path root, @TempDir Path vault) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, vault));
		ScriptAsset a = asset(service, root, "A.sql", "select 1;");
		String unfinished = "-- @status: draft\nselect * from where ((( 'unterminated";

		SaveOutcome outcome = service.save(a.assetId(), a.relativePath(), unfinished, null, CDF);

		Assertions.assertTrue(outcome.revisionCreated());
		Assertions.assertEquals(unfinished, Files.readString(root.resolve("A.sql")));
	}

	@Test
	void everyIssueIsReportedTogether(@TempDir Path root, @TempDir Path vault) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, vault));
		ScriptAsset a = asset(service, root, "A.sql", "select 1;");

		MetadataIntegrityException ex = refused(service, a, "-- @instance: NOPE\n-- @status: foo\nselect 1;");

		Assertions.assertEquals(2, ex.issues().size());
	}

	@Test
	void withoutAKnownCdfOnlyTheStatusIsChecked(@TempDir Path root, @TempDir Path vault) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, vault));
		ScriptAsset a = asset(service, root, "A.sql", "select 1;");

		Assertions.assertDoesNotThrow(() -> service.save(a.assetId(), a.relativePath(), "-- @instance: ANYTHING\nselect 1;", null));
		Assertions.assertThrows(MetadataIntegrityException.class, () -> service.save(a.assetId(), a.relativePath(), "-- @status: foo\nselect 1;", null));
	}

	// ---------------- Status vocabulary / defaults ----------------

	@Test
	void theAuthoritativeStatusVocabularyIsDraftStableDeprecatedAndNewAssetsDefaultToDraft() {
		Assertions.assertEquals(java.util.List.of("draft", "stable", "deprecated"), EntryMetadata.VALID_STATUSES);
		Assertions.assertEquals("draft", EntryMetadata.DEFAULT_NEW_STATUS);
		Assertions.assertEquals("draft", EntryMetadata.parse(ScriptLibraryService.newAssetSeedContent()).getStatus());
		Assertions.assertTrue(EntryMetadata.isValidStatus("Stable"));
		Assertions.assertFalse(EntryMetadata.isValidStatus("foo"));
		Assertions.assertFalse(EntryMetadata.isValidStatus(""));
	}

	@Test
	void aNewScriptStartsAsDraft(@TempDir Path root, @TempDir Path vault) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, vault));
		ScriptAsset created = service.create("new_script", ScriptLibraryService.newAssetSeedContent());
		Assertions.assertEquals("draft", created.metadataHeader().metadata().getStatus());
		Assertions.assertEquals("new_script.bsql", created.relativePath());
	}

	@Test
	void aDuplicateCopiesTheSourceVerbatimAndAnOldAliasLineIsJustInertText(@TempDir Path root, @TempDir Path vault) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, vault));
		asset(service, root, "QR1.sql", "-- @alias: QR1\n-- @status: stable\nselect 1;");

		ScriptAsset copy = service.duplicate("QR1.sql", "QR1copy.sql");

		Assertions.assertEquals("stable", copy.metadataHeader().metadata().getStatus(), "everything is copied");
		Assertions.assertDoesNotThrow(() -> service.save(copy.assetId(), copy.relativePath(), copy.content(), null, CDF), "a leftover alias line never blocks a save");
	}
}
