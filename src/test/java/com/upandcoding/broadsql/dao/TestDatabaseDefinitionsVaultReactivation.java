package com.upandcoding.broadsql.dao;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Covers the connection activation-state additions made for the "manage inactive connections"
 * feature discussed with the user (docs/TECHNICAL_CHANGE.md, 03/09/2026): {@link
 * DatabaseDefinitionsVault#isInactiveConnection}, {@link DatabaseDefinitionsVault#getInactiveConnectionDetails},
 * {@link DatabaseDefinitionsVault#reactivateDatabaseDefinition} and the new hard-delete gate in
 * {@link DatabaseDefinitionsVault#deleteDatabaseDefinition} - against a real, file-backed CDF (see
 * {@link TestDatabaseConnections#newFileBackedVault}), not a mock, per docs/TESTS_STRATEGY.md.
 */
class TestDatabaseDefinitionsVaultReactivation {

	private static DatabaseDefinition newConnection(String id) {
		DatabaseDefinition connection = new DatabaseDefinition(id, "H2", "org.h2.Driver", "jdbc:h2:mem:" + id.toLowerCase(), "sa", "sa", id + " database");
		connection.setEnvironment("LOCAL");
		return connection;
	}

	@Test
	void isInactiveConnectionDistinguishesInactiveFromUnknown() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveDatabaseDefinition(newConnection("MYDB01"));

		Assertions.assertFalse(vault.isInactiveConnection("MYDB01"), "an active connection is not inactive");
		Assertions.assertFalse(vault.isInactiveConnection("GHOST"), "an unknown ID is not inactive either");

		vault.softDeleteDatabaseDefinition("MYDB01");

		Assertions.assertTrue(vault.isInactiveConnection("MYDB01"));
		Assertions.assertFalse(vault.contains("MYDB01"), "an inactive connection must not appear as active");
	}

	@Test
	void getInactiveConnectionDetailsReturnsFullDetailNotJustTheId() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		DatabaseDefinition connection = newConnection("MYDB01");
		connection.setComment("some comment");
		vault.saveDatabaseDefinition(connection);
		vault.softDeleteDatabaseDefinition("MYDB01");

		List<DatabaseDefinition> inactive = vault.getInactiveConnectionDetails();

		Assertions.assertEquals(1, inactive.size());
		DatabaseDefinition found = inactive.get(0);
		Assertions.assertEquals("MYDB01", found.getId());
		Assertions.assertEquals("H2", found.getDbType());
		Assertions.assertEquals("MYDB01 database", found.getDbName());
		Assertions.assertEquals("some comment", found.getComment());
		Assertions.assertEquals(DatabaseDefinition.STATUS_INACTIVE, found.getStatus());
	}

	@Test
	void reactivateRestoresTheConnectionUnchangedOtherwise() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		DatabaseDefinition connection = newConnection("MYDB01");
		connection.setComment("original comment");
		vault.saveDatabaseDefinition(connection);
		vault.softDeleteDatabaseDefinition("MYDB01");

		vault.reactivateDatabaseDefinition("MYDB01");

		Assertions.assertTrue(vault.contains("MYDB01"), "must be visible as active again");
		Assertions.assertFalse(vault.isInactiveConnection("MYDB01"));
		DatabaseDefinition reactivated = vault.getDatabaseConnection("MYDB01");
		Assertions.assertEquals("original comment", reactivated.getComment(), "reactivation must not change any other field");
	}

	@Test
	void hardDeleteRefusesAnActiveConnection() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveDatabaseDefinition(newConnection("MYDB01"));
		vault.load(); // saveDatabaseDefinition() does not reload the vault itself (every production
		// caller does this immediately afterward - see CommandUtils#addOrEditPlatform).

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.deleteDatabaseDefinition("MYDB01"));
		Assertions.assertTrue(ex.getMessage().contains("active"), "got: " + ex.getMessage());
		Assertions.assertTrue(vault.contains("MYDB01"), "the refused hard delete must not have touched the connection");
	}

	@Test
	void hardDeleteRefusesAnUnknownId() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		Assertions.assertThrows(BroadSQLException.class, () -> vault.deleteDatabaseDefinition("GHOST"));
	}

	@Test
	void hardDeleteRemovesAnInactiveConnectionOutright() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveDatabaseDefinition(newConnection("MYDB01"));
		vault.softDeleteDatabaseDefinition("MYDB01");

		vault.deleteDatabaseDefinition("MYDB01");

		Assertions.assertFalse(vault.isInactiveConnection("MYDB01"), "must be gone outright, not just still inactive");
		Assertions.assertFalse(vault.contains("MYDB01"));
	}
}
