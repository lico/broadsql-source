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
 * Creates a new Environment: {@code ADD ENVIRONMENT <id>} (docs/CONNECTION_MODEL.md §5) - the CLI
 * equivalent of the config screen's "Environments" tab, "New" action.
 *
 * <p>{@code id} is mandatory and must not already exist - uniqueness is case-insensitive per §5.1
 * ({@code PR} and {@code pr} must not coexist); an ID already used by an inactive Environment is
 * reported distinctly, pointing at {@code EDIT ENVIRONMENT} to reactivate it instead of creating a
 * duplicate. Runs an interactive wizard prompting for Description, Production {@code [y/n]} and
 * Comment, followed by a {@code [y/n/c]} confirmation: {@code y} saves, {@code n} restarts the wizard
 * from the top, anything else aborts without saving.
 */
public class CommandManageEnvironmentAdd extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageEnvironmentAdd.class);

	public CommandManageEnvironmentAdd() {
		super("ADD ENVIRONMENT", "ADD ENV", "ADDENV");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (!CommandUtils.isValidArgs(args)) {
			console.error(BroadSQLErrorMessages.ERR_ENV_01);
			return;
		}
		String id = args[0].trim();

		for (EnvironmentDefinition environment : getDatabaseConnectionsVault().getEnvironmentDetails()) {
			if (environment.getId().equalsIgnoreCase(id)) {
				if (environment.isActive()) {
					throw new BroadSQLException(BroadSQLErrorMessages.ERR_ENV_02);
				}
				console.error("An inactive Environment already exists for ID '" + environment.getId() + "'. Use EDIT ENVIRONMENT to reactivate it.");
				return;
			}
		}

		DatabaseDefinition placeholder = new DatabaseDefinition(id);
		boolean repeat = true;
		while (repeat) {
			console.println("");
			console.println("Creating new Environment with ID '" + id + "'");
			String descr = console.inputField(placeholder, "Description", "", true, false, false, null);
			String productionAnswer = console.inputField(placeholder, "Production [y/n]", "", false, false, true, null);
			boolean production = isYes(productionAnswer);
			String comment = console.inputField(placeholder, "Comment", "", true, false, false, null);

			String confirm = console.inputField(placeholder, "Save Environment [y/n/c]?", "", false, false, true, null);
			if (confirm.equalsIgnoreCase("y")) {
				repeat = false;
				getDatabaseConnectionsVault().saveEnvironment(new EnvironmentDefinition(id, descr, production, comment, DatabaseDefinition.STATUS_ACTIVE));
				console.println("");
				console.println("Environment '" + id + "' successfully saved");
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
		return ("Creates a new Environment");
	}

	@Override
	public String getArguments() {
		return "<id> (mandatory) new Environment ID";
	}

	@Override
	public String getExamples() {
		return "ADD ENVIRONMENT PROD;";
	}
}
