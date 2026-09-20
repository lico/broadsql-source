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
 * Reactivates an inactive database connection: {@code REACTIVATE CONNECTION <id>}.
 *
 * <p>{@code id} is mandatory and the user must already know it: an inactive connection is invisible
 * to every other command ({@code CONNECT}, {@code SHOW ALL CONNECTIONS}, etc.) and only listed in the
 * config screen's "Inactive connections" view. If {@code id} does not name any connection at all
 * (active or inactive), the command fails with a clear "cannot find this ID" error rather than the
 * generic "not defined" used elsewhere, since this is the one command whose whole purpose is to look
 * up an ID the caller believes is merely inactive, so a plain "not defined" would be misleading. If
 * {@code id} names a connection that is already active, the command says so and does nothing.
 *
 * <p>Otherwise asks for a {@code [y/n]} confirmation before proceeding; only {@code y}/{@code yes}
 * reactivates, anything else aborts. Reactivation only flips the status back to {@code ACTIVE}; it
 * never changes any other field, so whatever the connection looked like right before it was
 * deactivated is exactly what comes back.
 */
public class CommandManageConnectionReactivate extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageConnectionReactivate.class);

	public CommandManageConnectionReactivate() {
		super("REACTIVATE CONNECTION", "REACT CO", "REACO");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		if (!CommandUtils.isValidArgs(args)) {
			console.error(BroadSQLErrorMessages.ERR_CONN_01);
			return;
		}

		String id = args[0].trim();
		if (StringUtils.isBlank(id)) {
			console.error(BroadSQLErrorMessages.ERR_CONN_01);
			return;
		}

		if (getDatabaseConnectionsVault().contains(id)) {
			console.println("Connection '" + id + "' is already active");
			return;
		}

		if (!getDatabaseConnectionsVault().isInactiveConnection(id)) {
			console.error(BroadSQLErrorMessages.ERR_CONN_04 + ": '" + id + "'");
			return;
		}

		String confirm = console.inputField(new DatabaseDefinition(id), "Reactivate connection '" + id + "' [y/n]?", "", false, false, true, null);
		if (confirm != null && (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes"))) {
			getDatabaseConnectionsVault().reactivateDatabaseDefinition(id);
			console.println("Connection '" + id + "' reactivated");
		} else {
			console.println("Operation aborted");
		}
	}

	@Override
	public String getDescription() {
		return ("Reactivates an inactive database connection");
	}

	@Override
	public String getArguments() {
		return "<id> (mandatory) an existing, currently inactive connection ID";
	}

	@Override
	public String getExamples() {
		return "REACTIVATE CONNECTION MYDB01;";
	}
}
