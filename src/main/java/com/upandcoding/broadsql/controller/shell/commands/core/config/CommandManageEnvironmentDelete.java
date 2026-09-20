package com.upandcoding.broadsql.controller.shell.commands.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/**
 * Soft-deletes an existing Environment: {@code DEL ENVIRONMENT <id>} (docs/CONNECTION_MODEL.md §5).
 *
 * <p>{@code id} is mandatory and must exist, active (matched case-insensitively per §5.1) - already
 * inactive is reported with a distinct message, unknown with the generic one. Asks for a {@code
 * [y/n]} confirmation before proceeding; only {@code y}/{@code yes} deletes, anything else aborts.
 *
 * <p>This is a <em>soft</em> delete: the Environment is marked inactive, not removed - {@code
 * DatabaseDefinitionsVault#softDeleteEnvironment} refuses if any connection, active or inactive,
 * still references it, or if it is the last remaining active Environment (§5.5). Reactivate it with
 * {@code EDIT ENVIRONMENT} (toggle Active back on).
 */
public class CommandManageEnvironmentDelete extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageEnvironmentDelete.class);

	public CommandManageEnvironmentDelete() {
		super("DEL ENVIRONMENT", "DEL ENV", "DELENV");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (!CommandUtils.isValidArgs(args)) {
			console.error(BroadSQLErrorMessages.ERR_ENV_01);
			return;
		}
		String id = args[0].trim();

		EnvironmentDefinition current = null;
		for (EnvironmentDefinition environment : getDatabaseConnectionsVault().getEnvironmentDetails()) {
			if (environment.getId().equalsIgnoreCase(id)) {
				current = environment;
				break;
			}
		}
		if (current == null) {
			console.error(BroadSQLErrorMessages.ERR_ENV_04 + ": '" + id + "'");
			return;
		}
		if (!current.isActive()) {
			console.error("Environment '" + current.getId() + "' is already inactive");
			return;
		}

		String actualId = current.getId();
		String confirm = console.inputField(new DatabaseDefinition(actualId), "Delete Environment '" + actualId + "' [y/n]?", "", false, false, true, null);
		if (confirm != null && (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes"))) {
			getDatabaseConnectionsVault().softDeleteEnvironment(actualId);
			console.println("Environment '" + actualId + "' deleted");
		} else {
			console.println("Deletion aborted");
		}
	}

	@Override
	public String getDescription() {
		return ("Deletes an existing Environment");
	}

	@Override
	public String getArguments() {
		return "<id> (mandatory) existing, active Environment ID";
	}

	@Override
	public String getExamples() {
		return "DEL ENVIRONMENT QA;";
	}
}
