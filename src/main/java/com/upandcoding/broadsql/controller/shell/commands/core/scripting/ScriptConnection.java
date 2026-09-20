package com.upandcoding.broadsql.controller.shell.commands.core.scripting;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * A single database connection as seen from a {@code JS} script: {@code execute}/{@code executeUpdate}/
 * {@code close}, backed by a raw JDBC {@link Connection} (see {@code docs/LIGHT_SCRIPTING.md}, rule 3).
 *
 * <p>{@link #connect(String, DatabaseDefinitionsVault)} opens a brand new, independent connection -
 * never the interactive session's own {@code sqlDatabase}. It duplicates the driver-branching logic of
 * {@link com.upandcoding.broadsql.dao.DatabaseConnection#openConnection(boolean)} (Derby/H2/everything
 * else) rather than reusing that class directly, for the same reason
 * {@link com.upandcoding.broadsql.dao.compare.TableStructureComparer#compare} already does: that class's
 * {@code @Autowired} collaborators are only populated when Spring creates the bean, which a short-lived
 * connection opened at script-run time is not.
 *
 * <p>{@link #wrapExisting(Connection, String)} instead wraps a connection this object does not own
 * (the {@code db} binding, aliasing whichever connection is already active interactively) - closing a
 * borrowed connection through the script is a deliberate no-op, since the script did not open it and
 * must not close it out from under the interactive session.
 */
public class ScriptConnection {

	private final Connection connection;
	private final String label;
	private final boolean ownsConnection;

	private ScriptConnection(Connection connection, String label, boolean ownsConnection) {
		this.connection = connection;
		this.label = label;
		this.ownsConnection = ownsConnection;
	}

	/** Opens a new, independent connection to {@code name}, resolved from {@code vault}. The caller owns it and must {@link #close()} it. */
	public static ScriptConnection connect(String name, DatabaseDefinitionsVault vault) throws BroadSQLException {
		DatabaseDefinition target = vault.getDatabaseConnection(name);
		if (target == null) {
			throw new BroadSQLException("No database connection for ID: " + name);
		}
		Connection connection = openConnection(target);
		return new ScriptConnection(connection, name, true);
	}

	/** Wraps an already-open connection without taking ownership of it - see the class Javadoc. */
	public static ScriptConnection wrapExisting(Connection connection, String label) {
		return new ScriptConnection(connection, label, false);
	}

	public ScriptResultSet execute(String sql) throws BroadSQLException {
		try (Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(sql)) {
			return ScriptResultSet.readAll(rs);
		} catch (SQLException e) {
			throw new BroadSQLException("Connection '" + label + "': " + e.getLocalizedMessage(), e);
		}
	}

	public int executeUpdate(String sql) throws BroadSQLException {
		try (Statement statement = connection.createStatement()) {
			return statement.executeUpdate(sql);
		} catch (SQLException e) {
			throw new BroadSQLException("Connection '" + label + "': " + e.getLocalizedMessage(), e);
		}
	}

	/** A no-op for a connection this object does not own (see {@link #wrapExisting}). */
	public void close() throws BroadSQLException {
		if (!ownsConnection) {
			return;
		}
		try {
			if (connection != null && !connection.isClosed()) {
				connection.close();
			}
		} catch (SQLException e) {
			throw new BroadSQLException("Connection '" + label + "': " + e.getLocalizedMessage(), e);
		}
	}

	/**
	 * Duplicates the driver branching of {@link com.upandcoding.broadsql.dao.DatabaseConnection#openConnection(boolean)}
	 * (Derby, H2, everything else) - see the class Javadoc for why this is duplicated rather than reused.
	 */
	private static Connection openConnection(DatabaseDefinition target) throws BroadSQLException {
		try {
			Connection conn;
			if (SpringPropertiesConfig.DBTYPE_DERBY_Embedded.equalsIgnoreCase(target.getDbType())
					|| SpringPropertiesConfig.DBTYPE_DERBY_Client.equalsIgnoreCase(target.getDbType())) {
				System.setProperty("derby.system.home", "logs");
				Class.forName(target.getDbDriver()).getDeclaredConstructor().newInstance();
				Properties props = new Properties();
				if (StringUtils.isNotBlank(target.getUserName())) {
					props.put("user", target.getUserName());
					props.put("password", target.getUserPassword());
				}
				conn = DriverManager.getConnection(target.getConnectorDatabase(), props);
			} else if (SpringPropertiesConfig.DBTYPE_H2.equalsIgnoreCase(target.getDbType())) {
				Class.forName(target.getDbDriver()).getDeclaredConstructor().newInstance();
				String connector = target.getConnectorDatabase();
				if (StringUtils.isNotBlank(target.getUserName())) {
					connector = connector + ";USER=" + target.getUserName();
				}
				if (StringUtils.isNotBlank(target.getUserPassword())) {
					connector = connector + ";PASSWORD=" + target.getUserPassword();
				}
				conn = DriverManager.getConnection(connector);
			} else {
				Class.forName(target.getDbDriver()).getDeclaredConstructor().newInstance();
				Properties props = new Properties();
				if (StringUtils.isNotBlank(target.getUserName())) {
					props.put("user", target.getUserName());
					props.put("password", target.getUserPassword());
				}
				conn = DriverManager.getConnection(target.getConnectorDatabase(), props);
			}
			return conn;
		} catch (ReflectiveOperationException | SQLException e) {
			throw new BroadSQLException("Unable to connect to '" + target.getId() + "': " + e.getLocalizedMessage(), e);
		}
	}
}
