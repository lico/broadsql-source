package com.upandcoding.broadsql.controller.shell.commands.core.io;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Tests a database connection without opening it: {@code PING <databaseId>} or
 * {@code CHECK <databaseId>}.
 *
 * <p>{@code databaseId} is mandatory and must be defined in the Connections Definition File (CDF) -
 * the command fails otherwise. Unlike {@code CONNECT}, this never changes the current connection: it
 * only reports {@code SUCCESS} or {@code FAILED}, printing the connection test's detail on success
 * or the underlying error on failure.
 */
public class CommandTestConnection extends Command {

	public CommandTestConnection() {
		super("PING", "CHECK");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String platformID = null;
		if (CommandUtils.isValidArgs(args)) {
			platformID = args[0].trim();

			if (StringUtils.isNotBlank(platformID)) {
				if (getDatabaseConnectionsVault().contains(platformID)) {
					try {
						StringBuffer result = sqlDatabase.testConnectionToExistingPlatform(platformID);
						if (result == null || result.length() <= 0) {
							console.writeln("Connection to database: FAILED");
						} else {
							console.writeln("Connection to database: SUCCESS");
							console.writeln(result.toString());
						}
					} catch (BroadSQLException ex) {
						console.writeln("Connection FAILED with error :");
						console.error(ex);
					}
				} else if (getDatabaseConnectionsVault().isInactiveConnection(platformID)) {
					console.error(CommandUtils.inactiveConnectionMessage(platformID));
				} else {
					console.error("Database " + platformID + " does not exist in the CDF file");
				}
				console.println("");
			} else {
				console.error(BroadSQLErrorMessages.ERR_CONN_01);
			}
		} else {
			console.error(BroadSQLErrorMessages.ERR_CONN_01);
		}
	}

	@Override
	public String getDescription() {
		return ("Tests a database connection");
	}

	@Override
	public String getArguments() {
		return "<databaseId> (mandatory) a valid database ID";
	}

	@Override
	public String getExamples() {
		return "CHECK MYDB;";
	}
}
