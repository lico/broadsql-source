package com.upandcoding.broadsql.controller.shell.commands.core.config;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Soft-deletes an existing database connection: {@code DEL CONNECTION <id>}.
 *
 * <p>{@code id} is mandatory and must exist, active, in the Connections Definition File (CDF),
 * otherwise the command fails with an error (a distinct one when {@code id} is already inactive,
 * rather than the generic "must provide a valid ID" used for a truly unknown one). Asks for a
 * {@code [y/n/c]} confirmation before proceeding; only {@code y} deletes, any other answer aborts.
 *
 * <p>This is a <em>soft</em> delete: the connection is marked inactive rather than removed, so its
 * history is preserved and it can be reactivated ({@code REACTIVATE CONNECTION}, or from the config
 * screen's "Inactive connections" view) rather than recreated from scratch. Use {@code HARDDEL} to
 * permanently remove an already-inactive connection instead.
 */
public class CommandManageConnectionDelete extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageConnectionDelete.class);

	public CommandManageConnectionDelete() {
		super("DEL CONNECTION", "DEL CO", "DELCO", "DE CO", "DECO");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String platform = null;
		if (CommandUtils.isValidArgs(args)) {
			platform = args[0].trim();
			if (StringUtils.isNotBlank(platform)) {
				if (!getDatabaseConnectionsVault().contains(platform)) {
					if (getDatabaseConnectionsVault().isInactiveConnection(platform)) {
						console.error("Connection '" + platform + "' is already inactive");
					} else {
						console.error(BroadSQLErrorMessages.ERR_CONN_01);
					}
				} else {
					DatabaseDefinition def = getDatabaseConnectionsVault().getDatabaseConnection(platform);
					String confirm = console.inputField(def, "Delete connection '" + platform + "' [y/n/c]?", "", false, false, true, null);
					if ("n".equalsIgnoreCase(confirm)) {
						console.println("Deletion aborted");
					} else {
						getDatabaseConnectionsVault().softDeleteDatabaseDefinition(platform);
						console.println("Database connection '" + platform + "' deleted");
					}
				}

			} else {
				console.error(BroadSQLErrorMessages.ERR_CONN_01);
			}
		} else {
			console.error(BroadSQLErrorMessages.ERR_CONN_01);
		}
	}

	@Override
	public String getDescription() {
		return ("Deletes an existing database connection");
	}

	@Override
	public String getArguments() {
		return "<id> (mandatory) existing connection ID";
	}

	@Override
	public String getExamples() {
		return "DEL CONNECTION MYDB01;";
	}
}
