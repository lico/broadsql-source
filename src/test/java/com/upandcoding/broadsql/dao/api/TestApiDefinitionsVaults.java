package com.upandcoding.broadsql.dao.api;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Builds real, file-backed, AES-encrypted CDF fixtures for {@link ApiDefinitionsVault} tests - the
 * {@code ApiDefinitionsVault} equivalent of
 * {@code com.upandcoding.broadsql.dao.TestDatabaseConnections#newFileBackedVault}, same connection-string/
 * password convention (see docs/TESTS_STRATEGY.md, "Fixture policy": real H2, not a mock).
 */
public final class TestApiDefinitionsVaults {

	private TestApiDefinitionsVaults() {
	}

	/** A fresh, empty CDF file - {@link ApiDefinitionsVault#load()} must create every {@code API*} table itself. */
	public static ApiDefinitionsVault newFileBackedVault() throws BroadSQLException {
		String path = newTempCdfPath();
		String password = "testpwd";
		ApiDefinitionsVault vault = new ApiDefinitionsVault(path, password);
		vault.load();
		return vault;
	}

	/**
	 * A CDF file that already has {@code DatabaseDefinitionsVault}'s own tables (CONNECTIONS/TYPE/
	 * INSTANCE/ENVIRONMENT), pre-populated with one connection row - proves
	 * {@link ApiDefinitionsVault#load()} neither fails against, nor disturbs, data belonging to the
	 * other vault sharing the same physical file (section 13.2 of the sprint doc).
	 */
	public static ApiDefinitionsVault newFileBackedVaultSharingDatabaseTables() throws BroadSQLException {
		String path = newTempCdfPath();
		String password = "testpwd";
		try {
			Class.forName("org.h2.Driver");
			String connectionStr = "jdbc:h2:" + path + ";CIPHER=AES";
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, "ADMIN", aesPassword)) {
				try (Statement stmt = conn.createStatement()) {
					stmt.execute("CREATE TABLE TYPE (ID VARCHAR(20), DRIVER VARCHAR(80), STATUS_ID VARCHAR(10))");
					stmt.execute("CREATE TABLE CONNECTIONS (ID VARCHAR(15), URL VARCHAR(2048), TYPE_ID VARCHAR(20), "
							+ "STATUS_ID VARCHAR(10), NAME VARCHAR(255), ENVIRONMENT_ID VARCHAR(30), INSTANCE_ID VARCHAR(30), "
							+ "USER_NAME VARCHAR(80), USER_PASSWORD VARCHAR(80), COMMENT VARCHAR(255))");
					stmt.execute("CREATE TABLE INSTANCE (ID VARCHAR(30), STATUS_ID VARCHAR(10))");
					stmt.execute("CREATE TABLE ENVIRONMENT (ID VARCHAR(30), STATUS_ID VARCHAR(10))");
					stmt.execute("INSERT INTO TYPE (ID, DRIVER, STATUS_ID) VALUES ('H2', 'org.h2.Driver', 'ACTIVE')");
					stmt.execute("INSERT INTO ENVIRONMENT (ID, STATUS_ID) VALUES ('LOCAL', 'ACTIVE')");
					stmt.execute("INSERT INTO CONNECTIONS (ID, URL, TYPE_ID, STATUS_ID, NAME, ENVIRONMENT_ID) VALUES "
							+ "('SOMEDB', 'jdbc:h2:mem:somedb', 'H2', 'ACTIVE', 'Some DB', 'LOCAL')");
				}
				conn.commit();
			}
		} catch (SQLException | ClassNotFoundException e) {
			throw new BroadSQLException(e);
		}
		ApiDefinitionsVault vault = new ApiDefinitionsVault(path, password);
		vault.load();
		return vault;
	}

	private static String newTempCdfPath() {
		String uniqueName = "api_cdf_" + UUID.randomUUID().toString().replace("-", "");
		return System.getProperty("java.io.tmpdir") + File.separator + uniqueName;
	}
}
