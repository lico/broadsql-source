package com.upandcoding.broadsql.dao;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Connections saved through the real persistence layer ({@link DatabaseDefinitionsVault#saveDatabaseDefinition})
 * into a copy of the CDF BroadSQL ships ({@code src/main/resources/release-template/conf}), whose
 * {@code CONNECTIONS.DRIVER} is {@code NOT NULL}, then reloaded by a new vault: New, Edit and Duplicate, for H2
 * and a non-H2 type. The save used to leave {@code DRIVER} out of its statements, so every new connection failed
 * with "NULL not allowed for column DRIVER"; the file-backed test fixture had no such column and hid it.
 * Passwords are compared, never printed.
 */
class TestConnectionPersistenceOnShippedCdf {

	/** The shipped CDF's documented default master password (deploy/README.txt). */
	private static final String SHIPPED_CDF_PASSWORD = "clipper8AD";
	private static final Path SHIPPED_CDF = Path.of("src", "main", "resources", "release-template", "conf", "ConnectionsDefinitionFile.cdf.mv.db");

	@TempDir
	Path dir;
	private String cdf;

	@BeforeEach
	void copyTheShippedCdf() throws Exception {
		Files.copy(SHIPPED_CDF, dir.resolve("ConnectionsDefinitionFile.cdf.mv.db"), StandardCopyOption.REPLACE_EXISTING);
		cdf = dir.resolve("ConnectionsDefinitionFile.cdf").toString();
	}

	private DatabaseDefinitionsVault vault() throws BroadSQLException {
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault(cdf, SHIPPED_CDF_PASSWORD);
		vault.load();
		return vault;
	}

	/** The DRIVER column as stored, read with plain SQL (not through the vault, which reads the type's driver). */
	private String storedDriver(String id) throws Exception {
		try (Connection conn = DriverManager.getConnection("jdbc:h2:" + cdf + ";CIPHER=AES", DatabaseDefinitionsVault.H2_ADMIN,
				SHIPPED_CDF_PASSWORD + " " + SHIPPED_CDF_PASSWORD);
				PreparedStatement stat = conn.prepareStatement("SELECT DRIVER FROM CONNECTIONS WHERE ID=?")) {
			stat.setString(1, id);
			try (ResultSet rs = stat.executeQuery()) {
				Assertions.assertTrue(rs.next(), "no row for " + id);
				return rs.getString(1);
			}
		}
	}

	private static DatabaseDefinition connection(String id, String type, String driver, String url, String password, String environment) {
		DatabaseDefinition c = new DatabaseDefinition(id, type, driver, url, "sa", password, id + " name");
		c.setEnvironment(environment);
		c.setComment("comment of " + id);
		c.setStatus(DatabaseDefinition.STATUS_ACTIVE);
		return c;
	}

	private static void assertSurvives(DatabaseDefinition saved, DatabaseDefinition reloaded, String expectedDriver) {
		Assertions.assertNotNull(reloaded, saved.getId() + " was not reloaded");
		Assertions.assertEquals(saved.getId(), reloaded.getId());
		Assertions.assertEquals(saved.getDbName(), reloaded.getDbName());
		Assertions.assertEquals(saved.getUrl(), reloaded.getUrl());
		Assertions.assertEquals(saved.getDbType(), reloaded.getDbType());
		Assertions.assertEquals(expectedDriver, reloaded.getDbDriver());
		Assertions.assertEquals(saved.getUserName(), reloaded.getUserName());
		Assertions.assertTrue(java.util.Objects.equals(saved.getUserPassword(), reloaded.getUserPassword())
				|| (saved.getUserPassword().isEmpty() && reloaded.getUserPassword() == null), "password differs after reload");
		Assertions.assertEquals(saved.getDatabaseGroup(), reloaded.getDatabaseGroup());
		Assertions.assertEquals(saved.getEnvironment(), reloaded.getEnvironment());
		Assertions.assertEquals(saved.getComment(), reloaded.getComment());
		Assertions.assertEquals(DatabaseDefinition.STATUS_ACTIVE, reloaded.getStatus());
	}

	@Test
	void aNewH2ConnectionIsSavedWithItsDriverAndReloadsIdentically() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		String local = vault.resolveLocalEnvironmentId();
		DatabaseDefinition world = connection("WORLD", "H2", "org.h2.Driver", "jdbc:h2:./samples/WorldDB", "", local);

		vault.saveDatabaseDefinition(world);

		Assertions.assertEquals("org.h2.Driver", storedDriver("WORLD"));
		assertSurvives(world, vault().getDatabaseConnection("WORLD"), "org.h2.Driver");
	}

	@Test
	void aNewNonH2ConnectionIsSavedWithItsTypesDriver() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition pg = connection("PGSAMPLE", "PostgreSQL", "org.postgresql.Driver", "jdbc:postgresql://localhost:5432/sample", "secret",
				vault.resolveLocalEnvironmentId());

		vault.saveDatabaseDefinition(pg);

		Assertions.assertEquals("org.postgresql.Driver", storedDriver("PGSAMPLE"));
		assertSurvives(pg, vault().getDatabaseConnection("PGSAMPLE"), "org.postgresql.Driver");
	}

	@Test
	void aConnectionCreatedWithoutADriverGetsItsTypesDriver() throws Exception {
		// ADD CONNECTION at the prompt and DUMP ... AS H2 build the definition without a driver
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition noDriver = connection("NODRIVER", "H2", null, "jdbc:h2:mem:nodriver", "", vault.resolveLocalEnvironmentId());

		vault.saveDatabaseDefinition(noDriver);

		Assertions.assertEquals("org.h2.Driver", storedDriver("NODRIVER"));
		Assertions.assertEquals("org.h2.Driver", vault().getDatabaseConnection("NODRIVER").getDbDriver());
	}

	@Test
	void editingAConnectionKeepsItsDriverInStepWithItsType() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		vault.saveDatabaseDefinition(connection("EDITED", "H2", "org.h2.Driver", "jdbc:h2:mem:edited", "pw", vault.resolveLocalEnvironmentId()));

		DatabaseDefinitionsVault editing = vault();
		DatabaseDefinition edited = editing.getDatabaseConnection("EDITED");
		edited.setDbType("PostgreSQL"); // the driver still says org.h2.Driver, as after a type change in the wizard
		edited.setUrl("jdbc:postgresql://localhost/edited");
		edited.setDbName("Edited name");
		edited.setComment("edited comment");
		editing.saveDatabaseDefinition(edited);

		Assertions.assertEquals("org.postgresql.Driver", storedDriver("EDITED"));
		assertSurvives(edited, vault().getDatabaseConnection("EDITED"), "org.postgresql.Driver");
	}

	@Test
	void aDuplicatedConnectionIsSavedWithItsDriver() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		vault.saveDatabaseDefinition(connection("SOURCE", "H2", "org.h2.Driver", "jdbc:h2:mem:source", "pw", vault.resolveLocalEnvironmentId()));
		DatabaseDefinitionsVault duplicating = vault();
		DatabaseDefinition source = duplicating.getDatabaseConnection("SOURCE");
		// how DUPLICATE CONNECTION builds its copy (CommandUtils.duplicatePlatform)
		DatabaseDefinition copy = new DatabaseDefinition("COPY", source.getDbType(), source.getDbDriver(), source.getUrl(), source.getUserName(),
				source.getUserPassword(), source.getDbName());
		copy.setDatabaseGroup(source.getDatabaseGroup());
		copy.setEnvironment(source.getEnvironment());
		copy.setComment(source.getComment());
		copy.setStatus(DatabaseDefinition.STATUS_ACTIVE);

		duplicating.saveDatabaseDefinition(copy);

		Assertions.assertEquals("org.h2.Driver", storedDriver("COPY"));
		assertSurvives(copy, vault().getDatabaseConnection("COPY"), "org.h2.Driver");
		Assertions.assertEquals("org.h2.Driver", storedDriver("SOURCE"), "the source is untouched");
	}

	@Test
	void aTypeWithoutAnyKnownDriverIsReportedClearly() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition unknown = connection("UNKNOWNTYPE", "NoSuchType", null, "jdbc:none:x", "", vault.resolveLocalEnvironmentId());

		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> vault.saveDatabaseDefinition(unknown));

		Assertions.assertTrue(e.getMessage().contains("No JDBC driver class is known for database type 'NoSuchType'"), e.getMessage());
		Assertions.assertFalse(e.getMessage().contains("NULL not allowed"), e.getMessage());
	}
}
