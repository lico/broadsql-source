package com.upandcoding.broadsql.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/**
 * Covers the {@code ENVIRONMENT} CRUD methods backing the "Environments" tab
 * (docs/CONNECTION_MODEL.md §5): {@link DatabaseDefinitionsVault#saveEnvironment},
 * {@link DatabaseDefinitionsVault#getEnvironmentDetails}, {@link DatabaseDefinitionsVault#softDeleteEnvironment}
 * and its guards - against a real, file-backed, AES-encrypted CDF
 * ({@link TestDatabaseConnections#newFileBackedVault}), not a mock, per docs/TESTS_STRATEGY.md.
 *
 * <p>Phase 1 of the {@code docs/CONNECTION_MODEL.md} rework: {@code ENVIRONMENT} gets a full CRUD
 * lifecycle for the first time - before this, it only had the simple, insert-only read path covered
 * by {@code TestDatabaseDefinitionsVaultEnvironmentMigration} (a different, narrower concern: the
 * {@code LANDSCAPE}/{@code ENVIRONMENT} table-rename migration, unrelated to this CRUD lifecycle).
 *
 * <p>Tests that create a {@code CONNECTIONS} row referencing an environment additionally call
 * {@link #enforceRealEnvironmentForeignKey}, mirroring
 * {@code TestDatabaseDefinitionsVaultGroups#enforceRealGroupForeignKey} - see that class's javadoc for
 * why this is added per-test here instead of in the shared fixture.
 */
class TestDatabaseDefinitionsVaultEnvironments {

	@Test
	void savesANewEnvironmentAndReloadsTheActiveEnvironmentsList() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production", true, "prod comment", DatabaseDefinition.STATUS_ACTIVE));

		Assertions.assertTrue(vault.getEnvironments().contains("PR"), "a freshly saved active environment must appear in the combo-box list immediately");
		// Also expects the built-in LOCAL environment, auto-seeded on every CDF (docs/CONNECTION_MODEL.md).
		List<EnvironmentDefinition> details = vault.getEnvironmentDetails();
		EnvironmentDefinition pr = details.stream().filter(e -> "PR".equals(e.getId())).findFirst().orElseThrow();
		Assertions.assertEquals("Production", pr.getDescr());
		Assertions.assertTrue(pr.isProduction());
		Assertions.assertEquals("prod comment", pr.getComment());
	}

	@Test
	void updatesAnExistingEnvironmentInPlaceInsteadOfDuplicatingIt() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));

		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production (edited)", true, "now commented", DatabaseDefinition.STATUS_ACTIVE));

		List<EnvironmentDefinition> details = vault.getEnvironmentDetails();
		Assertions.assertEquals(1, details.stream().filter(e -> "PR".equals(e.getId())).count(),
				"saving an existing ID must update the row, not insert a duplicate");
		EnvironmentDefinition pr = details.stream().filter(e -> "PR".equals(e.getId())).findFirst().orElseThrow();
		Assertions.assertEquals("Production (edited)", pr.getDescr());
		Assertions.assertEquals("now commented", pr.getComment());
	}

	@Test
	void refusesToCreateAnEnvironmentWhoseIdCollidesCaseInsensitively() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> vault.saveEnvironment(new EnvironmentDefinition("pr", "Duplicate", false, null, DatabaseDefinition.STATUS_ACTIVE)));
		Assertions.assertTrue(ex.getMessage().contains("pr"));
		Assertions.assertEquals(1, vault.getEnvironmentDetails().stream().filter(e -> "PR".equalsIgnoreCase(e.getId())).count(),
				"the colliding create must not have inserted a second row");
	}

	@Test
	void softDeletesAnUnreferencedEnvironmentWhenAnotherRemainsActive() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("QA", "Quality Assurance", false, null, DatabaseDefinition.STATUS_ACTIVE));

		vault.softDeleteEnvironment("QA");

		Assertions.assertFalse(vault.getEnvironments().contains("QA"), "a soft-deleted environment must drop out of the active combo-box list");
		EnvironmentDefinition reloaded = vault.getEnvironmentDetails().stream().filter(e -> "QA".equals(e.getId())).findFirst().orElseThrow();
		Assertions.assertEquals(DatabaseDefinition.STATUS_INACTIVE, reloaded.getStatusId(), "must be a soft delete (status flip), never a real DELETE");
	}

	/**
	 * The built-in {@code LOCAL} environment (docs/CONNECTION_MODEL.md) is auto-seeded and active on
	 * every fresh CDF, so it is already the last-remaining-active-environment case this guard exists
	 * for - no extra setup needed to reach it.
	 */
	@Test
	void refusesToDeleteTheLastActiveEnvironment() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.softDeleteEnvironment("LOCAL"));
		Assertions.assertTrue(ex.getMessage().contains("LOCAL"));
		Assertions.assertTrue(vault.getEnvironments().contains("LOCAL"), "the refused delete must not have changed anything");
	}

	@Test
	void refusesToDeleteAnEnvironmentStillReferencedByAnActiveConnection() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		enforceRealEnvironmentForeignKey(vault);
		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("QA", "Quality Assurance", false, null, DatabaseDefinition.STATUS_ACTIVE));

		DatabaseDefinition connection = new DatabaseDefinition("TATV3", "H2", "org.h2.Driver", "jdbc:h2:mem:tatv3", "sa", "sa", "TAT (QA)");
		connection.setEnvironment("QA");
		vault.saveDatabaseDefinition(connection);

		Assertions.assertEquals(List.of("TATV3"), vault.getActiveConnectionIdsForEnvironment("QA"));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.softDeleteEnvironment("QA"));
		Assertions.assertTrue(ex.getMessage().contains("TATV3"), "the error must name the referencing connection so the user can act on it");
	}

	// --- SPRINT 0911D: related connections must include inactive ones -------------------------------

	@Test
	void allConnectionsForEnvironmentIncludesBothActiveAndInactiveConnections() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		enforceRealEnvironmentForeignKey(vault);
		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));

		DatabaseDefinition activeConnection = new DatabaseDefinition("SALES_PROD", "H2", "org.h2.Driver", "jdbc:h2:mem:salesprod", "sa", "sa", "Sales PROD");
		activeConnection.setEnvironment("PR");
		vault.saveDatabaseDefinition(activeConnection);

		DatabaseDefinition retiredConnection = new DatabaseDefinition("OLD_FINANCE", "H2", "org.h2.Driver", "jdbc:h2:mem:oldfinance", "sa", "sa", "Old Finance");
		retiredConnection.setEnvironment("PR");
		vault.saveDatabaseDefinition(retiredConnection);
		vault.softDeleteDatabaseDefinition("OLD_FINANCE");

		List<DatabaseDefinition> all = vault.getAllConnectionsForEnvironment("PR");

		Assertions.assertEquals(2, all.size(), "must include both the active and the inactive connection");
		DatabaseDefinition active = all.stream().filter(c -> "SALES_PROD".equals(c.getId())).findFirst().orElseThrow();
		Assertions.assertEquals(DatabaseDefinition.STATUS_ACTIVE, active.getStatus());
		DatabaseDefinition inactive = all.stream().filter(c -> "OLD_FINANCE".equals(c.getId())).findFirst().orElseThrow();
		Assertions.assertEquals(DatabaseDefinition.STATUS_INACTIVE, inactive.getStatus());
	}

	// --- SPRINT 0911D: reactivate / hard delete ---------------------------------------------------

	@Test
	void reactivateRestoresAnInactiveEnvironmentUnchangedOtherwise() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production", true, "a comment", DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("QA", "Quality Assurance", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.softDeleteEnvironment("PR");

		vault.reactivateEnvironment("PR");

		Assertions.assertTrue(vault.getEnvironments().contains("PR"), "must be visible as active again");
		EnvironmentDefinition reactivated = vault.getEnvironmentDetails().stream().filter(e -> "PR".equals(e.getId())).findFirst().orElseThrow();
		Assertions.assertTrue(reactivated.isProduction(), "reactivation must not change any other field");
		Assertions.assertEquals("a comment", reactivated.getComment());
	}

	@Test
	void hardDeleteRefusesAnActiveEnvironment() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.hardDeleteEnvironment("PR"));
		Assertions.assertTrue(ex.getMessage().contains("active"), "got: " + ex.getMessage());
		Assertions.assertTrue(vault.getEnvironments().contains("PR"), "the refused hard delete must not have touched the environment");
	}

	@Test
	void hardDeleteRefusesAnUnknownEnvironment() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		Assertions.assertThrows(BroadSQLException.class, () -> vault.hardDeleteEnvironment("GHOST"));
	}

	@Test
	void hardDeleteRemovesAnInactiveUnreferencedEnvironmentOutright() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("QA", "Quality Assurance", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.softDeleteEnvironment("PR");

		vault.hardDeleteEnvironment("PR");

		Assertions.assertEquals(0, vault.getEnvironmentDetails().stream().filter(e -> "PR".equals(e.getId())).count(), "must be gone outright, not just still inactive");
	}

	@Test
	void hardDeleteRefusesAnInactiveEnvironmentStillReferencedByAConnectionAndReportsActiveAndInactiveCounts() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		enforceRealEnvironmentForeignKey(vault);
		vault.saveEnvironment(new EnvironmentDefinition("PR", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("QA", "Quality Assurance", false, null, DatabaseDefinition.STATUS_ACTIVE));

		// Legacy state: the environment is inactive while a connection still references it. Since #6 this cannot
		// be created by saving a connection against an inactive environment, nor by softDeleteEnvironment (it
		// refuses while referenced), so the connection is saved first and the environment then deactivated directly.
		DatabaseDefinition connection = new DatabaseDefinition("TATV3", "H2", "org.h2.Driver", "jdbc:h2:mem:tatv3", "sa", "sa", "TAT (PR)");
		connection.setEnvironment("PR");
		vault.saveDatabaseDefinition(connection);
		forceInactive(vault, "ENVIRONMENT", "PR");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.hardDeleteEnvironment("PR"));
		Assertions.assertTrue(ex.getMessage().contains("TATV3"), "got: " + ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("1 active"), "got: " + ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("0 inactive"), "got: " + ex.getMessage());
	}

	/**
	 * Marks a row inactive directly in the CDF, bypassing {@code softDelete*} (which refuses while a connection
	 * references it). This reproduces legacy data: since #6 no new reference to an inactive row can be created,
	 * but such references may still exist in vaults created before, and hard delete must keep refusing them.
	 */
	private static void forceInactive(DatabaseDefinitionsVault vault, String table, String id) throws SQLException {
		String connectionStr = "jdbc:h2:" + vault.getFileName() + ";CIPHER=AES";
		String aesPassword = vault.getPassword() + " " + vault.getPassword();
		try (Connection conn = DriverManager.getConnection(connectionStr, "ADMIN", aesPassword);
				Statement stmt = conn.createStatement()) {
			stmt.execute("UPDATE " + table + " SET STATUS_ID='" + DatabaseDefinition.STATUS_INACTIVE + "' WHERE ID='" + id + "'");
			conn.commit();
		}
	}

	/**
	 * Adds a {@code PRIMARY KEY(ID)} to {@code ENVIRONMENT} (required by H2 before any FK can
	 * reference the column - the shared fixture omits it) and the same {@code ENVIRONMENT_ID} FK the
	 * real CDF declares (as {@code NOCHECK}, matching the real schema exactly). Mirrors
	 * {@code TestDatabaseDefinitionsVaultGroups#enforceRealGroupForeignKey}.
	 */
	private static void enforceRealEnvironmentForeignKey(DatabaseDefinitionsVault vault) throws SQLException {
		String connectionStr = "jdbc:h2:" + vault.getFileName() + ";CIPHER=AES";
		String aesPassword = vault.getPassword() + " " + vault.getPassword();
		try (Connection conn = DriverManager.getConnection(connectionStr, "ADMIN", aesPassword);
				Statement stmt = conn.createStatement()) {
			stmt.execute("ALTER TABLE ENVIRONMENT ALTER COLUMN ID SET NOT NULL");
			stmt.execute("ALTER TABLE ENVIRONMENT ADD CONSTRAINT PK_ENVIRONMENT PRIMARY KEY (ID)");
			stmt.execute("ALTER TABLE CONNECTIONS ADD CONSTRAINT FK_CONNECTIONS_ENVIRONMENT FOREIGN KEY (ENVIRONMENT_ID) REFERENCES ENVIRONMENT(ID) NOCHECK");
			conn.commit();
		}
	}
}
