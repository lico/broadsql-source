package com.upandcoding.broadsql.dao;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Covers {@link DatabaseDefinitionsVault#migrateEnvironmentSchemaIfNeeded} - added 04/09/2026 with the
 * {@code LANDSCAPE}/{@code ENVIRONMENT} terminology correction (docs/TECHNICAL_CHANGE.md, same date).
 * Every CDF created before that fix physically has the old table/column names on disk; this builds one
 * by hand (old names, real FK, a seeded row and a connection referencing it - the shape
 * {@code archives/dbTestScript.sql} shows a real CDF has) and asserts a plain {@code load()} against it
 * transparently migrates and reads back correctly, with no separate migration step for the caller to
 * remember to run.
 *
 * <p>Split out of a since-renamed {@code TestDatabaseDefinitionsVaultEnvironments} during Phase 0 of
 * the {@code docs/CONNECTION_MODEL.md} rework (docs/TECHNICAL_CHANGE.md, 2026-09-05): that file's CRUD
 * coverage moved to what is now {@code TestDatabaseDefinitionsVaultGroups} (it was exercising the
 * wrong table), but this {@code LANDSCAPE}->{@code ENVIRONMENT} migration is a genuinely separate,
 * still-current feature of the {@code ENVIRONMENT} table itself, which that move left untouched.
 * {@code ENVIRONMENT}'s own full CRUD lifecycle was added later, in Phase 1, and is covered by the
 * current {@code TestDatabaseDefinitionsVaultEnvironments} (the class name freed up by the Phase 0
 * rename above) - unrelated to this schema-migration test.
 */
class TestDatabaseDefinitionsVaultEnvironmentMigration {

	@Test
	void loadingAPreExistingCdfWithTheOldLandscapeSchemaMigratesItTransparently() throws BroadSQLException, java.sql.SQLException {
		String uniqueName = "cdf_" + UUID.randomUUID().toString().replace("-", "");
		String path = System.getProperty("java.io.tmpdir") + File.separator + uniqueName;
		String password = "testpwd";
		String connectionStr = "jdbc:h2:" + path + ";CIPHER=AES";
		String aesPassword = password + " " + password;

		try (Connection conn = DriverManager.getConnection(connectionStr, "ADMIN", aesPassword)) {
			try (Statement stmt = conn.createStatement()) {
				stmt.execute("CREATE TABLE TYPE (ID VARCHAR(20), DRIVER VARCHAR(80), STATUS_ID VARCHAR(10))");
				stmt.execute("CREATE TABLE CONNECTIONS (ID VARCHAR(15), URL VARCHAR(2048), TYPE_ID VARCHAR(20), "
						+ "STATUS_ID VARCHAR(10), NAME VARCHAR(255), LANDSCAPE_ID VARCHAR(30), INSTANCE_ID VARCHAR(30), "
						+ "USER_NAME VARCHAR(80), USER_PASSWORD VARCHAR(80), COMMENT VARCHAR(255))");
				stmt.execute("CREATE TABLE INSTANCE (ID VARCHAR(30), DESCR VARCHAR(80), STATUS_ID VARCHAR(10))");
				stmt.execute("CREATE TABLE LANDSCAPE (ID VARCHAR(30) NOT NULL, DESCR VARCHAR(80), STATUS_ID VARCHAR(10))");
				stmt.execute("ALTER TABLE LANDSCAPE ADD CONSTRAINT PK_LANDSCAPE PRIMARY KEY (ID)");
				stmt.execute("ALTER TABLE CONNECTIONS ADD CONSTRAINT FK_CONNECTIONS_LANDSCAPE FOREIGN KEY (LANDSCAPE_ID) REFERENCES LANDSCAPE(ID) NOCHECK");
				stmt.execute("INSERT INTO TYPE (ID, DRIVER, STATUS_ID) VALUES ('H2', 'org.h2.Driver', 'ACTIVE')");
				stmt.execute("INSERT INTO LANDSCAPE (ID, DESCR, STATUS_ID) VALUES ('QA', 'Quality assurance', 'ACTIVE')");
				stmt.execute("INSERT INTO CONNECTIONS (ID, URL, TYPE_ID, STATUS_ID, NAME, LANDSCAPE_ID, INSTANCE_ID, USER_NAME, USER_PASSWORD, COMMENT) "
						+ "VALUES ('HRQA', 'jdbc:h2:mem:hrqa', 'H2', 'ACTIVE', 'HR (QA)', 'QA', 'WIKI1', 'sa', 'sa', '')");
			}
			conn.commit();
		}

		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault(path, password);
		vault.load();

		Assertions.assertTrue(vault.getEnvironments().contains("QA"), "the migrated ENVIRONMENT row must be readable under the new name");
		Assertions.assertEquals("QA", vault.getDatabaseConnection("HRQA").getEnvironment(),
				"a pre-existing connection's ENVIRONMENT_ID must survive the column rename with its value intact");
		Assertions.assertEquals("WIKI1", vault.getDatabaseConnection("HRQA").getDatabaseGroup(), "INSTANCE_ID is untouched by this migration");

		// A second load() (e.g. RELOAD VAULT) must be a clean no-op against the now-migrated schema.
		vault.load();
		Assertions.assertTrue(vault.getEnvironments().contains("QA"), "loading twice must not fail or lose data once already migrated");
	}
}
