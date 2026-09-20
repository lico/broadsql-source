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
 * Edits an existing Environment: {@code EDIT ENVIRONMENT <id>} (docs/CONNECTION_MODEL.md §5).
 *
 * <p>{@code id} is mandatory and must already exist (matched case-insensitively per §5.1) - the
 * command fails otherwise. Runs an interactive wizard pre-filled with the Environment's current
 * Description, Production flag and Comment, plus an Active {@code [y/n]} prompt (this is also how an
 * inactive Environment is reactivated from the CLI), followed by a {@code [y/n/c]} confirmation:
 * {@code y} saves, {@code n} restarts the wizard from the top, anything else aborts without saving.
 * Deactivating an Environment still referenced by any connection - active or inactive - or that is the
 * last remaining active Environment, is refused by {@code DatabaseDefinitionsVault#saveEnvironment},
 * exactly like {@code DEL ENVIRONMENT} refuses.
 *
 * <p>Per §5.1, an Environment's ID is immutable after creation - this command never changes it, using
 * the Environment's own stored ID (not necessarily the exact casing typed on the command line) for the
 * save.
 */
public class CommandManageEnvironmentEdit extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageEnvironmentEdit.class);

	public CommandManageEnvironmentEdit() {
		super("EDIT ENVIRONMENT", "EDIT ENV", "EDENV");
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

		String actualId = current.getId();
		DatabaseDefinition placeholder = new DatabaseDefinition(actualId);
		boolean repeat = true;
		while (repeat) {
			console.println("");
			console.println("Editing Environment '" + actualId + "'");
			String descr = console.inputField(placeholder, "Description", current.getDescr(), true, false, false, null);
			String productionAnswer = console.inputField(placeholder, "Production [y/n] (currently " + (current.isProduction() ? "y" : "n") + ")", "", false, false, true, null);
			boolean production = isYes(productionAnswer);
			String comment = console.inputField(placeholder, "Comment", current.getComment(), true, false, false, null);
			String activeAnswer = console.inputField(placeholder, "Active [y/n] (currently " + (current.isActive() ? "y" : "n") + ")", "", false, false, true, null);
			String statusId = isYes(activeAnswer) ? DatabaseDefinition.STATUS_ACTIVE : DatabaseDefinition.STATUS_INACTIVE;

			String confirm = console.inputField(placeholder, "Save Environment [y/n/c]?", "", false, false, true, null);
			if (confirm.equalsIgnoreCase("y")) {
				repeat = false;
				getDatabaseConnectionsVault().saveEnvironment(new EnvironmentDefinition(actualId, descr, production, comment, statusId));
				console.println("");
				console.println("Environment '" + actualId + "' successfully saved");
			} else if (confirm.equalsIgnoreCase("n")) {
				repeat = true;
			} else {
				console.println("Operation aborted");
				repeat = false;
			}
		}
		console.println("");
	}

	private static boolean isYes(String answer) {
		return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
	}

	@Override
	public String getDescription() {
		return ("Edits an existing Environment");
	}

	@Override
	public String getArguments() {
		return "<id> (mandatory) existing Environment ID";
	}

	@Override
	public String getExamples() {
		return "EDIT ENVIRONMENT PROD;";
	}
}
