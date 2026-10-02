package com.upandcoding.broadsql.dao;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.TypeDefinition;
import com.upandcoding.broadsql.dao.model.TypeSyncResult;

/**
 * Covers {@link DatabaseDefinitionsVault#getTypeDetails}/{@link DatabaseDefinitionsVault#syncTypeCatalog},
 * the mechanics behind the hidden {@code SYNC TYPE CATALOG} command (see
 * {@code com.upandcoding.broadsql.controller.shell.commands.core.config.CommandSyncTypeCatalog} and
 * {@code docs/SUPPORTED_DATABASES.md}) - against a real, file-backed, AES-encrypted CDF
 * ({@link TestDatabaseConnections#newFileBackedVault}), not a mock, per docs/TESTS_STRATEGY.md.
 */
class TestDatabaseDefinitionsVaultTypeCatalog {

	@Test
	void migratesTypeSchemaAutomaticallyOnLoadSoThePreExistingSeedRowIsReadableWithTheNewColumns() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		List<TypeDefinition> details = vault.getTypeDetails();

		Assertions.assertEquals(1, details.size(), "the fixture seeds exactly one TYPE row ('H2')");
		Assertions.assertEquals("H2", details.get(0).getId());
		Assertions.assertEquals("org.h2.Driver", details.get(0).getDriver());
		Assertions.assertNull(details.get(0).getMode(), "MODE is a brand-new column - the pre-existing row has no value for it yet");
		Assertions.assertNull(details.get(0).getDescr(), "DESCR is a brand-new column - the pre-existing row has no value for it yet");
	}

	@Test
	void syncTypeCatalogAddsMissingRowsAndReportsThem() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		List<TypeDefinition> recognized = List.of(
				new TypeDefinition("H2", "H2 Database Engine", TypeDefinition.MODE_DEFAULT, "org.h2.Driver", DatabaseDefinition.STATUS_ACTIVE),
				new TypeDefinition("PostgreSQL", "PostgreSQL", TypeDefinition.MODE_DEFAULT, "org.postgresql.Driver", DatabaseDefinition.STATUS_ACTIVE),
				new TypeDefinition("DERBY Embedded", "Apache Derby (embedded)", "Embedded", "org.apache.derby.jdbc.EmbeddedDriver", DatabaseDefinition.STATUS_ACTIVE));

		TypeSyncResult result = vault.syncTypeCatalog(recognized);

		Assertions.assertEquals(List.of("PostgreSQL", "DERBY Embedded"), result.getAdded());
		Assertions.assertEquals(List.of("H2"), result.getUpdated());
		List<TypeDefinition> details = vault.getTypeDetails();
		Assertions.assertEquals(3, details.size());
	}

	@Test
	void syncTypeCatalogRefreshesAnExistingRowInPlaceRatherThanDuplicatingIt() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		TypeSyncResult result = vault.syncTypeCatalog(
				List.of(new TypeDefinition("H2", "H2 Database Engine (refreshed)", TypeDefinition.MODE_DEFAULT, "org.h2.Driver", DatabaseDefinition.STATUS_ACTIVE)));

		Assertions.assertTrue(result.getAdded().isEmpty());
		Assertions.assertEquals(List.of("H2"), result.getUpdated());
		List<TypeDefinition> details = vault.getTypeDetails();
		Assertions.assertEquals(1, details.size(), "refreshing an existing ID must update the row, not insert a duplicate");
		Assertions.assertEquals("H2 Database Engine (refreshed)", details.get(0).getDescr());
		Assertions.assertEquals(TypeDefinition.MODE_DEFAULT, details.get(0).getMode());
	}

	@Test
	void syncTypeCatalogTruncatesAnOverlongDescriptionInsteadOfFailing() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		String overlong = "A".repeat(200);

		vault.syncTypeCatalog(List.of(new TypeDefinition("PostgreSQL", overlong, TypeDefinition.MODE_DEFAULT, "org.postgresql.Driver", DatabaseDefinition.STATUS_ACTIVE)));

		TypeDefinition postgres = vault.getTypeDetails().stream().filter(t -> "PostgreSQL".equals(t.getId())).findFirst().orElseThrow();
		Assertions.assertEquals(80, postgres.getDescr().length(), "a description longer than the DESCR column can hold must be truncated, not blow up the whole sync");
	}

	@Test
	void syncTypeCatalogNeverTouchesARowThatIsNotInTheRecognizedList() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		vault.syncTypeCatalog(List.of(new TypeDefinition("PostgreSQL", "PostgreSQL", TypeDefinition.MODE_DEFAULT, "org.postgresql.Driver", DatabaseDefinition.STATUS_ACTIVE)));

		List<TypeDefinition> details = vault.getTypeDetails();
		Assertions.assertEquals(2, details.size());
		Assertions.assertTrue(details.stream().anyMatch(t -> "H2".equals(t.getId())), "the pre-existing 'H2' row (not in the recognized list passed here) must be left alone");
	}

	@Test
	void syncTypeCatalogNeverCreatesARowForAnEntryWithNoKnownDriver() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		TypeSyncResult result = vault.syncTypeCatalog(
				List.of(new TypeDefinition("Intersys", "InterSystems IRIS", TypeDefinition.MODE_DEFAULT, null, DatabaseDefinition.STATUS_ACTIVE)));

		Assertions.assertEquals(List.of("Intersys"), result.getSkippedNoDriver());
		Assertions.assertTrue(result.getAdded().isEmpty(), "an entry with no known driver must never be created");
		Assertions.assertTrue(vault.getTypeDetails().stream().noneMatch(t -> "Intersys".equals(t.getId())),
				"'Intersys' must not exist in TYPE at all - it has no known driver class");
	}

	@Test
	void syncTypeCatalogLeavesAnAlreadyExistingNoDriverRowUntouchedRatherThanUpdatingIt() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		// Simulates a row created by an earlier version of this command, before the "no driver, no row"
		// rule existed - see docs/TECHNICAL_CHANGE.md, 10/09/2026.
		vault.syncTypeCatalog(List.of(new TypeDefinition("Cloudscape", "Old description", TypeDefinition.MODE_DEFAULT, "com.example.OldDriver", DatabaseDefinition.STATUS_ACTIVE)));

		TypeSyncResult result = vault.syncTypeCatalog(
				List.of(new TypeDefinition("Cloudscape", "Cloudscape (discontinued)", TypeDefinition.MODE_DEFAULT, null, DatabaseDefinition.STATUS_ACTIVE)));

		Assertions.assertEquals(List.of("Cloudscape"), result.getSkippedNoDriver());
		Assertions.assertTrue(result.getUpdated().isEmpty(), "a no-driver entry must not be refreshed even if a row already exists under its ID");
		TypeDefinition cloudscape = vault.getTypeDetails().stream().filter(t -> "Cloudscape".equals(t.getId())).findFirst().orElseThrow();
		Assertions.assertEquals("Old description", cloudscape.getDescr(), "the pre-existing row must be left exactly as it was");
		Assertions.assertEquals("com.example.OldDriver", cloudscape.getDriver());
	}
}
