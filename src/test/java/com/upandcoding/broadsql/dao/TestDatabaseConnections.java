package com.upandcoding.broadsql.dao;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Builds a {@link DatabaseConnection} against a fresh, throwaway in-memory H2 database - one
 * unique database per call, never shared between tests and never the file-backed
 * {@code sampleDb/WorldDB.mv.db} (see {@code docs/TESTS_STRATEGY.md}, "Fixture policy": that file is
 * a manual/dev database, not a test fixture). Lives in this exact package (not a sub-package) because
 * {@link DatabaseConnection#consoleSettings} has no public setter - only package-private access.
 *
 * <p>Callers seed whatever schema/rows their test needs via {@code seedSql}, executed in order right
 * after connecting.
 */
public final class TestDatabaseConnections {

	private TestDatabaseConnections() {
	}

	public static DatabaseConnection connectInMemory(String... seedSql) throws BroadSQLException {
		return connectInMemory(defaultConsoleSettings(), seedSql);
	}

	public static DatabaseConnection connectInMemory(ConsoleSettings consoleSettings, String... seedSql) throws BroadSQLException {
		String uniqueName = "testdb_" + UUID.randomUUID().toString().replace("-", "");

		DatabaseDefinition platform = new DatabaseDefinition(uniqueName);
		platform.setDbDriver("org.h2.Driver");
		platform.setDbType(SpringPropertiesConfig.DBTYPE_H2);
		platform.setDbName(uniqueName);
		platform.setUrl("jdbc:h2:mem:" + uniqueName + ";DB_CLOSE_DELAY=-1");

		DatabaseConnection db = new DatabaseConnection();
		db.consoleSettings = consoleSettings;
		db.setPlatform(platform);
		db.connect();

		for (String sql : seedSql) {
			db.executeUpdateQuery(sql);
		}
		return db;
	}

	/**
	 * A {@link DatabaseDefinition} for a fresh, uniquely-named in-memory H2 database, not yet
	 * connected - for tests that need to hand a target platform to production code (e.g.
	 * {@code CommandPull}) rather than connect to it directly.
	 */
	public static DatabaseDefinition newInMemoryTarget(String id) {
		DatabaseDefinition target = new DatabaseDefinition(id);
		target.setDbDriver("org.h2.Driver");
		target.setDbType(SpringPropertiesConfig.DBTYPE_H2);
		target.setDbName(id);
		target.setUrl("jdbc:h2:mem:" + id + "_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
		return target;
	}

	/**
	 * A {@link ConsoleSettings} usable without Spring - none of its {@code @Value} fields are
	 * populated outside a Spring context, so every setting a test might need is set explicitly here
	 * rather than relying on injection.
	 */
	public static ConsoleSettings defaultConsoleSettings() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setAutoCommit(true);
		settings.setDefaultSeparator('\t');
		settings.setOnScreenSeparator('|');
		settings.setMaxRowsOnScreen(1000);
		settings.setMaxRowXlsx(500000);
		settings.setExtractFolderName(System.getProperty("java.io.tmpdir") + File.separator);
		return settings;
	}

	public static void close(DatabaseConnection db) throws BroadSQLException {
		if (db != null && db.isConnected()) {
			db.close();
		}
	}

	/**
	 * Closes the underlying JDBC {@link Connection} directly, bypassing {@link DatabaseConnection#close()}
	 * entirely - simulates a server-side session dying while {@code db} still believes it is connected
	 * (an Oracle session killed by an {@code IDLE_TIME} profile limit, a dropped idle TCP connection...),
	 * so the next {@code db.isConnected()} check reports {@code false} without {@code db} having done
	 * anything to cause it. Used to test the silent-reconnect path in
	 * {@code CommandInterpreter.executeCommand()} (see docs/TODO.md item 7).
	 */
	public static void killUnderlyingConnection(DatabaseConnection db) throws SQLException {
		db.connection.close();
	}

	/**
	 * A small, purpose-built, in-memory CDF (Connections Definition File) - just the
	 * {@code CONNECTIONS}/{@code TYPE} columns {@link DatabaseDefinitionsVault#load()} actually reads,
	 * not the retired {@code archives/dbTestScript.sql} dump (see {@code docs/TESTS_STRATEGY.md},
	 * "Fixture policy") - seeded from the given connection definitions and already {@code load()}ed.
	 * Backed by a {@link SingleConnectionDataSource} with {@code suppressClose=true}, since
	 * {@code DatabaseDefinitionsVault} closes the connection it gets from the data source at the end of
	 * every {@code load()} call - without that, a second {@code load()} (e.g. from {@code RELOAD VAULT})
	 * would fail against an already-closed connection.
	 */
	public static DatabaseDefinitionsVault newVault(DatabaseDefinition... connections) throws BroadSQLException {
		try {
			String uniqueName = "cdf_" + UUID.randomUUID().toString().replace("-", "");
			Connection conn = DriverManager.getConnection("jdbc:h2:mem:" + uniqueName + ";DB_CLOSE_DELAY=-1");
			try (Statement stmt = conn.createStatement()) {
				stmt.execute("CREATE TABLE TYPE (ID VARCHAR(20), DRIVER VARCHAR(80), STATUS_ID VARCHAR(10))");
				stmt.execute("CREATE TABLE CONNECTIONS (ID VARCHAR(15), URL VARCHAR(2048), TYPE_ID VARCHAR(20), "
						+ "STATUS_ID VARCHAR(10), NAME VARCHAR(255), ENVIRONMENT_ID VARCHAR(30), INSTANCE_ID VARCHAR(30), "
						+ "USER_NAME VARCHAR(80), USER_PASSWORD VARCHAR(80), COMMENT VARCHAR(255))");
				stmt.execute("CREATE TABLE INSTANCE (ID VARCHAR(30), STATUS_ID VARCHAR(10))");
				stmt.execute("CREATE TABLE ENVIRONMENT (ID VARCHAR(30), STATUS_ID VARCHAR(10))");
			}

			Set<String> insertedTypes = new HashSet<>();
			try (PreparedStatement typeStmt = conn.prepareStatement(
					"INSERT INTO TYPE (ID, DRIVER, STATUS_ID) VALUES (?, ?, 'ACTIVE')");
					PreparedStatement connStmt = conn.prepareStatement(
							"INSERT INTO CONNECTIONS (ID, URL, TYPE_ID, STATUS_ID, NAME, ENVIRONMENT_ID, INSTANCE_ID, USER_NAME, USER_PASSWORD, COMMENT) "
									+ "VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)")) {
				for (DatabaseDefinition def : connections) {
					if (insertedTypes.add(def.getDbType())) {
						typeStmt.setString(1, def.getDbType());
						typeStmt.setString(2, def.getDbDriver());
						typeStmt.executeUpdate();
					}
					connStmt.setString(1, def.getId());
					connStmt.setString(2, def.getUrl());
					connStmt.setString(3, def.getDbType());
					connStmt.setString(4, def.getDbName());
					connStmt.setString(5, def.getEnvironment());
					connStmt.setString(6, def.getDatabaseGroup());
					connStmt.setString(7, def.getUserName());
					connStmt.setString(8, def.getUserPassword());
					connStmt.setString(9, def.getComment());
					connStmt.executeUpdate();
				}
			}

			DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault(new SingleConnectionDataSource(conn, true));
			vault.load();
			return vault;
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		}
	}

	/**
	 * A real, file-backed, AES-encrypted CDF - unlike {@link #newVault}, this one supports
	 * {@link DatabaseDefinitionsVault#saveDatabaseDefinition} and
	 * {@link DatabaseDefinitionsVault#saveGroup(String, String)}, which only have a {@code fileName}-based
	 * implementation (they open their own {@code jdbc:h2:<fileName>;CIPHER=AES} connection rather than
	 * going through a {@code DataSource}) - needed to test {@code CommandPull}'s "create a brand-new H2
	 * connection" path (standalone, in the built-in {@code LOCAL} Environment - docs/CONNECTION_MODEL.md),
	 * which writes through those two methods. Built with plain SQL rather than
	 * {@link DatabaseDefinitionsVault#createDatabase} - that method is legacy/unfinished (writes its DDL
	 * to a file instead of executing it, per its own source comment) and does not create the
	 * {@code CONNECTIONS}/{@code INSTANCE}/{@code ENVIRONMENT} tables at all. Also the fixture for the
	 * environment-management and login-script CRUD methods added for docs/TODO.md items 13/14, which are
	 * likewise {@code fileName}-based. {@code vault.load()} at the end also runs
	 * {@code seedLocalEnvironmentIfNeeded}, so the returned vault's {@code ENVIRONMENT} table always
	 * already has a {@code LOCAL} row.
	 *
	 * @param groupIds pre-seeded {@code INSTANCE.ID} (Database Group) rows, in insertion order - none if
	 *                  omitted; a test can use these to prove a pre-existing Database Group is never
	 *                  reused for a standalone PULL-created connection
	 */
	public static DatabaseDefinitionsVault newFileBackedVault(String... groupIds) throws BroadSQLException {
		try {
			String uniqueName = "cdf_" + UUID.randomUUID().toString().replace("-", "");
			String path = System.getProperty("java.io.tmpdir") + File.separator + uniqueName;
			String password = "testpwd";

			Class.forName("org.h2.Driver");
			String connectionStr = "jdbc:h2:" + path + ";CIPHER=AES";
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, "ADMIN", aesPassword)) {
				try (Statement stmt = conn.createStatement()) {
					stmt.execute("CREATE TABLE TYPE (ID VARCHAR(20), DRIVER VARCHAR(80), STATUS_ID VARCHAR(10))");
					stmt.execute("CREATE TABLE CONNECTIONS (ID VARCHAR(15), URL VARCHAR(2048), TYPE_ID VARCHAR(20), "
							+ "STATUS_ID VARCHAR(10), NAME VARCHAR(255), ENVIRONMENT_ID VARCHAR(30), INSTANCE_ID VARCHAR(30), "
							+ "USER_NAME VARCHAR(80), USER_PASSWORD VARCHAR(80), COMMENT VARCHAR(255), LAST_MODIFIED TIMESTAMP)");
					stmt.execute("CREATE TABLE INSTANCE (ID VARCHAR(30), DESCR VARCHAR(80), STATUS_ID VARCHAR(10))");
					stmt.execute("CREATE TABLE ENVIRONMENT (ID VARCHAR(30), DESCR VARCHAR(80), STATUS_ID VARCHAR(10))");
					stmt.execute("CREATE TABLE USERS_SCRIPT (SERVER_ID VARCHAR(15), USERS_ID VARCHAR(45), STATUS_ID VARCHAR(10), "
							+ "SQL_COMMAND VARCHAR(4000), SQL_ORDER INT, SQL_COMMENT VARCHAR(255))");
					stmt.execute("INSERT INTO TYPE (ID, DRIVER, STATUS_ID) VALUES ('H2', 'org.h2.Driver', 'ACTIVE')");
				}
				try (PreparedStatement instanceStmt = conn.prepareStatement(
						"INSERT INTO INSTANCE (ID, DESCR, STATUS_ID) VALUES (?, ?, 'ACTIVE')")) {
					for (String groupId : groupIds) {
						instanceStmt.setString(1, groupId);
						instanceStmt.setString(2, groupId);
						instanceStmt.executeUpdate();
					}
				}
				conn.commit();
			}

			DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault(path, password);
			vault.load();
			return vault;
		} catch (SQLException | ClassNotFoundException e) {
			throw new BroadSQLException(e);
		}
	}

	/**
	 * Points {@code db}'s own connections vault (used internally by, e.g.,
	 * {@code testConnectionToExistingPlatform()}) at {@code vault} - a separate field from the
	 * {@code Command}'s own {@code databaseConnectionsVault}, package-private with no public setter.
	 */
	public static void attachVault(DatabaseConnection db, DatabaseDefinitionsVault vault) {
		db.databaseConnectionsCollection = vault;
	}
}
