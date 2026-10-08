package com.upandcoding.broadsql.controller.shell.commands.core.misc;

import java.sql.SQLException;
import java.sql.Statement;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Links a table of another configured connection into the current H2 database:
 * {@code LINK TABLE <connectionId> <tableName>}.
 *
 * <p>The command runs H2's {@code CREATE LINKED TABLE} statement on the current connection, so the
 * current connection must be an H2 database; on any other database the command is refused before
 * anything is sent to it. The other connection, named by {@code connectionId}, is the data source the
 * linked table reads: it can be any configured connection whose JDBC driver BroadSQL has (H2, HSQLDB,
 * PostgreSQL, a driver added to the drivers folder...), because H2 reaches it through that driver.
 *
 * <p>Both arguments are mandatory. {@code connectionId} must be an existing, reachable connection
 * (checked before linking). {@code tableName} is both the table read in the other database and the
 * name the linked table gets in the current database; there is currently no way to link it under a
 * different local name. The link is live: it does not copy the remote rows into the current database,
 * every query on the linked table reads them from the other database.
 *
 * <p>H2 keeps the other connection's user and password in the linked table's definition, since it
 * needs them to reach the data. BroadSQL never shows that password in its own messages.
 */
public class CommandLinkTable extends Command {

	public CommandLinkTable() {
		super("LINK TABLE", "LINKT");
	}

	@Override
	public boolean isHidden() {
		return false;
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String remoteConnectionId = CommandUtils.isValidArgs(args) ? StringUtils.trimToNull(args[0]) : null;
		String tableName = CommandUtils.isValidArgs(args) && args.length > 1 ? StringUtils.trimToNull(args[1]) : null;
		if (remoteConnectionId == null) {
			console.error("You must specify a connection ID");
			return;
		}
		if (tableName == null) {
			console.error("You must specify the table to link, e.g. LINK TABLE " + remoteConnectionId + " CUSTOMERS");
			return;
		}

		// CREATE LINKED TABLE is H2 syntax and runs on the current connection: nothing is sent to any other database
		DatabaseDefinition current = sqlDatabase == null ? null : sqlDatabase.getPlatform();
		if (current == null || !SpringPropertiesConfig.DBTYPE_H2.equalsIgnoreCase(current.getDbType())) {
			String currentType = current == null ? "none" : current.getDbType();
			console.error("LINK TABLE needs an H2 current connection: it creates the linked table with H2's CREATE LINKED TABLE. "
					+ "The current connection is of type '" + currentType + "'. Connect to an H2 database first; the connection to link "
					+ "from can be of any type.");
			return;
		}

		if (!getDatabaseConnectionsVault().contains(remoteConnectionId)) {
			if (getDatabaseConnectionsVault().isInactiveConnection(remoteConnectionId)) {
				throw new BroadSQLException(CommandUtils.inactiveConnectionMessage(remoteConnectionId));
			}
			throw new BroadSQLException("Connection '" + remoteConnectionId + "' not defined");
		}
		DatabaseDefinition remote = getDatabaseConnectionsVault().getDatabaseConnection(remoteConnectionId);
		if (StringUtils.isBlank(remote.getDbDriver())) {
			console.error("Connection '" + remoteConnectionId + "' has no JDBC driver class: H2 needs it to reach that database.");
			return;
		}

		StringBuffer result = this.sqlDatabase.testConnectionToExistingPlatform(remoteConnectionId);
		if (result == null || result.length() <= 0 || result.toString().trim().equals("")) {
			console.error("Connection FAILED");
			return;
		}

		String password = StringUtils.defaultString(remote.getUserPassword());
		String sql = "CREATE LINKED TABLE " + tableName + " (" + literal(remote.getDbDriver()) + ", " + literal(remote.getUrl()) + ", "
				+ literal(remote.getUserName()) + ", " + literal(password) + ", " + literal(tableName) + ")";
		// Run directly, not through the statement display/error path: a failed statement's text, which holds the
		// password, must never be printed or logged
		try (Statement statement = sqlDatabase.getDirectConnection().createStatement()) {
			statement.execute(sql);
		} catch (SQLException e) {
			console.error("LINK TABLE failed: " + mask(e.getLocalizedMessage(), password));
			return;
		}
		console.println("Table '" + tableName + "' linked from connection '" + remoteConnectionId + "'.");
	}

	/** A SQL string literal: single quotes doubled. */
	private static String literal(String value) {
		return "'" + StringUtils.defaultString(value).replace("'", "''") + "'";
	}

	/** {@code message} with every occurrence of {@code password} (and its quoted SQL form) hidden. */
	static String mask(String message, String password) {
		if (message == null || password.isEmpty()) {
			return message;
		}
		return message.replace(password.replace("'", "''"), "********").replace(password, "********");
	}

	@Override
	public String getDescription() {
		return ("Links a table of another connection into the current H2 database");
	}

	@Override
	public String getArguments() {
		return "connection (mandatory) the connection the table is read from, of any type; table name (mandatory). The current connection must be H2";
	}

	@Override
	public String getExamples() {
		return "LINKT mydb01 CUSTOMERS";
	}
}
