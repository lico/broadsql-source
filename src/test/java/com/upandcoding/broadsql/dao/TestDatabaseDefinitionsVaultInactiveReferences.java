package com.upandcoding.broadsql.dao;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.DatabaseGroupDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/** #6: a Connection cannot be saved with a reference to an already-inactive Database Group or Environment. */
class TestDatabaseDefinitionsVaultInactiveReferences {

	private DatabaseDefinition connection(String id, String group, String environment) {
		DatabaseDefinition c = new DatabaseDefinition(id, "H2", "org.h2.Driver", "jdbc:h2:mem:" + id, "sa", "sa", id);
		c.setStatus(DatabaseDefinition.STATUS_ACTIVE);
		c.setDatabaseGroup(group);
		c.setEnvironment(environment);
		return c;
	}

	private DatabaseDefinitionsVault vault() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveGroup(new DatabaseGroupDefinition("ACT", "active group", null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveGroup(new DatabaseGroupDefinition("OLD", "inactive group", null, DatabaseDefinition.STATUS_INACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("QA", "active env", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("OLDENV", "inactive env", false, null, DatabaseDefinition.STATUS_INACTIVE));
		return vault;
	}

	@Test
	void acceptsActiveGroupAndActiveEnvironment() throws BroadSQLException {
		DatabaseDefinitionsVault vault = vault();
		vault.saveDatabaseDefinition(connection("C1", "ACT", "QA"));
		vault.load();
		Assertions.assertTrue(vault.contains("C1"));
	}

	@Test
	void rejectsInactiveGroup() throws BroadSQLException {
		DatabaseDefinitionsVault vault = vault();
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.saveDatabaseDefinition(connection("C2", "OLD", "QA")));
		Assertions.assertTrue(ex.getMessage().contains("Database Group 'OLD' is inactive"), ex.getMessage());
		Assertions.assertFalse(vault.contains("C2"));
	}

	@Test
	void rejectsInactiveEnvironment() throws BroadSQLException {
		DatabaseDefinitionsVault vault = vault();
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.saveDatabaseDefinition(connection("C3", "ACT", "OLDENV")));
		Assertions.assertTrue(ex.getMessage().contains("Environment 'OLDENV' is inactive"), ex.getMessage());
		Assertions.assertFalse(vault.contains("C3"));
	}

	@Test
	void unchangedResaveAndStandaloneConnectionsStillWork() throws BroadSQLException {
		DatabaseDefinitionsVault vault = vault();
		vault.saveDatabaseDefinition(connection("C4", "ACT", "QA"));
		vault.load();
		vault.saveDatabaseDefinition(connection("C4", "ACT", "QA"));
		vault.saveDatabaseDefinition(connection("C5", null, "QA"));
		vault.load();
		Assertions.assertTrue(vault.contains("C4"));
		Assertions.assertTrue(vault.contains("C5"));
	}
}
