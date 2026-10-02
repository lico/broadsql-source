package com.upandcoding.broadsql.dao.api;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.model.ApiAliasValidator;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiAuthType;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiImportSource;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Persistence for BroadSQL's API/HTTP client catalog (SPRINT XT02 - Universal API Client) - the
 * {@code API}/{@code API_VERSION}/{@code API_ENVIRONMENT}/{@code API_ENDPOINT_GROUP}/
 * {@code API_ENDPOINT}/{@code API_AUTH}/{@code API_ATTRIBUTE}/{@code API_IMPORT_SOURCE} tables described
 * in docs/SPRINT XT02 - Universal API Client.md, section 13.1.
 *
 * <p>Deliberately a separate class from {@link com.upandcoding.broadsql.dao.DatabaseDefinitionsVault},
 * mirroring its shape ({@code fileName}/{@code password}/{@code adminName}, same
 * {@code jdbc:h2:<file>;CIPHER=AES} connection string) rather than adding eight more tables to an already
 * large class - see the sprint doc's section 13.2 for the full reasoning. Targets the *same* physical CDF
 * file and master password as database connections: one encrypted file, one password, for both concerns,
 * reusing the CDF's file-level AES encryption for secrets exactly like {@code CONNECTIONS.USER_PASSWORD}
 * already does (no additional field-level encryption - a documented limitation, not an oversight).
 *
 * <p>Schema creation/upgrade is automatic and idempotent on every {@link #load()} - guarded
 * {@code CREATE TABLE IF NOT EXISTS} - the same proven pattern
 * {@code DatabaseDefinitionsVault#migrateGroupAndEnvironmentColumnsIfNeeded} already uses, chosen over a
 * separate numbered-migration framework (see the sprint doc's section 13.3 for why introducing a second
 * migration paradigm would itself violate "no parallel architectural style").
 */
public class ApiDefinitionsVault {

	public static final String H2_ADMIN = "ADMIN";

	private String fileName;
	private String password;
	private String adminName = H2_ADMIN;

	private final Map<String, ApiDefinition> apis = new HashMap<>();

	public ApiDefinitionsVault() {
	}

	public ApiDefinitionsVault(String fileName, String password) {
		this.fileName = fileName;
		this.password = password;
	}

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getAdminName() {
		return adminName;
	}

	public void setAdminName(String adminName) {
		this.adminName = adminName;
	}

	// ------------------------------------------------------------------------------------------
	// Connection / migration
	// ------------------------------------------------------------------------------------------

	private Connection openConnection() throws BroadSQLException {
		if (StringUtils.isBlank(fileName)) {
			throw new BroadSQLException("This API vault is not backed by a CDF file.");
		}
		try {
			Class.forName("org.h2.Driver");
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			String aesPassword = password + " " + password;
			return DriverManager.getConnection(connectionStr, adminName, aesPassword);
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
	}

	/**
	 * Connects to the CDF, applies every schema migration needed (idempotent - safe to call on a brand
	 * new file, an upgraded one, or one already at the current shape), then refreshes the in-memory
	 * active-API cache {@link #getApis()}/{@link #contains(String)} read from.
	 */
	public void load() throws BroadSQLException {
		apis.clear();
		try (Connection conn = openConnection()) {
			migrateApiSchemaIfNeeded(conn);
			try (Statement stat = conn.createStatement();
					ResultSet rs = stat.executeQuery("SELECT ID, NAME, DESCR, COMMENT, DEFAULT_VERSION_ID, PROBE_METHOD, PROBE_PATH, STATUS_ID FROM API WHERE STATUS_ID='"
							+ DatabaseDefinition.STATUS_ACTIVE + "' ORDER BY ID")) {
				while (rs.next()) {
					ApiDefinition api = mapApi(rs);
					apis.put(api.getId(), api);
				}
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// load

	private boolean tableExists(Connection conn, String tableName) throws SQLException {
		try (PreparedStatement stat = conn.prepareStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME=?")) {
			stat.setString(1, tableName);
			try (ResultSet rs = stat.executeQuery()) {
				rs.next();
				return rs.getInt(1) > 0;
			}
		}
	}// tableExists

	/** Mirrors {@code DatabaseDefinitionsVault#columnExists} exactly - see that class for the pattern this follows. */
	private boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
		try (PreparedStatement stat = conn.prepareStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME=?")) {
			stat.setString(1, tableName);
			stat.setString(2, columnName);
			try (ResultSet rs = stat.executeQuery()) {
				rs.next();
				return rs.getInt(1) > 0;
			}
		}
	}// columnExists

	/**
	 * Creates every {@code API*} table this vault needs, each guarded by {@link #tableExists} so this is
	 * a no-op once already applied - safe to call unconditionally on every {@link #load()}, including
	 * against a CDF file that already holds {@code DatabaseDefinitionsVault}'s own tables (CONNECTIONS/
	 * TYPE/INSTANCE/ENVIRONMENT), which this never touches. A future schema addition (e.g. a new column)
	 * gets its own guarded method in this same style, called from here, exactly like
	 * {@code DatabaseDefinitionsVault#migrateGroupAndEnvironmentColumnsIfNeeded} follows
	 * {@code #migrateEnvironmentSchemaIfNeeded}.
	 */
	private void migrateApiSchemaIfNeeded(Connection conn) throws SQLException {
		if (!tableExists(conn, "API")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("CREATE TABLE API (" +
						"ID VARCHAR(80) PRIMARY KEY, " +
						"NAME VARCHAR(255), " +
						"DESCR VARCHAR(1000), " +
						"COMMENT VARCHAR(1000), " +
						"DEFAULT_VERSION_ID INT, " +
						"PROBE_METHOD VARCHAR(10), " +
						"PROBE_PATH VARCHAR(2000), " +
						"STATUS_ID VARCHAR(10))");
			}
		}
		if (!tableExists(conn, "API_VERSION")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("CREATE TABLE API_VERSION (" +
						"ID INT AUTO_INCREMENT PRIMARY KEY, " +
						"API_ID VARCHAR(80), " +
						"NAME VARCHAR(255), " +
						"IS_DEFAULT BOOLEAN DEFAULT FALSE, " +
						"STATUS_ID VARCHAR(10))");
			}
		}
		if (!tableExists(conn, "API_ENVIRONMENT")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("CREATE TABLE API_ENVIRONMENT (" +
						"ID INT AUTO_INCREMENT PRIMARY KEY, " +
						"API_ID VARCHAR(80), " +
						"NAME VARCHAR(255), " +
						"BASE_URL VARCHAR(2000), " +
						"SORT_ORDER INT DEFAULT 0, " +
						"SOURCE_TYPE VARCHAR(30), " +
						"SOURCE_KEY VARCHAR(500), " +
						"STATUS_ID VARCHAR(10))");
			}
		}
		if (!tableExists(conn, "API_ENDPOINT_GROUP")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("CREATE TABLE API_ENDPOINT_GROUP (" +
						"ID INT AUTO_INCREMENT PRIMARY KEY, " +
						"API_VERSION_ID INT, " +
						"PARENT_GROUP_ID INT, " +
						"NAME VARCHAR(255), " +
						"SORT_ORDER INT DEFAULT 0, " +
						"SOURCE_TYPE VARCHAR(30), " +
						"SOURCE_KEY VARCHAR(500), " +
						"STATUS_ID VARCHAR(10))");
			}
		}
		if (!tableExists(conn, "API_ENDPOINT")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("CREATE TABLE API_ENDPOINT (" +
						"ID INT AUTO_INCREMENT PRIMARY KEY, " +
						"API_VERSION_ID INT, " +
						"GROUP_ID INT, " +
						"NAME VARCHAR(255), " +
						"METHOD VARCHAR(10), " +
						"ENDPOINT_PATH VARCHAR(2000), " +
						"BODY_MODE VARCHAR(30), " +
						"BODY_CONTENT VARCHAR(1000000), " +
						"SORT_ORDER INT DEFAULT 0, " +
						"SOURCE_TYPE VARCHAR(30), " +
						"SOURCE_KEY VARCHAR(500), " +
						"STATUS_ID VARCHAR(10))");
			}
		}
		migrateApiEndpointAliasColumnIfNeeded(conn);
		if (!tableExists(conn, "API_AUTH")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("CREATE TABLE API_AUTH (" +
						"ID INT AUTO_INCREMENT PRIMARY KEY, " +
						"OWNER_TYPE VARCHAR(20), " +
						"OWNER_ID VARCHAR(80), " +
						"AUTH_TYPE VARCHAR(30))");
			}
		}
		if (!tableExists(conn, "API_ATTRIBUTE")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("CREATE TABLE API_ATTRIBUTE (" +
						"ID INT AUTO_INCREMENT PRIMARY KEY, " +
						"OWNER_TYPE VARCHAR(20), " +
						"OWNER_ID VARCHAR(80), " +
						"ATTR_KIND VARCHAR(20), " +
						"NAME VARCHAR(255), " +
						"ATTR_VALUE VARCHAR(4000), " +
						"SECRET BOOLEAN DEFAULT FALSE, " +
						"ENABLED BOOLEAN DEFAULT TRUE, " +
						"SORT_ORDER INT DEFAULT 0)");
			}
		}
		migrateApiAttributeParameterMetadataColumnsIfNeeded(conn);
		if (!tableExists(conn, "API_IMPORT_SOURCE")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("CREATE TABLE API_IMPORT_SOURCE (" +
						"ID INT AUTO_INCREMENT PRIMARY KEY, " +
						"API_ID VARCHAR(80), " +
						"SOURCE_TYPE VARCHAR(30), " +
						"SOURCE_LOCATION VARCHAR(2000), " +
						"LAST_IMPORTED_AT TIMESTAMP)");
			}
		}
		conn.commit();
	}// migrateApiSchemaIfNeeded

	/**
	 * One-time, idempotent schema migration adding {@code API_ENDPOINT.ALIAS} (SPRINT XT02 sub-sprint 5 -
	 * docs/Amendment - Endpoint Aliases and Future Scriptability.md). Guarded by {@link #columnExists},
	 * exactly the same {@code columnExists}-guarded {@code ALTER TABLE} pattern
	 * {@code DatabaseDefinitionsVault#migrateGroupAndEnvironmentColumnsIfNeeded} uses - so this is a no-op
	 * on a CDF this has already run against. Called unconditionally from {@link #migrateApiSchemaIfNeeded},
	 * right after the {@code API_ENDPOINT} {@code tableExists} block, so it runs whether {@code API_ENDPOINT}
	 * is being created fresh (where the column could just as easily be part of the {@code CREATE TABLE}, but
	 * going through the same guarded-migration path keeps exactly one schema-evolution mechanism in this
	 * class) or already existed from a pre-alias release.
	 */
	private void migrateApiEndpointAliasColumnIfNeeded(Connection conn) throws SQLException {
		if (!columnExists(conn, "API_ENDPOINT", "ALIAS")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE API_ENDPOINT ADD COLUMN ALIAS VARCHAR(80)");
			}
			conn.commit();
		}
	}// migrateApiEndpointAliasColumnIfNeeded

	/**
	 * One-time, idempotent schema migration adding {@code API_ATTRIBUTE.REQUIRED}/{@code PARAM_TYPE}/
	 * {@code DEFAULT_VALUE}/{@code ALLOWED_VALUES}/{@code DESCR} - SPRINT XT02A (URL-Native API
	 * Execution), evolving {@code PATH_PARAMETER}/{@code QUERY_PARAMETER} rows from a bare persisted
	 * value into full endpoint-parameter metadata (docs/SPRINT_XT02A_URL_NATIVE_API_EXECUTION.md,
	 * section 2.6/4), reused as-is by every other {@code ApiAttribute} kind (left at their column
	 * defaults). Same guarded {@code ALTER TABLE} pattern as {@link #migrateApiEndpointAliasColumnIfNeeded}.
	 */
	private void migrateApiAttributeParameterMetadataColumnsIfNeeded(Connection conn) throws SQLException {
		if (!columnExists(conn, "API_ATTRIBUTE", "REQUIRED")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE API_ATTRIBUTE ADD COLUMN REQUIRED BOOLEAN DEFAULT FALSE");
			}
			conn.commit();
		}
		if (!columnExists(conn, "API_ATTRIBUTE", "PARAM_TYPE")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE API_ATTRIBUTE ADD COLUMN PARAM_TYPE VARCHAR(20) DEFAULT 'string'");
			}
			conn.commit();
		}
		if (!columnExists(conn, "API_ATTRIBUTE", "DEFAULT_VALUE")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE API_ATTRIBUTE ADD COLUMN DEFAULT_VALUE VARCHAR(4000)");
			}
			conn.commit();
		}
		if (!columnExists(conn, "API_ATTRIBUTE", "ALLOWED_VALUES")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE API_ATTRIBUTE ADD COLUMN ALLOWED_VALUES VARCHAR(2000)");
			}
			conn.commit();
		}
		if (!columnExists(conn, "API_ATTRIBUTE", "DESCR")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE API_ATTRIBUTE ADD COLUMN DESCR VARCHAR(500)");
			}
			conn.commit();
		}
	}// migrateApiAttributeParameterMetadataColumnsIfNeeded

	// ------------------------------------------------------------------------------------------
	// API
	// ------------------------------------------------------------------------------------------

	public boolean contains(String id) {
		return apis.containsKey(id);
	}

	public ApiDefinition getApi(String id) {
		return apis.get(id);
	}

	public List<ApiDefinition> getApis() {
		return new ArrayList<>(apis.values());
	}

	/**
	 * Resolves an {@code API} row directly from the database, regardless of {@code STATUS_ID} - unlike
	 * {@link #getApi(String)}/{@link #contains(String)}, which only ever see active rows because
	 * {@link #apis} is populated from a {@code WHERE STATUS_ID='ACTIVE'} query. Used by
	 * {@code CommandApiExecuteEndpoint} so a deactivated API can be reported as "inactive" rather than
	 * "not found" (SPRINT XT02 verification finding 4) - {@code contains(apiId)} alone cannot make that
	 * distinction, since a deactivated API simply disappears from the in-memory cache.
	 */
	public ApiDefinition findApiById(String apiId) throws BroadSQLException {
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement(
						"SELECT ID, NAME, DESCR, COMMENT, DEFAULT_VERSION_ID, PROBE_METHOD, PROBE_PATH, STATUS_ID FROM API WHERE ID=?")) {
			stat.setString(1, apiId);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? mapApi(rs) : null;
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// findApiById

	private ApiDefinition mapApi(ResultSet rs) throws SQLException {
		ApiDefinition api = new ApiDefinition(rs.getString("ID"));
		api.setName(rs.getString("NAME"));
		api.setDescr(rs.getString("DESCR"));
		api.setComment(rs.getString("COMMENT"));
		int defaultVersionId = rs.getInt("DEFAULT_VERSION_ID");
		api.setDefaultVersionId(rs.wasNull() ? null : defaultVersionId);
		api.setProbeMethod(rs.getString("PROBE_METHOD"));
		api.setProbePath(rs.getString("PROBE_PATH"));
		api.setStatusId(rs.getString("STATUS_ID"));
		return api;
	}

	/**
	 * Inserts a new {@code API} row, or updates the existing one when {@code api.getId()} already exists
	 * - same "save decides insert vs update by ID presence" convention as
	 * {@code DatabaseDefinitionsVault#saveDatabaseDefinition}. Reloads the vault afterward.
	 */
	public void saveApi(ApiDefinition api) throws BroadSQLException {
		if (api == null || !api.isNotNull()) {
			return;
		}
		try (Connection conn = openConnection()) {
			boolean exists;
			try (PreparedStatement check = conn.prepareStatement("SELECT COUNT(*) FROM API WHERE ID=?")) {
				check.setString(1, api.getId());
				try (ResultSet rs = check.executeQuery()) {
					rs.next();
					exists = rs.getInt(1) > 0;
				}
			}
			if (exists) {
				try (PreparedStatement update = conn.prepareStatement(
						"UPDATE API SET NAME=?, DESCR=?, COMMENT=?, DEFAULT_VERSION_ID=?, PROBE_METHOD=?, PROBE_PATH=?, STATUS_ID=? WHERE ID=?")) {
					update.setString(1, api.getName());
					update.setString(2, api.getDescr());
					update.setString(3, api.getComment());
					setNullableInt(update, 4, api.getDefaultVersionId());
					update.setString(5, api.getProbeMethod());
					update.setString(6, api.getProbePath());
					update.setString(7, StringUtils.defaultIfBlank(api.getStatusId(), DatabaseDefinition.STATUS_ACTIVE));
					update.setString(8, api.getId());
					update.executeUpdate();
				}
			} else {
				try (PreparedStatement insert = conn.prepareStatement(
						"INSERT INTO API (ID, NAME, DESCR, COMMENT, DEFAULT_VERSION_ID, PROBE_METHOD, PROBE_PATH, STATUS_ID) VALUES (?,?,?,?,?,?,?,?)")) {
					insert.setString(1, api.getId());
					insert.setString(2, api.getName());
					insert.setString(3, api.getDescr());
					insert.setString(4, api.getComment());
					setNullableInt(insert, 5, api.getDefaultVersionId());
					insert.setString(6, api.getProbeMethod());
					insert.setString(7, api.getProbePath());
					insert.setString(8, StringUtils.defaultIfBlank(api.getStatusId(), DatabaseDefinition.STATUS_ACTIVE));
					insert.executeUpdate();
				}
			}
			conn.commit();
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
		load();
	}// saveApi

	/**
	 * Creates {@code api} together with its mandatory default {@link ApiVersion} (Release 1: exactly one
	 * version per API - see the sprint doc's section 12) in a single call, and points
	 * {@code API.DEFAULT_VERSION_ID} at it. The convenience every non-import creation path should use
	 * instead of calling {@link #saveApi} and {@link #saveVersion} separately and wiring the two IDs by
	 * hand.
	 *
	 * @return the created default {@link ApiVersion}, with its generated ID populated
	 */
	public ApiVersion createApiWithDefaultVersion(ApiDefinition api) throws BroadSQLException {
		saveApi(api);
		ApiVersion version = new ApiVersion(api.getId(), ApiVersion.DEFAULT_VERSION_NAME, true);
		saveVersion(version);
		api.setDefaultVersionId(version.getId());
		saveApi(api);
		return version;
	}// createApiWithDefaultVersion

	// ------------------------------------------------------------------------------------------
	// API_VERSION
	// ------------------------------------------------------------------------------------------

	private ApiVersion mapVersion(ResultSet rs) throws SQLException {
		ApiVersion version = new ApiVersion();
		version.setId(rs.getInt("ID"));
		version.setApiId(rs.getString("API_ID"));
		version.setName(rs.getString("NAME"));
		version.setDefault(rs.getBoolean("IS_DEFAULT"));
		version.setStatusId(rs.getString("STATUS_ID"));
		return version;
	}

	/**
	 * Inserts a new {@code API_VERSION} row when {@code version.getId()} is {@code null}, populating it
	 * with the generated ID; updates the existing row otherwise.
	 */
	public void saveVersion(ApiVersion version) throws BroadSQLException {
		try (Connection conn = openConnection()) {
			if (version.getId() == null) {
				try (PreparedStatement insert = conn.prepareStatement(
						"INSERT INTO API_VERSION (API_ID, NAME, IS_DEFAULT, STATUS_ID) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
					insert.setString(1, version.getApiId());
					insert.setString(2, version.getName());
					insert.setBoolean(3, version.isDefault());
					insert.setString(4, StringUtils.defaultIfBlank(version.getStatusId(), DatabaseDefinition.STATUS_ACTIVE));
					insert.executeUpdate();
					try (ResultSet keys = insert.getGeneratedKeys()) {
						if (keys.next()) {
							version.setId(keys.getInt(1));
						}
					}
				}
			} else {
				try (PreparedStatement update = conn.prepareStatement(
						"UPDATE API_VERSION SET API_ID=?, NAME=?, IS_DEFAULT=?, STATUS_ID=? WHERE ID=?")) {
					update.setString(1, version.getApiId());
					update.setString(2, version.getName());
					update.setBoolean(3, version.isDefault());
					update.setString(4, StringUtils.defaultIfBlank(version.getStatusId(), DatabaseDefinition.STATUS_ACTIVE));
					update.setInt(5, version.getId());
					update.executeUpdate();
				}
			}
			conn.commit();
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// saveVersion

	public List<ApiVersion> getVersionsForApi(String apiId) throws BroadSQLException {
		List<ApiVersion> result = new ArrayList<>();
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_VERSION WHERE API_ID=? ORDER BY ID")) {
			stat.setString(1, apiId);
			try (ResultSet rs = stat.executeQuery()) {
				while (rs.next()) {
					result.add(mapVersion(rs));
				}
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
		return result;
	}// getVersionsForApi

	public ApiVersion getDefaultVersion(String apiId) throws BroadSQLException {
		for (ApiVersion version : getVersionsForApi(apiId)) {
			if (version.isDefault()) {
				return version;
			}
		}
		return null;
	}// getDefaultVersion

	// ------------------------------------------------------------------------------------------
	// API_ENVIRONMENT
	// ------------------------------------------------------------------------------------------

	private ApiEnvironment mapEnvironment(ResultSet rs) throws SQLException {
		ApiEnvironment env = new ApiEnvironment();
		env.setId(rs.getInt("ID"));
		env.setApiId(rs.getString("API_ID"));
		env.setName(rs.getString("NAME"));
		env.setBaseUrl(rs.getString("BASE_URL"));
		env.setSortOrder(rs.getInt("SORT_ORDER"));
		env.setSourceType(rs.getString("SOURCE_TYPE"));
		env.setSourceKey(rs.getString("SOURCE_KEY"));
		env.setStatusId(rs.getString("STATUS_ID"));
		return env;
	}

	public void saveEnvironment(ApiEnvironment env) throws BroadSQLException {
		try (Connection conn = openConnection()) {
			if (env.getId() == null) {
				try (PreparedStatement insert = conn.prepareStatement(
						"INSERT INTO API_ENVIRONMENT (API_ID, NAME, BASE_URL, SORT_ORDER, SOURCE_TYPE, SOURCE_KEY, STATUS_ID) VALUES (?,?,?,?,?,?,?)",
						Statement.RETURN_GENERATED_KEYS)) {
					insert.setString(1, env.getApiId());
					insert.setString(2, env.getName());
					insert.setString(3, env.getBaseUrl());
					insert.setInt(4, env.getSortOrder());
					insert.setString(5, env.getSourceType());
					insert.setString(6, env.getSourceKey());
					insert.setString(7, StringUtils.defaultIfBlank(env.getStatusId(), DatabaseDefinition.STATUS_ACTIVE));
					insert.executeUpdate();
					try (ResultSet keys = insert.getGeneratedKeys()) {
						if (keys.next()) {
							env.setId(keys.getInt(1));
						}
					}
				}
			} else {
				try (PreparedStatement update = conn.prepareStatement(
						"UPDATE API_ENVIRONMENT SET API_ID=?, NAME=?, BASE_URL=?, SORT_ORDER=?, SOURCE_TYPE=?, SOURCE_KEY=?, STATUS_ID=? WHERE ID=?")) {
					update.setString(1, env.getApiId());
					update.setString(2, env.getName());
					update.setString(3, env.getBaseUrl());
					update.setInt(4, env.getSortOrder());
					update.setString(5, env.getSourceType());
					update.setString(6, env.getSourceKey());
					update.setString(7, StringUtils.defaultIfBlank(env.getStatusId(), DatabaseDefinition.STATUS_ACTIVE));
					update.setInt(8, env.getId());
					update.executeUpdate();
				}
			}
			conn.commit();
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// saveEnvironment

	public List<ApiEnvironment> getEnvironmentsForApi(String apiId) throws BroadSQLException {
		List<ApiEnvironment> result = new ArrayList<>();
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_ENVIRONMENT WHERE API_ID=? ORDER BY SORT_ORDER, ID")) {
			stat.setString(1, apiId);
			try (ResultSet rs = stat.executeQuery()) {
				while (rs.next()) {
					result.add(mapEnvironment(rs));
				}
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
		return result;
	}// getEnvironmentsForApi

	/** A single environment by its own ID - used by the CONFIG API GUI and by {@link #duplicateEnvironment}. */
	public ApiEnvironment findEnvironmentById(int id) throws BroadSQLException {
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_ENVIRONMENT WHERE ID=?")) {
			stat.setInt(1, id);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? mapEnvironment(rs) : null;
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// findEnvironmentById

	/**
	 * The single, mandatory way to change an environment's base URL - SPRINT XT02 sub-sprint 5's mandatory
	 * dual-storage fix (see the sub-sprint plan's "Context" section). Before this method existed,
	 * {@code baseUrl} had two independent live representations that could silently drift apart: the
	 * {@code API_ENVIRONMENT.BASE_URL} column (read by {@code ApiEndpointRequestBuilder#joinBaseUrl} for a
	 * manually-created endpoint's bare relative path) and the {@code API_ATTRIBUTE} (ENVIRONMENT, VARIABLE,
	 * name={@code baseUrl}) row (read by ordinary variable resolution/substitution for any endpoint whose
	 * path template already contains {@code ${baseUrl}} - the normal shape for a Bruno-imported endpoint).
	 * A caller that wrote only the column (as a naive GUI "Base URL" field would) left the second,
	 * dominant path completely unaffected. This method writes both in one call and must be the only way
	 * either representation is ever changed - {@link com.upandcoding.broadsql.dao.api.bruno.BrunoCollectionImporter}
	 * and the CONFIG API GUI's Environments panel both go through this, never through {@link #saveEnvironment}
	 * plus a hand-rolled attribute upsert.
	 */
	public void setEnvironmentBaseUrl(ApiEnvironment env, String baseUrl) throws BroadSQLException {
		env.setBaseUrl(baseUrl);
		saveEnvironment(env);
		String envOwnerId = String.valueOf(env.getId());
		List<ApiAttribute> variables = new ArrayList<>(getAttributes(ApiOwnerType.ENVIRONMENT, envOwnerId, ApiAttributeKind.VARIABLE));
		ApiAttribute baseUrlAttr = null;
		for (ApiAttribute attr : variables) {
			if ("baseUrl".equalsIgnoreCase(attr.getName())) {
				baseUrlAttr = attr;
				break;
			}
		}
		if (baseUrlAttr == null) {
			baseUrlAttr = new ApiAttribute(ApiOwnerType.ENVIRONMENT, envOwnerId, ApiAttributeKind.VARIABLE, "baseUrl", baseUrl, false);
			variables.add(baseUrlAttr);
		} else {
			baseUrlAttr.setValue(baseUrl);
		}
		replaceAttributes(ApiOwnerType.ENVIRONMENT, envOwnerId, ApiAttributeKind.VARIABLE, variables);
	}// setEnvironmentBaseUrl

	/**
	 * The single, mandatory way for a caller (the CONFIG API GUI's Environments panel) that manages an
	 * environment's Base URL field and its other variables as two separate pieces of UI to save both
	 * together, in the order that keeps the {@code baseUrl} attribute intact. Before this method existed,
	 * {@code JApiEnvironmentsPanel.save} called {@link #setEnvironmentBaseUrl} (which correctly dual-writes
	 * the {@code BASE_URL} column and the {@code baseUrl} attribute) immediately followed by a direct
	 * {@link #replaceAttributes} call carrying only the variables-table content - which, by the GUI's own
	 * design, deliberately excludes {@code baseUrl} (kept in its own dedicated field, not the variables
	 * table, per the sprint spec's section 10). That second call replaced the *entire* attribute set for
	 * {@code (ENVIRONMENT, envId, VARIABLE)}, silently deleting the {@code baseUrl} attribute the first call
	 * had just written (SPRINT XT02 verification finding 2) - an endpoint whose path template contains
	 * {@code ${baseUrl}} (the normal shape for a Bruno-imported endpoint) would then fail to resolve it on
	 * the very next execution, even though {@code API_ENVIRONMENT.BASE_URL} itself still looked correct.
	 *
	 * <p>This method fixes the invariant centrally rather than by caller call-order discipline: it always
	 * replaces {@code otherVariables} first, then calls {@link #setEnvironmentBaseUrl} last so the
	 * {@code baseUrl} attribute is (re)written after, never before, the variables-table replacement. Any
	 * entry in {@code otherVariables} literally named {@code baseUrl} (case-insensitively) is dropped before
	 * saving, so a caller cannot accidentally create a duplicate row.
	 *
	 * @param otherVariables every VARIABLE-kind attribute this environment owns except {@code baseUrl}
	 *        itself (the caller's variables-table content, supplied separately from {@code baseUrl})
	 */
	public void saveEnvironmentVariablesAndBaseUrl(ApiEnvironment env, String baseUrl, List<ApiAttribute> otherVariables) throws BroadSQLException {
		String envOwnerId = String.valueOf(env.getId());
		List<ApiAttribute> filtered = new ArrayList<>();
		if (otherVariables != null) {
			for (ApiAttribute attr : otherVariables) {
				if (attr != null && !"baseUrl".equalsIgnoreCase(attr.getName())) {
					filtered.add(attr);
				}
			}
		}
		replaceAttributes(ApiOwnerType.ENVIRONMENT, envOwnerId, ApiAttributeKind.VARIABLE, filtered);
		setEnvironmentBaseUrl(env, baseUrl);
	}// saveEnvironmentVariablesAndBaseUrl

	/**
	 * Deep-copies environment {@code sourceEnvId} into a brand-new environment named {@code newName} - the
	 * "Duplicate environment" GUI action (sprint doc section 9: "A common workflow is Development -&gt;
	 * duplicate -&gt; Production -&gt; change baseUrl and credentials"). Copies {@code baseUrl} and every
	 * {@code VARIABLE}-kind attribute the source environment owns, <b>including secret values</b> - a
	 * deliberate, documented choice (not a silent oversight): the spec's own Dev-to-Prod example implies
	 * "duplicate, then edit the two things that differ" (baseUrl and credentials), which only makes sense
	 * if the starting point is a usable copy: clearing secrets on duplicate would make every duplicated
	 * environment start broken, requiring the user to re-enter every credential by hand even when they only
	 * meant to change one. The new environment is a fresh manual object - {@code sourceType}/{@code sourceKey}
	 * are left {@code null} even if the source was Bruno-imported, since the duplicate has no import
	 * provenance of its own.
	 *
	 * @return the newly created environment, with its generated ID populated
	 */
	public ApiEnvironment duplicateEnvironment(int sourceEnvId, String newName) throws BroadSQLException {
		ApiEnvironment source = findEnvironmentById(sourceEnvId);
		if (source == null) {
			throw new BroadSQLException("Environment '" + sourceEnvId + "' does not exist.");
		}
		ApiEnvironment copy = new ApiEnvironment(source.getApiId(), newName, source.getBaseUrl(), source.getSortOrder());
		saveEnvironment(copy);
		List<ApiAttribute> copiedAttributes = new ArrayList<>();
		for (ApiAttribute attr : getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(sourceEnvId), ApiAttributeKind.VARIABLE)) {
			ApiAttribute clone = new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(copy.getId()), ApiAttributeKind.VARIABLE,
					attr.getName(), attr.getValue(), attr.isSecret());
			clone.setEnabled(attr.isEnabled());
			clone.setSortOrder(attr.getSortOrder());
			copiedAttributes.add(clone);
		}
		replaceAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(copy.getId()), ApiAttributeKind.VARIABLE, copiedAttributes);
		return copy;
	}// duplicateEnvironment

	/**
	 * Resolves an {@code API_ENVIRONMENT} row by its import-provenance identity - the idempotent-reimport
	 * lookup ({@code (api_id, source_type, source_key)}) described in the sprint doc's section 13.1.
	 * Returns {@code null} when no such row exists yet (the importer should then insert a new one).
	 */
	public ApiEnvironment findEnvironmentBySource(String apiId, String sourceType, String sourceKey) throws BroadSQLException {
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_ENVIRONMENT WHERE API_ID=? AND SOURCE_TYPE=? AND SOURCE_KEY=?")) {
			stat.setString(1, apiId);
			stat.setString(2, sourceType);
			stat.setString(3, sourceKey);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? mapEnvironment(rs) : null;
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// findEnvironmentBySource

	// ------------------------------------------------------------------------------------------
	// API_ENDPOINT_GROUP
	// ------------------------------------------------------------------------------------------

	private ApiEndpointGroup mapGroup(ResultSet rs) throws SQLException {
		ApiEndpointGroup group = new ApiEndpointGroup();
		group.setId(rs.getInt("ID"));
		group.setApiVersionId(rs.getInt("API_VERSION_ID"));
		int parentGroupId = rs.getInt("PARENT_GROUP_ID");
		group.setParentGroupId(rs.wasNull() ? null : parentGroupId);
		group.setName(rs.getString("NAME"));
		group.setSortOrder(rs.getInt("SORT_ORDER"));
		group.setSourceType(rs.getString("SOURCE_TYPE"));
		group.setSourceKey(rs.getString("SOURCE_KEY"));
		group.setStatusId(rs.getString("STATUS_ID"));
		return group;
	}

	public void saveEndpointGroup(ApiEndpointGroup group) throws BroadSQLException {
		try (Connection conn = openConnection()) {
			if (group.getId() == null) {
				try (PreparedStatement insert = conn.prepareStatement(
						"INSERT INTO API_ENDPOINT_GROUP (API_VERSION_ID, PARENT_GROUP_ID, NAME, SORT_ORDER, SOURCE_TYPE, SOURCE_KEY, STATUS_ID) VALUES (?,?,?,?,?,?,?)",
						Statement.RETURN_GENERATED_KEYS)) {
					insert.setInt(1, group.getApiVersionId());
					setNullableInt(insert, 2, group.getParentGroupId());
					insert.setString(3, group.getName());
					insert.setInt(4, group.getSortOrder());
					insert.setString(5, group.getSourceType());
					insert.setString(6, group.getSourceKey());
					insert.setString(7, StringUtils.defaultIfBlank(group.getStatusId(), DatabaseDefinition.STATUS_ACTIVE));
					insert.executeUpdate();
					try (ResultSet keys = insert.getGeneratedKeys()) {
						if (keys.next()) {
							group.setId(keys.getInt(1));
						}
					}
				}
			} else {
				try (PreparedStatement update = conn.prepareStatement(
						"UPDATE API_ENDPOINT_GROUP SET API_VERSION_ID=?, PARENT_GROUP_ID=?, NAME=?, SORT_ORDER=?, SOURCE_TYPE=?, SOURCE_KEY=?, STATUS_ID=? WHERE ID=?")) {
					update.setInt(1, group.getApiVersionId());
					setNullableInt(update, 2, group.getParentGroupId());
					update.setString(3, group.getName());
					update.setInt(4, group.getSortOrder());
					update.setString(5, group.getSourceType());
					update.setString(6, group.getSourceKey());
					update.setString(7, StringUtils.defaultIfBlank(group.getStatusId(), DatabaseDefinition.STATUS_ACTIVE));
					update.setInt(8, group.getId());
					update.executeUpdate();
				}
			}
			conn.commit();
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// saveEndpointGroup

	public List<ApiEndpointGroup> getGroupsForVersion(int apiVersionId) throws BroadSQLException {
		List<ApiEndpointGroup> result = new ArrayList<>();
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_ENDPOINT_GROUP WHERE API_VERSION_ID=? ORDER BY SORT_ORDER, ID")) {
			stat.setInt(1, apiVersionId);
			try (ResultSet rs = stat.executeQuery()) {
				while (rs.next()) {
					result.add(mapGroup(rs));
				}
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
		return result;
	}// getGroupsForVersion

	/**
	 * Walks {@code groupId}'s ancestor chain via {@code PARENT_GROUP_ID} and returns it ordered root
	 * (outermost folder) to leaf - the order both {@link ApiVariableResolver} (variable precedence) and
	 * {@code ApiEndpointRequestBuilder} (header precedence) need so a more specific folder correctly
	 * overrides a less specific ancestor's value, matching Bruno's own folder inheritance. Shared here
	 * rather than duplicated in each caller.
	 */
	public List<ApiEndpointGroup> groupChainRootToLeaf(Integer groupId) throws BroadSQLException {
		List<ApiEndpointGroup> leafToRoot = new ArrayList<>();
		Integer currentId = groupId;
		// A cycle in PARENT_GROUP_ID should never occur (nothing in this vault can create one), but a
		// defensive cap avoids an infinite loop if the data is ever corrupted by hand.
		int maxDepth = 100;
		while (currentId != null && maxDepth-- > 0) {
			ApiEndpointGroup group = findGroupById(currentId);
			if (group == null) {
				break;
			}
			leafToRoot.add(group);
			currentId = group.getParentGroupId();
		}
		Collections.reverse(leafToRoot);
		return leafToRoot;
	}// groupChainRootToLeaf

	/** A single group by its own ID, regardless of version - used by {@link ApiVariableResolver} to walk a group's ancestor chain. */
	public ApiEndpointGroup findGroupById(int groupId) throws BroadSQLException {
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_ENDPOINT_GROUP WHERE ID=?")) {
			stat.setInt(1, groupId);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? mapGroup(rs) : null;
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// findGroupById

	public ApiEndpointGroup findGroupBySource(int apiVersionId, String sourceType, String sourceKey) throws BroadSQLException {
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_ENDPOINT_GROUP WHERE API_VERSION_ID=? AND SOURCE_TYPE=? AND SOURCE_KEY=?")) {
			stat.setInt(1, apiVersionId);
			stat.setString(2, sourceType);
			stat.setString(3, sourceKey);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? mapGroup(rs) : null;
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// findGroupBySource

	// ------------------------------------------------------------------------------------------
	// API_ENDPOINT
	// ------------------------------------------------------------------------------------------

	private ApiEndpoint mapEndpoint(ResultSet rs) throws SQLException {
		ApiEndpoint endpoint = new ApiEndpoint();
		endpoint.setId(rs.getInt("ID"));
		endpoint.setApiVersionId(rs.getInt("API_VERSION_ID"));
		int groupId = rs.getInt("GROUP_ID");
		endpoint.setGroupId(rs.wasNull() ? null : groupId);
		endpoint.setName(rs.getString("NAME"));
		endpoint.setMethod(rs.getString("METHOD"));
		endpoint.setEndpointPath(rs.getString("ENDPOINT_PATH"));
		endpoint.setBodyMode(rs.getString("BODY_MODE"));
		endpoint.setBodyContent(rs.getString("BODY_CONTENT"));
		endpoint.setSortOrder(rs.getInt("SORT_ORDER"));
		endpoint.setSourceType(rs.getString("SOURCE_TYPE"));
		endpoint.setSourceKey(rs.getString("SOURCE_KEY"));
		endpoint.setAlias(rs.getString("ALIAS"));
		endpoint.setStatusId(rs.getString("STATUS_ID"));
		return endpoint;
	}

	/**
	 * Inserts or updates {@code endpoint}, first enforcing alias syntax ({@link ApiAliasValidator}, only
	 * when non-blank) and the amendment's mandatory global, case-insensitive alias uniqueness (section 6):
	 * if {@code endpoint.getAlias()} is already assigned to a <em>different</em> active endpoint, the save
	 * is refused with the exact wording the amendment specifies, before anything is written.
	 */
	public void saveEndpoint(ApiEndpoint endpoint) throws BroadSQLException {
		assertAliasIsValidAndAvailable(endpoint);
		try (Connection conn = openConnection()) {
			if (endpoint.getId() == null) {
				try (PreparedStatement insert = conn.prepareStatement(
						"INSERT INTO API_ENDPOINT (API_VERSION_ID, GROUP_ID, NAME, METHOD, ENDPOINT_PATH, BODY_MODE, BODY_CONTENT, SORT_ORDER, SOURCE_TYPE, SOURCE_KEY, ALIAS, STATUS_ID) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
						Statement.RETURN_GENERATED_KEYS)) {
					insert.setInt(1, endpoint.getApiVersionId());
					setNullableInt(insert, 2, endpoint.getGroupId());
					insert.setString(3, endpoint.getName());
					insert.setString(4, endpoint.getMethod());
					insert.setString(5, endpoint.getEndpointPath());
					insert.setString(6, endpoint.getBodyMode());
					insert.setString(7, endpoint.getBodyContent());
					insert.setInt(8, endpoint.getSortOrder());
					insert.setString(9, endpoint.getSourceType());
					insert.setString(10, endpoint.getSourceKey());
					insert.setString(11, StringUtils.trimToNull(endpoint.getAlias()));
					insert.setString(12, StringUtils.defaultIfBlank(endpoint.getStatusId(), DatabaseDefinition.STATUS_ACTIVE));
					insert.executeUpdate();
					try (ResultSet keys = insert.getGeneratedKeys()) {
						if (keys.next()) {
							endpoint.setId(keys.getInt(1));
						}
					}
				}
			} else {
				try (PreparedStatement update = conn.prepareStatement(
						"UPDATE API_ENDPOINT SET API_VERSION_ID=?, GROUP_ID=?, NAME=?, METHOD=?, ENDPOINT_PATH=?, BODY_MODE=?, BODY_CONTENT=?, SORT_ORDER=?, SOURCE_TYPE=?, SOURCE_KEY=?, ALIAS=?, STATUS_ID=? WHERE ID=?")) {
					update.setInt(1, endpoint.getApiVersionId());
					setNullableInt(update, 2, endpoint.getGroupId());
					update.setString(3, endpoint.getName());
					update.setString(4, endpoint.getMethod());
					update.setString(5, endpoint.getEndpointPath());
					update.setString(6, endpoint.getBodyMode());
					update.setString(7, endpoint.getBodyContent());
					update.setInt(8, endpoint.getSortOrder());
					update.setString(9, endpoint.getSourceType());
					update.setString(10, endpoint.getSourceKey());
					update.setString(11, StringUtils.trimToNull(endpoint.getAlias()));
					update.setString(12, StringUtils.defaultIfBlank(endpoint.getStatusId(), DatabaseDefinition.STATUS_ACTIVE));
					update.setInt(13, endpoint.getId());
					update.executeUpdate();
				}
			}
			conn.commit();
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// saveEndpoint

	private void assertAliasIsValidAndAvailable(ApiEndpoint endpoint) throws BroadSQLException {
		String alias = StringUtils.trimToNull(endpoint.getAlias());
		if (alias == null) {
			return;
		}
		if (!ApiAliasValidator.isValid(alias)) {
			throw new BroadSQLException("Alias '" + alias + "' is not a valid BroadSQL identifier (must match [A-Za-z_][A-Za-z0-9_]*).");
		}
		String apiId = findApiIdForVersion(endpoint.getApiVersionId());
		ApiEndpoint existing = findEndpointByAlias(apiId, alias);
		if (existing != null && !existing.getId().equals(endpoint.getId())) {
			throw new BroadSQLException("Alias " + alias + " is already assigned to another API endpoint.");
		}
	}// assertAliasIsValidAndAvailable

	/**
	 * Resolves the owning API's identifier from an {@code API_VERSION} id - used to scope alias
	 * uniqueness (SPRINT XT02-7B) to one API without adding a redundant {@code API_ID} column directly
	 * on {@code API_ENDPOINT}.
	 */
	/** Package-private (not {@code private}): also called by {@link ApiEndpointReferenceResolver} to verify a numeric-id match's owning API. */
	String findApiIdForVersion(Integer apiVersionId) throws BroadSQLException {
		if (apiVersionId == null) {
			return null;
		}
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT API_ID FROM API_VERSION WHERE ID=?")) {
			stat.setInt(1, apiVersionId);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? rs.getString("API_ID") : null;
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// findApiIdForVersion

	/**
	 * Resolves an endpoint by its BroadSQL alias within one API - case-insensitive, unique per API, not
	 * globally (docs/Amendment - Endpoint Aliases and Future Scriptability.md, section 6, revised by
	 * SPRINT XT02-7B: the same alias may now be reused by a different API, since interactive execution
	 * always resolves an alias within an explicitly active {@code CONNECT API} context, so no ambiguity
	 * is possible), and restricted to currently-active endpoints only: deactivating or permanently
	 * deleting an endpoint immediately frees its alias for reuse elsewhere, since a soft-deleted
	 * endpoint's {@code STATUS_ID} is no longer {@code ACTIVE} (amendment section 12, "Endpoint
	 * deletion").
	 */
	public ApiEndpoint findEndpointByAlias(String apiId, String alias) throws BroadSQLException {
		if (StringUtils.isBlank(alias) || StringUtils.isBlank(apiId)) {
			return null;
		}
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement(
						"SELECT e.* FROM API_ENDPOINT e JOIN API_VERSION v ON e.API_VERSION_ID = v.ID "
								+ "WHERE v.API_ID = ? AND UPPER(e.ALIAS)=UPPER(?) AND e.STATUS_ID='" + DatabaseDefinition.STATUS_ACTIVE + "'")) {
			stat.setString(1, apiId);
			stat.setString(2, alias);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? mapEndpoint(rs) : null;
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// findEndpointByAlias

	/**
	 * Resolves every currently-active endpoint of one API whose {@code NAME} matches {@code name}
	 * case-insensitively - SPRINT XT02B, section 6: unlike {@link #findEndpointByAlias}, an endpoint
	 * name is <b>not</b> guaranteed unique within an API (duplicate names across different folders are
	 * normal), so this deliberately returns every match rather than the first one - it is up to the
	 * caller ({@link com.upandcoding.broadsql.dao.api.ApiEndpointReferenceResolver}) to treat more than one
	 * result as ambiguous rather than guessing.
	 */
	public List<ApiEndpoint> findEndpointByName(String apiId, String name) throws BroadSQLException {
		List<ApiEndpoint> result = new ArrayList<>();
		if (StringUtils.isBlank(name) || StringUtils.isBlank(apiId)) {
			return result;
		}
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement(
						"SELECT e.* FROM API_ENDPOINT e JOIN API_VERSION v ON e.API_VERSION_ID = v.ID "
								+ "WHERE v.API_ID = ? AND UPPER(e.NAME)=UPPER(?) AND e.STATUS_ID='" + DatabaseDefinition.STATUS_ACTIVE + "'")) {
			stat.setString(1, apiId);
			stat.setString(2, name);
			try (ResultSet rs = stat.executeQuery()) {
				while (rs.next()) {
					result.add(mapEndpoint(rs));
				}
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
		return result;
	}// findEndpointByName

	public List<ApiEndpoint> getEndpointsForVersion(int apiVersionId) throws BroadSQLException {
		List<ApiEndpoint> result = new ArrayList<>();
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_ENDPOINT WHERE API_VERSION_ID=? ORDER BY SORT_ORDER, ID")) {
			stat.setInt(1, apiVersionId);
			try (ResultSet rs = stat.executeQuery()) {
				while (rs.next()) {
					result.add(mapEndpoint(rs));
				}
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
		return result;
	}// getEndpointsForVersion

	public List<ApiEndpoint> getEndpointsForGroup(int groupId) throws BroadSQLException {
		List<ApiEndpoint> result = new ArrayList<>();
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_ENDPOINT WHERE GROUP_ID=? ORDER BY SORT_ORDER, ID")) {
			stat.setInt(1, groupId);
			try (ResultSet rs = stat.executeQuery()) {
				while (rs.next()) {
					result.add(mapEndpoint(rs));
				}
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
		return result;
	}// getEndpointsForGroup

	public ApiEndpoint findEndpointBySource(int apiVersionId, String sourceType, String sourceKey) throws BroadSQLException {
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_ENDPOINT WHERE API_VERSION_ID=? AND SOURCE_TYPE=? AND SOURCE_KEY=?")) {
			stat.setInt(1, apiVersionId);
			stat.setString(2, sourceType);
			stat.setString(3, sourceKey);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? mapEndpoint(rs) : null;
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// findEndpointBySource

	/** A single endpoint by its own ID, regardless of status - used by the CONFIG API GUI and the lifecycle methods below. */
	public ApiEndpoint findEndpointById(int id) throws BroadSQLException {
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_ENDPOINT WHERE ID=?")) {
			stat.setInt(1, id);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? mapEndpoint(rs) : null;
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// findEndpointById

	// ------------------------------------------------------------------------------------------
	// API_AUTH
	// ------------------------------------------------------------------------------------------

	private ApiAuthConfig mapAuth(ResultSet rs) throws SQLException {
		ApiAuthConfig auth = new ApiAuthConfig();
		auth.setId(rs.getInt("ID"));
		auth.setOwnerType(ApiOwnerType.valueOf(rs.getString("OWNER_TYPE")));
		auth.setOwnerId(rs.getString("OWNER_ID"));
		auth.setAuthType(ApiAuthType.valueOf(rs.getString("AUTH_TYPE")));
		return auth;
	}

	/**
	 * Inserts or updates the single {@code API_AUTH} row for {@code (ownerType, ownerId)} - an owner has
	 * at most one authentication configuration, so this replaces rather than accumulates.
	 */
	public void saveAuth(ApiAuthConfig auth) throws BroadSQLException {
		try (Connection conn = openConnection()) {
			Integer existingId = null;
			try (PreparedStatement check = conn.prepareStatement("SELECT ID FROM API_AUTH WHERE OWNER_TYPE=? AND OWNER_ID=?")) {
				check.setString(1, auth.getOwnerType().name());
				check.setString(2, auth.getOwnerId());
				try (ResultSet rs = check.executeQuery()) {
					if (rs.next()) {
						existingId = rs.getInt(1);
					}
				}
			}
			if (existingId != null) {
				try (PreparedStatement update = conn.prepareStatement("UPDATE API_AUTH SET AUTH_TYPE=? WHERE ID=?")) {
					update.setString(1, auth.getAuthType().name());
					update.setInt(2, existingId);
					update.executeUpdate();
				}
				auth.setId(existingId);
			} else {
				try (PreparedStatement insert = conn.prepareStatement(
						"INSERT INTO API_AUTH (OWNER_TYPE, OWNER_ID, AUTH_TYPE) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
					insert.setString(1, auth.getOwnerType().name());
					insert.setString(2, auth.getOwnerId());
					insert.setString(3, auth.getAuthType().name());
					insert.executeUpdate();
					try (ResultSet keys = insert.getGeneratedKeys()) {
						if (keys.next()) {
							auth.setId(keys.getInt(1));
						}
					}
				}
			}
			conn.commit();
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// saveAuth

	/**
	 * Deletes the {@code API_AUTH} row (and its owned {@code PROPERTY} attributes) for
	 * {@code (ownerType, ownerId)}, if one exists - a no-op otherwise. The persistence side of
	 * "Inherit" (docs/SPRINT XT02-sub sprint 5 - API Configuration GUI + Bruno YAML Round-trip.md,
	 * section 22: "Inherit means no local auth row") - used by the CONFIG API GUI's Authentication
	 * editor when the user switches an owner that previously had an explicit auth type back to Inherit.
	 * Before this method existed, {@link #saveAuth} could create or update a row but nothing could
	 * remove one, so a GUI switching back to Inherit had no way to actually clear the owner's
	 * authentication - it would have silently kept enforcing the old explicit type.
	 */
	public void deleteAuth(ApiOwnerType ownerType, String ownerId) throws BroadSQLException {
		ApiAuthConfig auth = getAuth(ownerType, ownerId);
		if (auth == null) {
			return;
		}
		execUpdate("DELETE FROM API_ATTRIBUTE WHERE OWNER_TYPE=? AND OWNER_ID=?", ApiOwnerType.AUTH.name(), String.valueOf(auth.getId()));
		execUpdate("DELETE FROM API_AUTH WHERE ID=?", auth.getId());
	}// deleteAuth

	public ApiAuthConfig getAuth(ApiOwnerType ownerType, String ownerId) throws BroadSQLException {
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_AUTH WHERE OWNER_TYPE=? AND OWNER_ID=?")) {
			stat.setString(1, ownerType.name());
			stat.setString(2, ownerId);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? mapAuth(rs) : null;
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// getAuth

	// ------------------------------------------------------------------------------------------
	// API_ATTRIBUTE
	// ------------------------------------------------------------------------------------------

	private ApiAttribute mapAttribute(ResultSet rs) throws SQLException {
		ApiAttribute attr = new ApiAttribute();
		attr.setId(rs.getInt("ID"));
		attr.setOwnerType(ApiOwnerType.valueOf(rs.getString("OWNER_TYPE")));
		attr.setOwnerId(rs.getString("OWNER_ID"));
		attr.setKind(ApiAttributeKind.valueOf(rs.getString("ATTR_KIND")));
		attr.setName(rs.getString("NAME"));
		attr.setValue(rs.getString("ATTR_VALUE"));
		attr.setSecret(rs.getBoolean("SECRET"));
		attr.setEnabled(rs.getBoolean("ENABLED"));
		attr.setSortOrder(rs.getInt("SORT_ORDER"));
		attr.setRequired(rs.getBoolean("REQUIRED"));
		String paramType = rs.getString("PARAM_TYPE");
		attr.setParamType(paramType == null ? "string" : paramType);
		attr.setDefaultValue(rs.getString("DEFAULT_VALUE"));
		attr.setAllowedValues(rs.getString("ALLOWED_VALUES"));
		attr.setDescription(rs.getString("DESCR"));
		return attr;
	}

	public void saveAttribute(ApiAttribute attr) throws BroadSQLException {
		try (Connection conn = openConnection()) {
			if (attr.getId() == null) {
				try (PreparedStatement insert = conn.prepareStatement(
						"INSERT INTO API_ATTRIBUTE (OWNER_TYPE, OWNER_ID, ATTR_KIND, NAME, ATTR_VALUE, SECRET, ENABLED, SORT_ORDER, REQUIRED, PARAM_TYPE, DEFAULT_VALUE, ALLOWED_VALUES, DESCR) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
						Statement.RETURN_GENERATED_KEYS)) {
					insert.setString(1, attr.getOwnerType().name());
					insert.setString(2, attr.getOwnerId());
					insert.setString(3, attr.getKind().name());
					insert.setString(4, attr.getName());
					insert.setString(5, attr.getValue());
					insert.setBoolean(6, attr.isSecret());
					insert.setBoolean(7, attr.isEnabled());
					insert.setInt(8, attr.getSortOrder());
					insert.setBoolean(9, attr.isRequired());
					insert.setString(10, attr.getParamType());
					insert.setString(11, attr.getDefaultValue());
					insert.setString(12, attr.getAllowedValues());
					insert.setString(13, attr.getDescription());
					insert.executeUpdate();
					try (ResultSet keys = insert.getGeneratedKeys()) {
						if (keys.next()) {
							attr.setId(keys.getInt(1));
						}
					}
				}
			} else {
				try (PreparedStatement update = conn.prepareStatement(
						"UPDATE API_ATTRIBUTE SET OWNER_TYPE=?, OWNER_ID=?, ATTR_KIND=?, NAME=?, ATTR_VALUE=?, SECRET=?, ENABLED=?, SORT_ORDER=?, REQUIRED=?, PARAM_TYPE=?, DEFAULT_VALUE=?, ALLOWED_VALUES=?, DESCR=? WHERE ID=?")) {
					update.setString(1, attr.getOwnerType().name());
					update.setString(2, attr.getOwnerId());
					update.setString(3, attr.getKind().name());
					update.setString(4, attr.getName());
					update.setString(5, attr.getValue());
					update.setBoolean(6, attr.isSecret());
					update.setBoolean(7, attr.isEnabled());
					update.setInt(8, attr.getSortOrder());
					update.setBoolean(9, attr.isRequired());
					update.setString(10, attr.getParamType());
					update.setString(11, attr.getDefaultValue());
					update.setString(12, attr.getAllowedValues());
					update.setString(13, attr.getDescription());
					update.setInt(14, attr.getId());
					update.executeUpdate();
				}
			}
			conn.commit();
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// saveAttribute

	/**
	 * SPRINT XT02B, section 5 ({@code VAR ... PERSIST}): finds an existing {@code VARIABLE}-kind
	 * attribute by case-insensitive name within {@code (ownerType, ownerId)} and updates only its
	 * {@code value}, preserving every other field ({@code enabled}, {@code secret}, {@code description},
	 * {@code sortOrder}, ...) untouched; if none exists, inserts a new one with {@code enabled=true}
	 * (matching {@link ApiAttribute}'s own default for a freshly-created attribute). Deliberately not
	 * {@link #replaceAttributes} (delete-all-then-insert), which would wipe every sibling variable of
	 * that owner.
	 */
	public void upsertVariable(ApiOwnerType ownerType, String ownerId, String name, String value) throws BroadSQLException {
		ApiAttribute existing = null;
		for (ApiAttribute attr : getAttributes(ownerType, ownerId, ApiAttributeKind.VARIABLE)) {
			if (attr.getName() != null && attr.getName().equalsIgnoreCase(name)) {
				existing = attr;
				break;
			}
		}
		if (existing != null) {
			existing.setValue(value);
			saveAttribute(existing);
		} else {
			saveAttribute(new ApiAttribute(ownerType, ownerId, ApiAttributeKind.VARIABLE, name, value, false));
		}
	}// upsertVariable

	public List<ApiAttribute> getAttributes(ApiOwnerType ownerType, String ownerId, ApiAttributeKind kind) throws BroadSQLException {
		List<ApiAttribute> result = new ArrayList<>();
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement(
						"SELECT * FROM API_ATTRIBUTE WHERE OWNER_TYPE=? AND OWNER_ID=? AND ATTR_KIND=? ORDER BY SORT_ORDER, ID")) {
			stat.setString(1, ownerType.name());
			stat.setString(2, ownerId);
			stat.setString(3, kind.name());
			try (ResultSet rs = stat.executeQuery()) {
				while (rs.next()) {
					result.add(mapAttribute(rs));
				}
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
		return result;
	}// getAttributes

	/**
	 * Replaces every {@code (ownerType, ownerId, kind)} attribute row with {@code newValues} - deletes
	 * what is there, then inserts the given list, all in one transaction. The re-import convenience: a
	 * Bruno import re-run does not need to diff a folder's variables one by one, it simply re-asserts the
	 * full set every time.
	 */
	public void replaceAttributes(ApiOwnerType ownerType, String ownerId, ApiAttributeKind kind, List<ApiAttribute> newValues) throws BroadSQLException {
		try (Connection conn = openConnection()) {
			try (PreparedStatement delete = conn.prepareStatement("DELETE FROM API_ATTRIBUTE WHERE OWNER_TYPE=? AND OWNER_ID=? AND ATTR_KIND=?")) {
				delete.setString(1, ownerType.name());
				delete.setString(2, ownerId);
				delete.setString(3, kind.name());
				delete.executeUpdate();
			}
			try (PreparedStatement insert = conn.prepareStatement(
					"INSERT INTO API_ATTRIBUTE (OWNER_TYPE, OWNER_ID, ATTR_KIND, NAME, ATTR_VALUE, SECRET, ENABLED, SORT_ORDER, REQUIRED, PARAM_TYPE, DEFAULT_VALUE, ALLOWED_VALUES, DESCR) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
				int sortOrder = 0;
				for (ApiAttribute attr : newValues) {
					insert.setString(1, ownerType.name());
					insert.setString(2, ownerId);
					insert.setString(3, kind.name());
					insert.setString(4, attr.getName());
					insert.setString(5, attr.getValue());
					insert.setBoolean(6, attr.isSecret());
					insert.setBoolean(7, attr.isEnabled());
					insert.setInt(8, sortOrder++);
					insert.setBoolean(9, attr.isRequired());
					insert.setString(10, attr.getParamType());
					insert.setString(11, attr.getDefaultValue());
					insert.setString(12, attr.getAllowedValues());
					insert.setString(13, attr.getDescription());
					insert.executeUpdate();
				}
			}
			conn.commit();
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// replaceAttributes

	// ------------------------------------------------------------------------------------------
	// API_IMPORT_SOURCE
	// ------------------------------------------------------------------------------------------

	private ApiImportSource mapImportSource(ResultSet rs) throws SQLException {
		ApiImportSource source = new ApiImportSource();
		source.setId(rs.getInt("ID"));
		source.setApiId(rs.getString("API_ID"));
		source.setSourceType(rs.getString("SOURCE_TYPE"));
		source.setSourceLocation(rs.getString("SOURCE_LOCATION"));
		Timestamp ts = rs.getTimestamp("LAST_IMPORTED_AT");
		source.setLastImportedAt(ts == null ? null : ts.toLocalDateTime());
		return source;
	}

	/**
	 * Records/updates {@code apiId}'s current import provenance - one row per API, upserted by
	 * {@code apiId} (an API is considered to come from at most one current import source at a time).
	 */
	public void saveImportSource(ApiImportSource source) throws BroadSQLException {
		try (Connection conn = openConnection()) {
			Integer existingId = null;
			try (PreparedStatement check = conn.prepareStatement("SELECT ID FROM API_IMPORT_SOURCE WHERE API_ID=?")) {
				check.setString(1, source.getApiId());
				try (ResultSet rs = check.executeQuery()) {
					if (rs.next()) {
						existingId = rs.getInt(1);
					}
				}
			}
			Timestamp ts = source.getLastImportedAt() == null ? Timestamp.valueOf(LocalDateTime.now()) : Timestamp.valueOf(source.getLastImportedAt());
			if (existingId != null) {
				try (PreparedStatement update = conn.prepareStatement(
						"UPDATE API_IMPORT_SOURCE SET SOURCE_TYPE=?, SOURCE_LOCATION=?, LAST_IMPORTED_AT=? WHERE ID=?")) {
					update.setString(1, source.getSourceType());
					update.setString(2, source.getSourceLocation());
					update.setTimestamp(3, ts);
					update.setInt(4, existingId);
					update.executeUpdate();
				}
				source.setId(existingId);
			} else {
				try (PreparedStatement insert = conn.prepareStatement(
						"INSERT INTO API_IMPORT_SOURCE (API_ID, SOURCE_TYPE, SOURCE_LOCATION, LAST_IMPORTED_AT) VALUES (?,?,?,?)",
						Statement.RETURN_GENERATED_KEYS)) {
					insert.setString(1, source.getApiId());
					insert.setString(2, source.getSourceType());
					insert.setString(3, source.getSourceLocation());
					insert.setTimestamp(4, ts);
					insert.executeUpdate();
					try (ResultSet keys = insert.getGeneratedKeys()) {
						if (keys.next()) {
							source.setId(keys.getInt(1));
						}
					}
				}
			}
			conn.commit();
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// saveImportSource

	public ApiImportSource getImportSource(String apiId) throws BroadSQLException {
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT * FROM API_IMPORT_SOURCE WHERE API_ID=?")) {
			stat.setString(1, apiId);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? mapImportSource(rs) : null;
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// getImportSource

	// ------------------------------------------------------------------------------------------
	// Lifecycle: deactivate / reactivate / permanently delete (SPRINT XT02 sub-sprint 5)
	//
	// Mirrors DatabaseDefinitionsVault's soft-delete-then-hard-delete convention for every table in
	// this vault (see that class's softDeleteGroup/hardDeleteGroup javadoc for the pattern this
	// follows): a "Deactivate" flips STATUS_ID to INACTIVE and is always reversible via "Reactivate";
	// a permanent delete is only ever legal on an already-inactive row, and refuses - naming exactly
	// what is blocking it - when something still depends on it, except for the API level itself (see
	// hardDeleteApi's javadoc for why that one deliberately cascades instead of refusing).
	// ------------------------------------------------------------------------------------------

	/** Runs one parameterized, no-result-set update statement in its own connection/transaction - the shared plumbing every lifecycle method below uses. */
	private void execUpdate(String sql, Object... params) throws BroadSQLException {
		try (Connection conn = openConnection(); PreparedStatement stat = conn.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				stat.setObject(i + 1, params[i]);
			}
			stat.executeUpdate();
			conn.commit();
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// execUpdate

	/**
	 * Deletes every {@code API_ATTRIBUTE} row owned by {@code (ownerType, ownerId)}, plus - if one
	 * exists - the {@code API_AUTH} row for that same owner and its own {@code AUTH}-owned
	 * {@code PROPERTY} attributes. Shared cleanup step for every {@code hardDelete*} method below, so
	 * a permanently deleted API/environment/group/endpoint never leaves orphaned attribute or auth rows
	 * behind (the {@code OWNER_ID}/{@code OWNER_TYPE} columns are plain {@code VARCHAR}, not enforced
	 * foreign keys, so nothing else would ever clean these up).
	 */
	private void deleteOwnedAttributesAndAuth(ApiOwnerType ownerType, String ownerId) throws BroadSQLException {
		ApiAuthConfig auth = getAuth(ownerType, ownerId);
		if (auth != null) {
			execUpdate("DELETE FROM API_ATTRIBUTE WHERE OWNER_TYPE=? AND OWNER_ID=?", ApiOwnerType.AUTH.name(), String.valueOf(auth.getId()));
			execUpdate("DELETE FROM API_AUTH WHERE ID=?", auth.getId());
		}
		execUpdate("DELETE FROM API_ATTRIBUTE WHERE OWNER_TYPE=? AND OWNER_ID=?", ownerType.name(), ownerId);
	}// deleteOwnedAttributesAndAuth

	// --- API ---

	private String findApiStatus(String apiId) throws BroadSQLException {
		try (Connection conn = openConnection();
				PreparedStatement stat = conn.prepareStatement("SELECT STATUS_ID FROM API WHERE ID=?")) {
			stat.setString(1, apiId);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}// findApiStatus

	/** Soft-deletes (status to {@code INACTIVE}) an {@code API} row. Reloads the vault so it drops out of {@link #getApis()}/{@link #contains} immediately. */
	public void deactivateApi(String apiId) throws BroadSQLException {
		if (findApiStatus(apiId) == null) {
			throw new BroadSQLException("API '" + apiId + "' does not exist.");
		}
		execUpdate("UPDATE API SET STATUS_ID=? WHERE ID=?", DatabaseDefinition.STATUS_INACTIVE, apiId);
		load();
	}// deactivateApi

	/** Reactivates an inactive {@code API} row (status back to {@code ACTIVE}), unchanged otherwise - the counterpart to {@link #deactivateApi}. */
	public void reactivateApi(String apiId) throws BroadSQLException {
		if (findApiStatus(apiId) == null) {
			throw new BroadSQLException("API '" + apiId + "' does not exist.");
		}
		execUpdate("UPDATE API SET STATUS_ID=? WHERE ID=?", DatabaseDefinition.STATUS_ACTIVE, apiId);
		load();
	}// reactivateApi

	/**
	 * Permanently removes API {@code apiId} and its entire tree - every {@link ApiVersion}, every
	 * {@link ApiEnvironment} (and the {@code VARIABLE} attributes it owns), every
	 * {@link ApiEndpointGroup} (and its owned attributes/auth), every {@link ApiEndpoint} (and its
	 * owned attributes/auth), the API's own attributes/auth, and its {@link ApiImportSource} row.
	 *
	 * <p>Unlike every other {@code hardDelete*} method in this vault, this one deliberately
	 * <b>cascades</b> rather than refusing when things still reference it - the spec's own "Delete API"
	 * UX (docs/SPRINT XT02-sub sprint 5 - API Configuration GUI + Bruno YAML Round-trip.md, section 5)
	 * is explicit that deleting an API is destructive exactly because it removes everything beneath it
	 * ("This will remove: 3 environments, 12 folders, 86 endpoints... This operation cannot be undone.").
	 * An API is the true root of its own tree; unlike an endpoint group (whose children are peer rows
	 * other things could independently reference) or an environment, nothing outside an API's own tree
	 * ever references a piece of it, so cascading here can never orphan something unrelated.
	 *
	 * <p>Only legal on an already-inactive API (deactivate it first via {@link #deactivateApi}), same
	 * two-step convention as every other entity in this vault - the CONFIG API GUI's single "Delete API"
	 * button performs both steps together after one confirmation dialog.
	 */
	public void hardDeleteApi(String apiId) throws BroadSQLException {
		String status = findApiStatus(apiId);
		if (status == null) {
			throw new BroadSQLException("API '" + apiId + "' does not exist.");
		}
		if (DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(status)) {
			throw new BroadSQLException("Cannot permanently delete API '" + apiId + "': it is still active. Deactivate it first.");
		}
		for (ApiEnvironment env : getEnvironmentsForApi(apiId)) {
			deleteOwnedAttributesAndAuth(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()));
			execUpdate("DELETE FROM API_ENVIRONMENT WHERE ID=?", env.getId());
		}
		for (ApiVersion version : getVersionsForApi(apiId)) {
			for (ApiEndpoint endpoint : getEndpointsForVersion(version.getId())) {
				deleteOwnedAttributesAndAuth(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()));
				execUpdate("DELETE FROM API_ENDPOINT WHERE ID=?", endpoint.getId());
			}
			for (ApiEndpointGroup group : getGroupsForVersion(version.getId())) {
				deleteOwnedAttributesAndAuth(ApiOwnerType.GROUP, String.valueOf(group.getId()));
				execUpdate("DELETE FROM API_ENDPOINT_GROUP WHERE ID=?", group.getId());
			}
			execUpdate("DELETE FROM API_VERSION WHERE ID=?", version.getId());
		}
		deleteOwnedAttributesAndAuth(ApiOwnerType.API, apiId);
		execUpdate("DELETE FROM API_IMPORT_SOURCE WHERE API_ID=?", apiId);
		execUpdate("DELETE FROM API WHERE ID=?", apiId);
		load();
	}// hardDeleteApi

	// --- API_ENVIRONMENT ---

	private void setEnvironmentStatus(int id, String statusId) throws BroadSQLException {
		if (findEnvironmentById(id) == null) {
			throw new BroadSQLException("Environment '" + id + "' does not exist.");
		}
		execUpdate("UPDATE API_ENVIRONMENT SET STATUS_ID=? WHERE ID=?", statusId, id);
	}// setEnvironmentStatus

	/** Soft-deletes (status to {@code INACTIVE}) an {@code API_ENVIRONMENT} row - nothing else in this vault references an environment by ID, so no dependency check is needed here. */
	public void deactivateEnvironment(int id) throws BroadSQLException {
		setEnvironmentStatus(id, DatabaseDefinition.STATUS_INACTIVE);
	}// deactivateEnvironment

	/** Reactivates an inactive environment - the counterpart to {@link #deactivateEnvironment}. */
	public void reactivateEnvironment(int id) throws BroadSQLException {
		setEnvironmentStatus(id, DatabaseDefinition.STATUS_ACTIVE);
	}// reactivateEnvironment

	/** Permanently removes an already-inactive {@code API_ENVIRONMENT} row and every {@code VARIABLE} attribute it owns. */
	public void hardDeleteEnvironment(int id) throws BroadSQLException {
		ApiEnvironment env = findEnvironmentById(id);
		if (env == null) {
			throw new BroadSQLException("Environment '" + id + "' does not exist.");
		}
		if (DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(env.getStatusId())) {
			throw new BroadSQLException("Cannot permanently delete environment '" + env.getName() + "': it is still active. Deactivate it first.");
		}
		deleteOwnedAttributesAndAuth(ApiOwnerType.ENVIRONMENT, String.valueOf(id));
		execUpdate("DELETE FROM API_ENVIRONMENT WHERE ID=?", id);
	}// hardDeleteEnvironment

	// --- API_ENDPOINT_GROUP ---

	private void setGroupStatus(int id, String statusId) throws BroadSQLException {
		if (findGroupById(id) == null) {
			throw new BroadSQLException("Folder '" + id + "' does not exist.");
		}
		execUpdate("UPDATE API_ENDPOINT_GROUP SET STATUS_ID=? WHERE ID=?", statusId, id);
	}// setGroupStatus

	/** Soft-deletes (status to {@code INACTIVE}) an {@code API_ENDPOINT_GROUP} row (a folder). */
	public void deactivateEndpointGroup(int id) throws BroadSQLException {
		setGroupStatus(id, DatabaseDefinition.STATUS_INACTIVE);
	}// deactivateEndpointGroup

	/** Reactivates an inactive folder - the counterpart to {@link #deactivateEndpointGroup}. */
	public void reactivateEndpointGroup(int id) throws BroadSQLException {
		setGroupStatus(id, DatabaseDefinition.STATUS_ACTIVE);
	}// reactivateEndpointGroup

	/**
	 * Permanently removes an already-inactive, empty {@code API_ENDPOINT_GROUP} row - refuses, naming
	 * exactly which child folders/endpoints are blocking it, if any still exist underneath (active or
	 * inactive alike, same "still referenced" convention as {@code DatabaseDefinitionsVault#hardDeleteGroup}).
	 * A folder's children are ordinary sibling rows (not exclusively owned the way an API's tree is -
	 * see {@link #hardDeleteApi}'s javadoc), so this deliberately refuses rather than cascading: the
	 * user must delete or move the contents first, exactly like emptying a directory before removing it.
	 */
	public void hardDeleteEndpointGroup(int id) throws BroadSQLException {
		ApiEndpointGroup group = findGroupById(id);
		if (group == null) {
			throw new BroadSQLException("Folder '" + id + "' does not exist.");
		}
		if (DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(group.getStatusId())) {
			throw new BroadSQLException("Cannot permanently delete folder '" + group.getName() + "': it is still active. Deactivate it first.");
		}
		List<String> blockers = findEndpointGroupBlockers(group);
		if (!blockers.isEmpty()) {
			throw new BroadSQLException("Cannot permanently delete folder '" + group.getName() + "': still contains " + blockers.size()
					+ " item(s): " + String.join(", ", blockers) + ". Delete or move them first.");
		}
		deleteOwnedAttributesAndAuth(ApiOwnerType.GROUP, String.valueOf(id));
		execUpdate("DELETE FROM API_ENDPOINT_GROUP WHERE ID=?", id);
	}// hardDeleteEndpointGroup

	/**
	 * Non-mutating: lists exactly what still lives under {@code group} (child folders, then endpoints,
	 * both active and inactive alike) - the same "still referenced" check {@link #hardDeleteEndpointGroup}
	 * itself uses, extracted so a caller can find out *before* changing anything (see
	 * {@link #deleteEndpointGroupPermanently}, SPRINT XT02 verification finding 5).
	 */
	private List<String> findEndpointGroupBlockers(ApiEndpointGroup group) throws BroadSQLException {
		List<String> blockers = new ArrayList<>();
		for (ApiEndpointGroup candidate : getGroupsForVersion(group.getApiVersionId())) {
			if (candidate.getParentGroupId() != null && candidate.getParentGroupId() == group.getId()) {
				blockers.add("folder '" + candidate.getName() + "'");
			}
		}
		for (ApiEndpoint endpoint : getEndpointsForGroup(group.getId())) {
			blockers.add("endpoint '" + endpoint.getName() + "'");
		}
		return blockers;
	}// findEndpointGroupBlockers

	/**
	 * The single vault-level operation behind the CONFIG API GUI's one-click "Delete" action on a folder:
	 * checks for blocking children <b>before</b> mutating anything, then deactivates and permanently
	 * deletes the folder in one call. Before this method existed, the GUI ({@code JApiEndpointTreePanel})
	 * called {@link #deactivateEndpointGroup} followed by {@link #hardDeleteEndpointGroup} as two separate
	 * calls; when the folder turned out to be non-empty, the second call correctly refused, but the first
	 * call's deactivation had already been committed - so a "failed" delete silently left the folder
	 * deactivated (invisible in the active tree) anyway (SPRINT XT02 verification finding 5). Refusing
	 * here happens strictly before any write, so a blocked deletion leaves the folder exactly as it was:
	 * still {@code ACTIVE} (or whatever status it already had), still visible.
	 */
	public void deleteEndpointGroupPermanently(int id) throws BroadSQLException {
		ApiEndpointGroup group = findGroupById(id);
		if (group == null) {
			throw new BroadSQLException("Folder '" + id + "' does not exist.");
		}
		List<String> blockers = findEndpointGroupBlockers(group);
		if (!blockers.isEmpty()) {
			throw new BroadSQLException("Cannot permanently delete folder '" + group.getName() + "': still contains " + blockers.size()
					+ " item(s): " + String.join(", ", blockers) + ". Delete or move them first.");
		}
		if (DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(group.getStatusId())) {
			deactivateEndpointGroup(id);
		}
		hardDeleteEndpointGroup(id);
	}// deleteEndpointGroupPermanently

	// --- API_ENDPOINT ---

	private void setEndpointStatus(int id, String statusId) throws BroadSQLException {
		if (findEndpointById(id) == null) {
			throw new BroadSQLException("Endpoint '" + id + "' does not exist.");
		}
		execUpdate("UPDATE API_ENDPOINT SET STATUS_ID=? WHERE ID=?", statusId, id);
	}// setEndpointStatus

	/**
	 * Soft-deletes (status to {@code INACTIVE}) an {@code API_ENDPOINT} row. This alone already frees
	 * its alias for reuse (docs/Amendment - Endpoint Aliases and Future Scriptability.md, section 12):
	 * {@link #findEndpointByAlias} only ever matches {@code STATUS_ID='ACTIVE'} rows, so a deactivated
	 * endpoint's alias becomes immediately assignable to a different endpoint without needing the
	 * permanent delete below.
	 */
	public void deactivateEndpoint(int id) throws BroadSQLException {
		setEndpointStatus(id, DatabaseDefinition.STATUS_INACTIVE);
	}// deactivateEndpoint

	/**
	 * Reactivates an inactive endpoint - the counterpart to {@link #deactivateEndpoint}. Re-validates
	 * alias uniqueness first, through the exact same {@link #assertAliasIsValidAndAvailable} check
	 * {@link #saveEndpoint} uses, before flipping {@code STATUS_ID} - so if a different endpoint has since
	 * been created or updated to claim this endpoint's alias while it sat deactivated (its alias having
	 * been freed for reuse per {@link #deactivateEndpoint}'s own javadoc), reactivation is refused with the
	 * same "already assigned to another API endpoint" message a conflicting save would produce, and the
	 * endpoint stays inactive - no partial write occurs, since the check runs before any statement executes
	 * (SPRINT XT02 verification finding 3; this was previously a real gap - reactivation only ever updated
	 * {@code STATUS_ID} and never re-checked uniqueness at all).
	 */
	public void reactivateEndpoint(int id) throws BroadSQLException {
		ApiEndpoint endpoint = findEndpointById(id);
		if (endpoint == null) {
			throw new BroadSQLException("Endpoint '" + id + "' does not exist.");
		}
		assertAliasIsValidAndAvailable(endpoint);
		execUpdate("UPDATE API_ENDPOINT SET STATUS_ID=? WHERE ID=?", DatabaseDefinition.STATUS_ACTIVE, id);
	}// reactivateEndpoint

	/** Permanently removes an already-inactive {@code API_ENDPOINT} row and every attribute/auth it owns. */
	public void hardDeleteEndpoint(int id) throws BroadSQLException {
		ApiEndpoint endpoint = findEndpointById(id);
		if (endpoint == null) {
			throw new BroadSQLException("Endpoint '" + id + "' does not exist.");
		}
		if (DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(endpoint.getStatusId())) {
			throw new BroadSQLException("Cannot permanently delete endpoint '" + endpoint.getName() + "': it is still active. Deactivate it first.");
		}
		deleteOwnedAttributesAndAuth(ApiOwnerType.ENDPOINT, String.valueOf(id));
		execUpdate("DELETE FROM API_ENDPOINT WHERE ID=?", id);
	}// hardDeleteEndpoint

	// ------------------------------------------------------------------------------------------

	private void setNullableInt(PreparedStatement stat, int index, Integer value) throws SQLException {
		if (value == null) {
			stat.setNull(index, java.sql.Types.INTEGER);
		} else {
			stat.setInt(index, value);
		}
	}
}
