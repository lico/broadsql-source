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
 * Covers the standalone-connections model (docs/CONNECTION_MODEL.md): Database Group becomes optional
 * on a Connection while Environment stays mandatory and validated, the built-in {@code LOCAL}
 * Environment is seeded automatically, and the {@code (Database Group, Environment)} uniqueness rule
 * remains strict for grouped connections while never applying to standalone ones. Against a real,
 * file-backed, AES-encrypted CDF ({@link TestDatabaseConnections#newFileBackedVault}), not a mock, per
 * docs/TESTS_STRATEGY.md - {@code DatabaseDefinitionsVault.saveDatabaseDefinition}/{@code saveEnvironment}/
 * {@code saveGroup} only have a {@code fileName}-based implementation.
 *
 * <p>Deliberately does not test any uniqueness-bypass flag - per the approved design, none exists.
 */
class TestDatabaseDefinitionsVaultStandaloneConnections {

	private static DatabaseDefinition connection(String id, String group, String environment) {
		DatabaseDefinition def = new DatabaseDefinition(id, "H2", "org.h2.Driver", "jdbc:h2:mem:" + id.toLowerCase(), "sa", "sa", id + " database");
		// Real callers (CommandUtils#addOrEditPlatform) always set this explicitly before an edit -
		// saveDatabaseDefinition's UPDATE path (unlike its INSERT path) trusts the object's own status.
		def.setStatus(DatabaseDefinition.STATUS_ACTIVE);
		def.setDatabaseGroup(group);
		def.setEnvironment(environment);
		return def;
	}

	// --- LOCAL environment seeding -------------------------------------------------------------

	@Test
	void newCdfGetsTheBuiltInLocalEnvironment() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		Assertions.assertTrue(vault.getEnvironments().contains("LOCAL"));
		EnvironmentDefinition local = vault.getEnvironmentDetails().stream().filter(e -> "LOCAL".equals(e.getId())).findFirst().orElseThrow();
		Assertions.assertFalse(local.isProduction(), "LOCAL must be non-production");
		Assertions.assertTrue(local.isActive());
	}

	@Test
	void repeatedLoadDoesNotDuplicateLocal() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		vault.load();
		vault.load();

		long localRowCount = vault.getEnvironmentDetails().stream().filter(e -> "LOCAL".equalsIgnoreCase(e.getId())).count();
		Assertions.assertEquals(1, localRowCount, "seeding must be idempotent");
	}

	@Test
	void preservesAPreExistingCaseVariantOfLocalRatherThanDuplicatingIt() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		replaceLocalRowWithCaseVariant(vault, "Local", "My own Local", "user comment");

		vault.load(); // must not insert a second, canonical-cased row alongside the pre-existing one

		List<EnvironmentDefinition> allLocalVariants = vault.getEnvironmentDetails().stream().filter(e -> "LOCAL".equalsIgnoreCase(e.getId())).toList();
		Assertions.assertEquals(1, allLocalVariants.size(), "must not duplicate the existing case variant");
		EnvironmentDefinition preserved = allLocalVariants.get(0);
		Assertions.assertEquals("Local", preserved.getId(), "the pre-existing casing must be preserved, not overwritten");
		Assertions.assertEquals("My own Local", preserved.getDescr(), "pre-existing user data must not be overwritten");
	}

	@Test
	void resolveLocalEnvironmentIdMatchesWhateverCasingIsPersisted() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		replaceLocalRowWithCaseVariant(vault, "Local", "My own Local", null);
		vault.load();

		Assertions.assertEquals("Local", vault.resolveLocalEnvironmentId(),
				"must resolve the persisted casing, never assume the literal 'LOCAL'");

		// A connection saved using the resolved ID must pass exact-match Environment validation -
		// the exact scenario docs/TECHNICAL_CHANGE.md's case-consistency fix addresses.
		DatabaseDefinition standalone = connection("STANDALONE1", null, vault.resolveLocalEnvironmentId());
		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(standalone));
	}

	private static void replaceLocalRowWithCaseVariant(DatabaseDefinitionsVault vault, String id, String descr, String comment) throws SQLException {
		String connectionStr = "jdbc:h2:" + vault.getFileName() + ";CIPHER=AES";
		String aesPassword = vault.getPassword() + " " + vault.getPassword();
		try (Connection conn = DriverManager.getConnection(connectionStr, "ADMIN", aesPassword);
				Statement stmt = conn.createStatement()) {
			stmt.execute("DELETE FROM ENVIRONMENT WHERE UPPER(ID)=UPPER('LOCAL')");
			stmt.execute("INSERT INTO ENVIRONMENT (ID, DESCR, PRODUCTION, COMMENT, STATUS_ID) VALUES ('" + id + "', '" + descr + "', FALSE, "
					+ (comment == null ? "NULL" : "'" + comment + "'") + ", 'ACTIVE')");
			conn.commit();
		}
	}

	// --- (Database Group, Environment) uniqueness matrix ---------------------------------------

	@Test
	void groupedConnectionsWithDistinctEnvironmentsAreAllValid() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault("TAT");
		vault.saveEnvironment(new EnvironmentDefinition("DEV", "Dev", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("QA", "QA", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("PR", "Prod", true, null, DatabaseDefinition.STATUS_ACTIVE));

		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("TATDEV", "TAT", "DEV")));
		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("TATQA", "TAT", "QA")));
		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("TATPR", "TAT", "PR")));
	}

	@Test
	void secondConnectionForTheSameGroupAndEnvironmentIsRejected() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault("TAT");
		vault.saveEnvironment(new EnvironmentDefinition("DEV", "Dev", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveDatabaseDefinition(connection("TATDEV1", "TAT", "DEV"));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> vault.saveDatabaseDefinition(connection("TATDEV2", "TAT", "DEV")));
		Assertions.assertTrue(ex.getMessage().contains("already has a connection for environment"));
	}

	@Test
	void multipleStandaloneConnectionsCanShareTheSameEnvironment() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("COPY1", null, "LOCAL")));
		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("COPY2", null, "LOCAL")));
		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("COPY3", null, "LOCAL")));
	}

	@Test
	void sameEnvironmentIsValidAcrossDifferentGroups() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault("TAT", "CP");
		vault.saveEnvironment(new EnvironmentDefinition("DEV", "Dev", false, null, DatabaseDefinition.STATUS_ACTIVE));

		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("TATDEV", "TAT", "DEV")));
		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("CPDEV", "CP", "DEV")));
	}

	@Test
	void localEnvironmentIsValidAcrossDifferentGroupsButNotTwiceInTheSameGroup() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault("TAT", "CP");

		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("TATLOCAL", "TAT", "LOCAL")));
		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("CPLOCAL", "CP", "LOCAL")));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> vault.saveDatabaseDefinition(connection("TATLOCAL2", "TAT", "LOCAL")));
		Assertions.assertTrue(ex.getMessage().contains("already has a connection for environment"));
	}

	// --- Environment/Group existence validation (Rules A and B) --------------------------------

	@Test
	void savingAConnectionWithAnUnknownEnvironmentIsRejected() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> vault.saveDatabaseDefinition(connection("MYDB", null, "DOES_NOT_EXIST")));
		Assertions.assertTrue(ex.getMessage().contains("does not exist"));
	}

	@Test
	void savingAConnectionWithABlankEnvironmentIsRejected() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> vault.saveDatabaseDefinition(connection("MYDB", null, null)));
		Assertions.assertTrue(ex.getMessage().contains("required"));
	}

	@Test
	void savingAConnectionWithAnUnknownDatabaseGroupIsRejected() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> vault.saveDatabaseDefinition(connection("MYDB", "DOES_NOT_EXIST", "LOCAL")));
		Assertions.assertTrue(ex.getMessage().contains("Database Group"));
		Assertions.assertTrue(ex.getMessage().contains("does not exist"));
	}

	@Test
	void aBlankDatabaseGroupIsAlwaysValidRegardlessOfWhatGroupsExist() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault("AAA", "CP", "TAT");

		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("STANDALONE", null, "LOCAL")));
	}

	// --- Editing transitions (docs/CONNECTION_MODEL.md standalone-connections model) -----------

	@Test
	void editingAGroupedConnectionToStandaloneIsAllowed() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault("TAT");
		vault.saveEnvironment(new EnvironmentDefinition("DEV", "Dev", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveDatabaseDefinition(connection("TATDEV", "TAT", "DEV"));
		vault.load();

		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("TATDEV", null, "DEV")));

		vault.load();
		Assertions.assertNull(vault.getDatabaseConnection("TATDEV").getDatabaseGroup());
	}

	@Test
	void editingAStandaloneConnectionIntoAnAvailableGroupEnvironmentPairSucceeds() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault("TAT");
		vault.saveDatabaseDefinition(connection("MYDB", null, "LOCAL"));
		vault.load();

		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("MYDB", "TAT", "LOCAL")));

		vault.load();
		Assertions.assertEquals("TAT", vault.getDatabaseConnection("MYDB").getDatabaseGroup());
	}

	@Test
	void editingAStandaloneConnectionIntoAnAlreadyOccupiedGroupEnvironmentPairIsRejected() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault("TAT");
		vault.saveDatabaseDefinition(connection("TATLOCAL", "TAT", "LOCAL"));
		vault.saveDatabaseDefinition(connection("MYDB", null, "LOCAL"));
		vault.load();

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> vault.saveDatabaseDefinition(connection("MYDB", "TAT", "LOCAL")));
		Assertions.assertTrue(ex.getMessage().contains("already has a connection for environment"));

		vault.load();
		Assertions.assertNull(vault.getDatabaseConnection("MYDB").getDatabaseGroup(), "the refused change must not have been applied");
	}

	@Test
	void changingEnvironmentOfAGroupedConnectionValidatesTheNewPair() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault("TAT");
		vault.saveEnvironment(new EnvironmentDefinition("QA", "QA", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveDatabaseDefinition(connection("TATTEST", "TAT", "LOCAL"));
		vault.load();

		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("TATTEST", "TAT", "QA")));

		vault.load();
		Assertions.assertEquals("QA", vault.getDatabaseConnection("TATTEST").getEnvironment());
	}

	@Test
	void changingEnvironmentOfAStandaloneConnectionNeverHitsTheUniquenessCheck() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveEnvironment(new EnvironmentDefinition("DEV", "Dev", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveDatabaseDefinition(connection("MYDB", null, "LOCAL"));
		vault.load();

		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(connection("MYDB", null, "DEV")));

		vault.load();
		Assertions.assertEquals("DEV", vault.getDatabaseConnection("MYDB").getEnvironment());
		Assertions.assertNull(vault.getDatabaseConnection("MYDB").getDatabaseGroup());
	}
}
