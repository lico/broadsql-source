package com.upandcoding.broadsql.dao.api;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;

/**
 * Covers {@link ApiDefinitionsVault#load()}'s automatic schema migration - docs/SPRINT XT02 - Universal
 * API Client.md, section 13.3: creation on a fresh file, idempotence (running it twice never fails or
 * duplicates structure), and safe coexistence with {@code DatabaseDefinitionsVault}'s own tables in the
 * same physical CDF file (section 13.2).
 */
class TestApiDefinitionsVaultMigration {

	@Test
	void createsEveryApiTableOnAFreshCdfFile() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();

		Assertions.assertTrue(vault.getApis().isEmpty(), "a freshly migrated vault has no APIs yet");
		// If any CREATE TABLE had failed, load() above would already have thrown - a passing load() is
		// itself the primary assertion; the explicit table check below is a stronger, more specific one.
		assertTableExists(vault, "API");
		assertTableExists(vault, "API_VERSION");
		assertTableExists(vault, "API_ENVIRONMENT");
		assertTableExists(vault, "API_ENDPOINT_GROUP");
		assertTableExists(vault, "API_ENDPOINT");
		assertTableExists(vault, "API_AUTH");
		assertTableExists(vault, "API_ATTRIBUTE");
		assertTableExists(vault, "API_IMPORT_SOURCE");
	}

	@Test
	void migrationIsIdempotentAndPreservesExistingRows() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));

		vault.load();
		vault.load();

		Assertions.assertEquals(1, vault.getApis().size(), "re-running the migration must never duplicate or lose existing rows");
		Assertions.assertNotNull(vault.getApi("GITHUB"));
	}

	@Test
	void coexistsWithDatabaseDefinitionsVaultTablesInTheSamePhysicalCdfFile() throws BroadSQLException {
		ApiDefinitionsVault apiVault = TestApiDefinitionsVaults.newFileBackedVaultSharingDatabaseTables();
		apiVault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));

		// The database vault, pointed at the exact same file/password, must still load cleanly and see
		// its own pre-existing data untouched by ApiDefinitionsVault's migration/writes.
		DatabaseDefinitionsVault dbVault = new DatabaseDefinitionsVault(apiVault.getFileName(), apiVault.getPassword());
		dbVault.load();

		Assertions.assertTrue(dbVault.contains("SOMEDB"), "ApiDefinitionsVault must not disturb pre-existing CONNECTIONS rows in the shared CDF file");
		Assertions.assertEquals(1, apiVault.getApis().size());
	}

	/**
	 * SPRINT XT02 sub-sprint 5: seeds a pre-ALIAS-column {@code API_ENDPOINT} row via direct JDBC (as if
	 * this CDF had never seen {@link ApiDefinitionsVault#migrateApiEndpointAliasColumnIfNeeded}), then
	 * calls {@link ApiDefinitionsVault#load()} and asserts the column now exists and the row's other
	 * data survives untouched - same idiom {@link #migrationIsIdempotentAndPreservesExistingRows} uses
	 * for the table-level migration.
	 */
	@Test
	void migratesInAnAliasColumnOnAPreAliasEndpointTableWithoutLosingExistingData() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		com.upandcoding.broadsql.dao.api.model.ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));

		try {
			Class.forName("org.h2.Driver");
			String connectionStr = "jdbc:h2:" + vault.getFileName() + ";CIPHER=AES";
			String aesPassword = vault.getPassword() + " " + vault.getPassword();
			try (Connection conn = DriverManager.getConnection(connectionStr, vault.getAdminName(), aesPassword)) {
				try (Statement stat = conn.createStatement()) {
					stat.execute("ALTER TABLE API_ENDPOINT DROP COLUMN ALIAS");
				}
				try (java.sql.PreparedStatement insert = conn.prepareStatement(
						"INSERT INTO API_ENDPOINT (API_VERSION_ID, NAME, METHOD, ENDPOINT_PATH, SORT_ORDER, STATUS_ID) VALUES (?,?,?,?,?,?)")) {
					insert.setInt(1, version.getId());
					insert.setString(2, "Pre-existing endpoint");
					insert.setString(3, "GET");
					insert.setString(4, "/legacy");
					insert.setInt(5, 0);
					insert.setString(6, "ACTIVE");
					insert.executeUpdate();
				}
				conn.commit();
			}
		} catch (Exception e) {
			throw new BroadSQLException(e);
		}

		vault.load();

		assertColumnExists(vault, "API_ENDPOINT", "ALIAS");
		com.upandcoding.broadsql.dao.api.model.ApiEndpoint reloaded = vault.getEndpointsForVersion(version.getId()).stream()
				.filter(e -> "Pre-existing endpoint".equals(e.getName())).findFirst().orElseThrow();
		Assertions.assertEquals("/legacy", reloaded.getEndpointPath(), "pre-existing row data must survive the ALTER TABLE untouched");
		Assertions.assertNull(reloaded.getAlias(), "a pre-existing row must get a null alias, never a guessed/default one");
	}

	/**
	 * Post-remediation migration re-verification (see this remediation's own instructions): the
	 * lifecycle/alias fixes (findings 2, 3, 5) touch persisted metadata, so this proves a richly
	 * populated CDF - alias, mixed active/inactive statuses, a dual-written base URL, and a
	 * secret-flagged attribute - survives {@link ApiDefinitionsVault#load()} run <b>twice in a row</b>
	 * with everything intact both times. {@link #migratesInAnAliasColumnOnAPreAliasEndpointTableWithoutLosingExistingData}
	 * already separately covers the genuinely-pre-ALIAS-column upgrade path in isolation (correctly
	 * proving a truly pre-existing row gets a {@code null} alias, since the column - and therefore any
	 * alias value - did not exist yet); this test's concern is different and complementary: once the
	 * schema is at its current shape and richly populated, does calling the idempotent migration path
	 * again (exactly what happens on every ordinary application restart) ever corrupt or drop any of
	 * this data.
	 */
	@Test
	void aRichlyPopulatedCdfSurvivesTwoConsecutiveLoadsWithAliasesStatusesBaseUrlsAndSecretFlagsIntact() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		com.upandcoding.broadsql.dao.api.model.ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("MIGRATE01"));

		com.upandcoding.broadsql.dao.api.model.ApiEnvironment env = new com.upandcoding.broadsql.dao.api.model.ApiEnvironment("MIGRATE01", "Production", null, 0);
		vault.saveEnvironment(env);
		vault.setEnvironmentBaseUrl(env, "https://prod.example.com");

		com.upandcoding.broadsql.dao.api.model.ApiEndpoint activeEndpoint = new com.upandcoding.broadsql.dao.api.model.ApiEndpoint(
				version.getId(), null, "Create order", "POST", "${baseUrl}/orders", 0);
		activeEndpoint.setAlias("DO_ORDER");
		vault.saveEndpoint(activeEndpoint);
		vault.saveAttribute(new com.upandcoding.broadsql.dao.api.model.ApiAttribute(com.upandcoding.broadsql.dao.api.model.ApiOwnerType.ENDPOINT,
				String.valueOf(activeEndpoint.getId()), com.upandcoding.broadsql.dao.api.model.ApiAttributeKind.HEADER,
				"X-Api-Key", "synthetic-test-secret-789", true));

		com.upandcoding.broadsql.dao.api.model.ApiEndpoint inactiveEndpoint = new com.upandcoding.broadsql.dao.api.model.ApiEndpoint(
				version.getId(), null, "Deprecated endpoint", "GET", "/old", 1);
		vault.saveEndpoint(inactiveEndpoint);
		vault.deactivateEndpoint(inactiveEndpoint.getId());

		vault.load();
		vault.load(); // idempotency: must not fail or duplicate/lose anything the second time either

		com.upandcoding.broadsql.dao.api.model.ApiVersion reloadedVersion = vault.getDefaultVersion("MIGRATE01");
		com.upandcoding.broadsql.dao.api.model.ApiEndpoint reloadedActive = vault.getEndpointsForVersion(reloadedVersion.getId()).stream()
				.filter(e -> "Create order".equals(e.getName())).findFirst().orElseThrow();
		Assertions.assertEquals("DO_ORDER", reloadedActive.getAlias(), "alias must survive the schema migration and both loads");
		Assertions.assertEquals("ACTIVE", reloadedActive.getStatusId());

		com.upandcoding.broadsql.dao.api.model.ApiEndpoint reloadedInactive = vault.getEndpointsForVersion(reloadedVersion.getId()).stream()
				.filter(e -> "Deprecated endpoint".equals(e.getName())).findFirst().orElseThrow();
		Assertions.assertEquals("INACTIVE", reloadedInactive.getStatusId(), "status must survive the schema migration and both loads");

		com.upandcoding.broadsql.dao.api.model.ApiEnvironment reloadedEnv = vault.findEnvironmentById(env.getId());
		Assertions.assertEquals("https://prod.example.com", reloadedEnv.getBaseUrl(), "base URL must survive the schema migration and both loads");

		java.util.List<com.upandcoding.broadsql.dao.api.model.ApiAttribute> headers = vault.getAttributes(
				com.upandcoding.broadsql.dao.api.model.ApiOwnerType.ENDPOINT, String.valueOf(reloadedActive.getId()),
				com.upandcoding.broadsql.dao.api.model.ApiAttributeKind.HEADER);
		com.upandcoding.broadsql.dao.api.model.ApiAttribute reloadedHeader = headers.get(0);
		Assertions.assertTrue(reloadedHeader.isSecret(), "the secret flag must survive the schema migration and both loads");
		Assertions.assertEquals("synthetic-test-secret-789", reloadedHeader.getValue());
	}

	/**
	 * SPRINT XT02A (URL-Native API Execution), section 9.4: seeds a pre-XT02A {@code API_ATTRIBUTE}
	 * row (a path parameter's bare persisted value, exactly as the legacy "settings values" model
	 * stored it - no {@code REQUIRED}/{@code PARAM_TYPE}/{@code DEFAULT_VALUE}/{@code ALLOWED_VALUES}/
	 * {@code DESCR} columns at all) via direct JDBC, then calls {@link ApiDefinitionsVault#load()} and
	 * asserts the new columns now exist, the pre-existing persisted value survives untouched, and the
	 * new columns default sanely (not required, {@code string} type, no default/allowed values) rather
	 * than erroring or leaving the row unreadable - the same idiom
	 * {@link #migratesInAnAliasColumnOnAPreAliasEndpointTableWithoutLosingExistingData} uses for the
	 * ALIAS column migration.
	 */
	@Test
	void migratesInParameterMetadataColumnsOnAPreXt02aAttributeRowWithoutLosingThePersistedValue() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		com.upandcoding.broadsql.dao.api.model.ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("LEGACY01"));
		com.upandcoding.broadsql.dao.api.model.ApiEndpoint endpoint = new com.upandcoding.broadsql.dao.api.model.ApiEndpoint(
				version.getId(), null, "Get customer", "GET", "${baseUrl}/api/customer/${id}", 0);
		vault.saveEndpoint(endpoint);

		try {
			Class.forName("org.h2.Driver");
			String connectionStr = "jdbc:h2:" + vault.getFileName() + ";CIPHER=AES";
			String aesPassword = vault.getPassword() + " " + vault.getPassword();
			try (Connection conn = DriverManager.getConnection(connectionStr, vault.getAdminName(), aesPassword)) {
				try (Statement stat = conn.createStatement()) {
					stat.execute("ALTER TABLE API_ATTRIBUTE DROP COLUMN REQUIRED");
					stat.execute("ALTER TABLE API_ATTRIBUTE DROP COLUMN PARAM_TYPE");
					stat.execute("ALTER TABLE API_ATTRIBUTE DROP COLUMN DEFAULT_VALUE");
					stat.execute("ALTER TABLE API_ATTRIBUTE DROP COLUMN ALLOWED_VALUES");
					stat.execute("ALTER TABLE API_ATTRIBUTE DROP COLUMN DESCR");
				}
				try (java.sql.PreparedStatement insert = conn.prepareStatement(
						"INSERT INTO API_ATTRIBUTE (OWNER_TYPE, OWNER_ID, ATTR_KIND, NAME, ATTR_VALUE, SECRET, ENABLED, SORT_ORDER) VALUES (?,?,?,?,?,?,?,?)")) {
					insert.setString(1, "ENDPOINT");
					insert.setString(2, String.valueOf(endpoint.getId()));
					insert.setString(3, "PATH_PARAMETER");
					insert.setString(4, "id");
					insert.setString(5, "123");
					insert.setBoolean(6, false);
					insert.setBoolean(7, true);
					insert.setInt(8, 0);
					insert.executeUpdate();
				}
				conn.commit();
			}
		} catch (Exception e) {
			throw new BroadSQLException(e);
		}

		vault.load();

		assertColumnExists(vault, "API_ATTRIBUTE", "REQUIRED");
		assertColumnExists(vault, "API_ATTRIBUTE", "PARAM_TYPE");
		assertColumnExists(vault, "API_ATTRIBUTE", "DEFAULT_VALUE");
		assertColumnExists(vault, "API_ATTRIBUTE", "ALLOWED_VALUES");
		assertColumnExists(vault, "API_ATTRIBUTE", "DESCR");

		com.upandcoding.broadsql.dao.api.model.ApiAttribute reloaded = vault.getAttributes(
				com.upandcoding.broadsql.dao.api.model.ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()),
				com.upandcoding.broadsql.dao.api.model.ApiAttributeKind.PATH_PARAMETER).get(0);
		Assertions.assertEquals("123", reloaded.getValue(), "the pre-existing persisted value must survive the ALTER TABLE untouched");
		Assertions.assertFalse(reloaded.isRequired(), "a pre-existing row must default to not-required, never a guessed true");
		Assertions.assertEquals("string", reloaded.getParamType(), "a pre-existing row must default to the string type");
		Assertions.assertNull(reloaded.getDefaultValue());
		Assertions.assertNull(reloaded.getAllowedValues());
	}

	private void assertColumnExists(ApiDefinitionsVault vault, String tableName, String columnName) throws BroadSQLException {
		try {
			Class.forName("org.h2.Driver");
			String connectionStr = "jdbc:h2:" + vault.getFileName() + ";CIPHER=AES";
			String aesPassword = vault.getPassword() + " " + vault.getPassword();
			try (Connection conn = DriverManager.getConnection(connectionStr, vault.getAdminName(), aesPassword);
					java.sql.PreparedStatement stat = conn.prepareStatement(
							"SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME=?")) {
				stat.setString(1, tableName);
				stat.setString(2, columnName);
				try (ResultSet rs = stat.executeQuery()) {
					rs.next();
					Assertions.assertEquals(1, rs.getInt(1), "column " + tableName + "." + columnName + " must exist after load()");
				}
			}
		} catch (Exception e) {
			throw new BroadSQLException(e);
		}
	}

	private void assertTableExists(ApiDefinitionsVault vault, String tableName) throws BroadSQLException {
		try {
			Class.forName("org.h2.Driver");
			String connectionStr = "jdbc:h2:" + vault.getFileName() + ";CIPHER=AES";
			String aesPassword = vault.getPassword() + " " + vault.getPassword();
			try (Connection conn = DriverManager.getConnection(connectionStr, vault.getAdminName(), aesPassword);
					Statement stat = conn.createStatement();
					ResultSet rs = stat.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='" + tableName + "'")) {
				rs.next();
				Assertions.assertEquals(1, rs.getInt(1), "table " + tableName + " must exist after load()");
			}
		} catch (Exception e) {
			throw new BroadSQLException(e);
		}
	}
}
