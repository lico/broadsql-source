package com.upandcoding.broadsql.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.DatabaseGroupDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/**
 * Covers the {@code INSTANCE} CRUD methods backing the "Database Groups" tab:
 * {@link DatabaseDefinitionsVault#saveGroup(DatabaseGroupDefinition, String)}, {@link
 * DatabaseDefinitionsVault#getGroupDetails}, {@link DatabaseDefinitionsVault#softDeleteGroup}
 * and the delete guard ({@link DatabaseDefinitionsVault#getActiveConnectionIdsForGroup}) - against
 * a real, file-backed, AES-encrypted CDF ({@link TestDatabaseConnections#newFileBackedVault}), not a
 * mock, per docs/TESTS_STRATEGY.md.
 *
 * <p>Phase 1 of the {@code docs/CONNECTION_MODEL.md} rework: renamed from
 * {@code TestDatabaseDefinitionsVaultInstances} (itself Phase 0's replacement of {@code
 * TestDatabaseDefinitionsVaultEnvironments}, which covered this exact CRUD lifecycle against the
 * wrong table - {@code ENVIRONMENT} instead of {@code INSTANCE}). {@code ENVIRONMENT} now has its own
 * full CRUD lifecycle too, covered separately by {@code TestDatabaseDefinitionsVaultEnvironments}
 * (the name freed up by this rename); the {@code LANDSCAPE}/{@code ENVIRONMENT} schema migration test
 * remains under {@code TestDatabaseDefinitionsVaultEnvironmentMigration} - a genuinely separate
 * concern.
 *
 * <p>Tests that create a {@code CONNECTIONS} row referencing a group additionally call
 * {@link #enforceRealGroupForeignKey}, which adds the same {@code INSTANCE_ID} FK the real CDF
 * schema declares (archives/dbTestScript.sql) - {@link TestDatabaseConnections#newFileBackedVault}
 * does not, to avoid rippling into every other test using that shared fixture. Without this, a
 * rename-ordering bug that violates the real, enforced constraint would pass here silently, as one
 * did on 03/09/2026 - see {@link DatabaseDefinitionsVault#saveGroup(DatabaseGroupDefinition, String)}'s
 * javadoc for why {@code NOCHECK} does not mean "unenforced".
 */
class TestDatabaseDefinitionsVaultGroups {

	@Test
	void savesANewGroupAndReloadsTheActiveGroupsList() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE));

		Assertions.assertTrue(vault.getGroups().contains("TAT"), "a freshly saved active group must appear in the combo-box list immediately");
		List<DatabaseGroupDefinition> details = vault.getGroupDetails();
		Assertions.assertEquals(1, details.size());
		Assertions.assertEquals("Product TAT", details.get(0).getDescr());
	}

	@Test
	void updatesAnExistingGroupInPlaceInsteadOfDuplicatingIt() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE));

		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT (renamed)", null, DatabaseDefinition.STATUS_ACTIVE));

		List<DatabaseGroupDefinition> details = vault.getGroupDetails();
		Assertions.assertEquals(1, details.size(), "saving an existing ID must update the row, not insert a duplicate");
		Assertions.assertEquals("Product TAT (renamed)", details.get(0).getDescr());
	}

	@Test
	void softDeletesAnUnreferencedGroup() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE));

		vault.softDeleteGroup("TAT");

		Assertions.assertFalse(vault.getGroups().contains("TAT"), "a soft-deleted group must drop out of the active combo-box list");
		DatabaseGroupDefinition reloaded = vault.getGroupDetails().stream().filter(l -> "TAT".equals(l.getId())).findFirst().orElseThrow();
		Assertions.assertEquals(DatabaseDefinition.STATUS_INACTIVE, reloaded.getStatusId(), "must be a soft delete (status flip), never a real DELETE");
	}

	@Test
	void refusesToDeleteAGroupStillReferencedByAnActiveConnection() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		enforceRealGroupForeignKey(vault);
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE));

		DatabaseDefinition connection = new DatabaseDefinition("TATV3", "H2", "org.h2.Driver", "jdbc:h2:mem:tatv3", "sa", "sa", "TAT (QA)");
		connection.setDatabaseGroup("TAT");
		connection.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(connection);

		Assertions.assertEquals(List.of("TATV3"), vault.getActiveConnectionIdsForGroup("TAT"));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.softDeleteGroup("TAT"));
		Assertions.assertTrue(ex.getMessage().contains("TATV3"), "the error must name the referencing connection so the user can act on it");

		DatabaseGroupDefinition stillActive = vault.getGroupDetails().stream().filter(l -> "TAT".equals(l.getId())).findFirst().orElseThrow();
		Assertions.assertEquals(DatabaseDefinition.STATUS_ACTIVE, stillActive.getStatusId(), "the refused delete must not have changed anything");
	}

	/**
	 * Tightened 03/09/2026 at the user's explicit request (rule 4 of the connections-activation
	 * discussion): a group can no longer be deactivated while <b>any</b> connection references it,
	 * active or inactive - an inactive connection keeps its {@code INSTANCE_ID} and remains viewable
	 * and reactivable from the config screen, so leaving it pointed at a deactivated group would
	 * silently break it the moment it's reactivated. Deactivating the referencing connection is no
	 * longer enough by itself; see {@link #deletionIsAllowedOnceTheReferencingConnectionIsHardDeleted}
	 * for what does free the group.
	 */
	@Test
	void deletionRemainsRefusedEvenAfterTheReferencingConnectionIsDeactivated() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		enforceRealGroupForeignKey(vault);
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE));
		DatabaseDefinition connection = new DatabaseDefinition("TATV3", "H2", "org.h2.Driver", "jdbc:h2:mem:tatv3", "sa", "sa", "TAT (QA)");
		connection.setDatabaseGroup("TAT");
		connection.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(connection);

		vault.softDeleteDatabaseDefinition("TATV3");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.softDeleteGroup("TAT"));
		Assertions.assertTrue(ex.getMessage().contains("TATV3"), "an inactive connection must still block group deactivation");
		Assertions.assertTrue(vault.getGroups().contains("TAT"));
	}

	@Test
	void deletionIsAllowedOnceTheReferencingConnectionIsHardDeleted() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		enforceRealGroupForeignKey(vault);
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE));
		DatabaseDefinition connection = new DatabaseDefinition("TATV3", "H2", "org.h2.Driver", "jdbc:h2:mem:tatv3", "sa", "sa", "TAT (QA)");
		connection.setDatabaseGroup("TAT");
		connection.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(connection);

		vault.softDeleteDatabaseDefinition("TATV3");
		vault.deleteDatabaseDefinition("TATV3");
		vault.softDeleteGroup("TAT");

		Assertions.assertFalse(vault.getGroups().contains("TAT"));
	}

	@Test
	void renamingAGroupIdPropagatesToEveryConnectionRegardlessOfStatus() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		enforceRealGroupForeignKey(vault);
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("QA", "QA", false, null, DatabaseDefinition.STATUS_ACTIVE));

		// Two connections in the same group must use distinct environments - (Database Group, Environment)
		// uniqueness (docs/CONNECTION_MODEL.md) - regardless of this test's actual focus (renaming).
		DatabaseDefinition activeConnection = new DatabaseDefinition("TATV3", "H2", "org.h2.Driver", "jdbc:h2:mem:tatv3", "sa", "sa", "TAT (QA)");
		activeConnection.setDatabaseGroup("TAT");
		activeConnection.setEnvironment("QA");
		vault.saveDatabaseDefinition(activeConnection);

		DatabaseDefinition retiredConnection = new DatabaseDefinition("OLDTAT", "H2", "org.h2.Driver", "jdbc:h2:mem:oldtat", "sa", "sa", "Old TAT");
		retiredConnection.setDatabaseGroup("TAT");
		retiredConnection.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(retiredConnection);
		vault.softDeleteDatabaseDefinition("OLDTAT");

		vault.saveGroup(new DatabaseGroupDefinition("TATPRODUCT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE), "TAT");

		Assertions.assertTrue(vault.getGroups().contains("TATPRODUCT"), "the renamed ID must be the one seen going forward");
		Assertions.assertFalse(vault.getGroups().contains("TAT"), "the old ID must be gone, not left behind as a second row");
		Assertions.assertEquals(1, vault.getGroupDetails().size(), "a rename must not leave a stray second INSTANCE row");
		Assertions.assertEquals("TATPRODUCT", groupIdOfConnection(vault, "TATV3"));
		Assertions.assertEquals("TATPRODUCT", groupIdOfConnection(vault, "OLDTAT"), "an inactive connection must be repointed too, not just active ones");
	}

	@Test
	void refusesToRenameToAnIdThatAlreadyExists() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveGroup(new DatabaseGroupDefinition("CP", "Product CP", null, DatabaseDefinition.STATUS_ACTIVE));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> vault.saveGroup(new DatabaseGroupDefinition("CP", "renamed onto an existing ID", null, DatabaseDefinition.STATUS_ACTIVE), "TAT"));
		Assertions.assertTrue(ex.getMessage().contains("CP"));

		Assertions.assertTrue(vault.getGroups().contains("TAT"), "the refused rename must not have touched the source row");
		DatabaseGroupDefinition untouchedCp = vault.getGroupDetails().stream().filter(l -> "CP".equals(l.getId())).findFirst().orElseThrow();
		Assertions.assertEquals("Product CP", untouchedCp.getDescr(), "the refused rename must not have touched the colliding target row either");
	}

	@Test
	void refusesToSaveAGroupAsInactiveWhileActiveConnectionsStillReferenceIt() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		enforceRealGroupForeignKey(vault);
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE));
		DatabaseDefinition connection = new DatabaseDefinition("TATV3", "H2", "org.h2.Driver", "jdbc:h2:mem:tatv3", "sa", "sa", "TAT (QA)");
		connection.setDatabaseGroup("TAT");
		connection.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(connection);

		// Same effect as unchecking "Active" and clicking Save in JDatabaseGroupsPanel, not Delete - must
		// be refused exactly like softDeleteGroup() already refuses, not silently allowed through.
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_INACTIVE), "TAT"));
		Assertions.assertTrue(ex.getMessage().contains("TATV3"), "the error must name the referencing connection so the user can act on it");

		DatabaseGroupDefinition stillActive = vault.getGroupDetails().stream().filter(l -> "TAT".equals(l.getId())).findFirst().orElseThrow();
		Assertions.assertEquals(DatabaseDefinition.STATUS_ACTIVE, stillActive.getStatusId(), "the refused save must not have changed anything");
	}

	@Test
	void allowsSavingAGroupAsInactiveOnceNoActiveConnectionsReferenceIt() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		enforceRealGroupForeignKey(vault);
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE));

		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_INACTIVE), "TAT");

		DatabaseGroupDefinition reloaded = vault.getGroupDetails().stream().filter(l -> "TAT".equals(l.getId())).findFirst().orElseThrow();
		Assertions.assertEquals(DatabaseDefinition.STATUS_INACTIVE, reloaded.getStatusId(), "deactivating an unreferenced group must still work");
	}

	@Test
	void refusesToRenameAndDeactivateSimultaneouslyWhileActiveConnectionsReferenceTheOldId() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		enforceRealGroupForeignKey(vault);
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE));
		DatabaseDefinition connection = new DatabaseDefinition("TATV3", "H2", "org.h2.Driver", "jdbc:h2:mem:tatv3", "sa", "sa", "TAT (QA)");
		connection.setDatabaseGroup("TAT");
		connection.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(connection);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> vault.saveGroup(new DatabaseGroupDefinition("TATPRODUCT", "Product TAT", null, DatabaseDefinition.STATUS_INACTIVE), "TAT"));
		Assertions.assertTrue(ex.getMessage().contains("TATV3"));

		Assertions.assertTrue(vault.getGroups().contains("TAT"), "the refused rename+deactivate must not have touched the source row");
		Assertions.assertFalse(vault.getGroups().contains("TATPRODUCT"), "nor created the target row");
		Assertions.assertEquals("TAT", groupIdOfConnection(vault, "TATV3"), "the connection must not have been repointed either");
	}

	@Test
	void savingWithoutAPreviousIdNeverTriggersRenameSemantics() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT", null, DatabaseDefinition.STATUS_ACTIVE), null);
		vault.saveGroup(new DatabaseGroupDefinition("TAT", "Product TAT (edited)", null, DatabaseDefinition.STATUS_ACTIVE), null);

		List<DatabaseGroupDefinition> details = vault.getGroupDetails();
		Assertions.assertEquals(1, details.size());
		Assertions.assertEquals("Product TAT (edited)", details.get(0).getDescr());
	}

	/**
	 * Adds a {@code PRIMARY KEY(ID)} to {@code INSTANCE} (required by H2 before any FK can reference
	 * the column - the shared fixture omits it) and the same {@code INSTANCE_ID} FK the real CDF
	 * declares (as {@code NOCHECK}, matching the real schema exactly). See the class javadoc for why
	 * this is added per-test here instead of in the shared fixture.
	 */
	private static void enforceRealGroupForeignKey(DatabaseDefinitionsVault vault) throws SQLException {
		String connectionStr = "jdbc:h2:" + vault.getFileName() + ";CIPHER=AES";
		String aesPassword = vault.getPassword() + " " + vault.getPassword();
		try (Connection conn = DriverManager.getConnection(connectionStr, "ADMIN", aesPassword);
				Statement stmt = conn.createStatement()) {
			stmt.execute("ALTER TABLE INSTANCE ALTER COLUMN ID SET NOT NULL");
			stmt.execute("ALTER TABLE INSTANCE ADD CONSTRAINT PK_INSTANCE PRIMARY KEY (ID)");
			stmt.execute("ALTER TABLE CONNECTIONS ADD CONSTRAINT FK_CONNECTIONS_INSTANCE FOREIGN KEY (INSTANCE_ID) REFERENCES INSTANCE(ID) NOCHECK");
			conn.commit();
		}
	}

	private static String groupIdOfConnection(DatabaseDefinitionsVault vault, String connectionId) throws SQLException {
		String connectionStr = "jdbc:h2:" + vault.getFileName() + ";CIPHER=AES";
		String aesPassword = vault.getPassword() + " " + vault.getPassword();
		try (Connection conn = DriverManager.getConnection(connectionStr, "ADMIN", aesPassword);
				PreparedStatement stat = conn.prepareStatement("SELECT INSTANCE_ID FROM CONNECTIONS WHERE ID=?")) {
			stat.setString(1, connectionId);
			try (ResultSet rs = stat.executeQuery()) {
				Assertions.assertTrue(rs.next(), "connection '" + connectionId + "' must still exist");
				return rs.getString(1);
			}
		}
	}
}
