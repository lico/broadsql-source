package com.upandcoding.broadsql.controller.shell.commands.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Creates a new database connection: {@code ADD CONNECTION <id>}.
 *
 * <p>{@code id} is mandatory and must not already exist in the Connections Definition File (CDF) -
 * the command fails otherwise. The command then runs an interactive wizard, prompting in turn for
 * Name, Type, URL, User Name, User Password, Database Group, Environment and Comment, followed by a
 * {@code [y/n/c]} confirmation: {@code y} saves the connection and immediately test-connects to it
 * (reporting success or failure without aborting the save), {@code n} restarts the wizard from the
 * top, anything else aborts without saving.
 *
 * <p>Environment is mandatory and must reference an existing Environment. Database Group is optional -
 * leaving the prompt blank creates a standalone connection (no Database Group); if specified, it must
 * reference an existing Database Group, and the {@code (Database Group, Environment)} pair must not
 * already be used by another connection (docs/CONNECTION_MODEL.md).
 *
 * <p>There is no non-interactive way to create a connection with this command; all fields must be
 * entered through the prompts.
 *
 * <p>The Type prompt lists every recognized database type, marking (never excluding) any whose JDBC
 * driver class isn't found in {@code drivers/} or {@code lib/}; such a type can still be picked, but
 * a connection using it will fail to {@code CONNECT} until the matching driver jar is added.
 */
public class CommandManageConnectionAdd extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageConnectionAdd.class);

	public CommandManageConnectionAdd() {
		super("ADD CONNECTION", "ADD CO", "ADDCO", "AD CO", "ADCO");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String newPlatform = null;
		if (CommandUtils.isValidArgs(args)) {
			newPlatform = args[0].trim();
			//log.debug("newPlatform: {}", newPlatform);
			CommandUtils.addOrEditPlatform(newPlatform, true, console, getDatabaseConnectionsVault(), sqlDatabase);
		} else {
			console.error(BroadSQLErrorMessages.ERR_CONN_01);
		}
	}

	@Override
	public String getDescription() {
		return ("Creates a new database connection");
	}

	@Override
	public String getArguments() {
		return "<id> (mandatory) new connection ID";
	}

	@Override
	public String getExamples() {
		return "ADD CONNECTION MYDB01;";
	}
}
