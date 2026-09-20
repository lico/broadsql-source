package com.upandcoding.broadsql.controller.shell.commands.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Edits an existing database connection: {@code EDIT CONNECTION <id>}.
 *
 * <p>{@code id} is mandatory and must already exist in the Connections Definition File (CDF) - the
 * command fails otherwise. Runs the same interactive wizard as {@code ADD CONNECTION}, pre-filled
 * with the connection's current values: Name, Type, URL, User Name, User Password, Database Group,
 * Environment and Comment, followed by a {@code [y/n/c]} confirmation. {@code y} saves the changes and
 * immediately test-connects (reporting success or failure without undoing the save), {@code n}
 * restarts the wizard from the top, anything else aborts without saving.
 *
 * <p>Environment is mandatory and must reference an existing Environment. Database Group is optional -
 * clearing the prompt turns the connection into a standalone one (no Database Group); if specified, it
 * must reference an existing Database Group, and the {@code (Database Group, Environment)} pair must
 * not already be used by another connection (docs/CONNECTION_MODEL.md).
 */
public class CommandManageConnectionEdit extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageConnectionEdit.class);

	public CommandManageConnectionEdit() {
		super("EDIT CONNECTION", "ED CO", "EDCO");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String platform = null;
		if (CommandUtils.isValidArgs(args)) {
			platform = args[0].trim();
			CommandUtils.addOrEditPlatform(platform, false, console, getDatabaseConnectionsVault(), sqlDatabase);
		} else {
			console.error(BroadSQLErrorMessages.ERR_CONN_01);
		}
	}

	@Override
	public String getDescription() {
		return ("Edit an existing database connection");
	}

	@Override
	public String getArguments() {
		return "<id> (mandatory) new connection ID";
	}

	@Override
	public String getExamples() {
		return "EDIT CONNECTION MYDB01;";
	}
}
