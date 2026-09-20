package com.upandcoding.broadsql.dao;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.TypeDefinition;

/**
 * Covers {@link DatabaseDefinitionsVault#isDriverAvailable}/{@link DatabaseDefinitionsVault#getUnavailableTypes} -
 * the check behind marking (never hiding) a CDF {@code TYPE} whose driver class isn't loadable on this
 * JVM's classpath, in the {@code CONFIG} type dropdown and the CLI connection wizard. See
 * docs/TECHNICAL_CHANGE.md, 10/09/2026.
 */
class TestDatabaseDefinitionsVaultDriverAvailability {

	@Test
	void aTypeWhoseDriverClassIsOnTheClasspathIsReportedAvailable() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		Assertions.assertTrue(vault.isDriverAvailable("H2"), "org.h2.Driver is on the test classpath");
		Assertions.assertTrue(vault.getUnavailableTypes().isEmpty());
	}

	@Test
	void aTypeWhoseDriverClassIsNotOnTheClasspathIsReportedUnavailableButNotRemoved() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.syncTypeCatalog(List.of(new TypeDefinition("FakeType", "Fake", TypeDefinition.MODE_DEFAULT, "com.example.NoSuchDriver", DatabaseDefinition.STATUS_ACTIVE)));
		vault.load();

		Assertions.assertFalse(vault.isDriverAvailable("FakeType"));
		Assertions.assertTrue(vault.getDbTypes().contains("FakeType"), "an unavailable type must still exist in the type list, only marked");
		Assertions.assertEquals(Set.of("FakeType"), vault.getUnavailableTypes());
	}

	@Test
	void twoTypesSharingTheSameDriverClassAreBothReportedAvailableOrNot() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.syncTypeCatalog(List.of(
				new TypeDefinition("Informix", "IBM Informix", TypeDefinition.MODE_DEFAULT, "com.informix.jdbc.IfxDriver", DatabaseDefinition.STATUS_ACTIVE),
				new TypeDefinition("IDS Server", "IBM Informix Dynamic Server", TypeDefinition.MODE_DEFAULT, "com.informix.jdbc.IfxDriver", DatabaseDefinition.STATUS_ACTIVE)));
		vault.load();

		Assertions.assertFalse(vault.isDriverAvailable("Informix"), "the Informix driver jar is not on the test classpath");
		Assertions.assertFalse(vault.isDriverAvailable("IDS Server"), "IDS Server shares Informix's driver class, so it must be unavailable too");
	}

	@Test
	void anUnknownTypeIdIsReportedUnavailable() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		Assertions.assertFalse(vault.isDriverAvailable("DoesNotExist"), "an unknown type id has no driver to probe");
	}
}
