package com.upandcoding.broadsql.controller.shell.commands.core.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.DatabaseGroupDefinition;

/**
 * Creates a new Database Group: {@code ADD GROUP <id>} (docs/CONNECTION_MODEL.md §4) - the CLI
 * equivalent of the config screen's "Database Groups" tab, "New" action.
 *
 * <p>{@code id} is mandatory and must not already exist, active or inactive, in the CDF's {@code
 * INSTANCE} table - the command fails otherwise; an ID already used by an inactive group is reported
 * distinctly, pointing at {@code EDIT GROUP} to reactivate it instead of creating a duplicate. Runs an
 * interactive wizard prompting for Description and Comment, followed by a {@code [y/n/c]}
 * confirmation: {@code y} saves, {@code n} restarts the wizard from the top, anything else aborts
 * without saving.
 */
public class CommandManageGroupAdd extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageGroupAdd.class);

	public CommandManageGroupAdd() {
		super("ADD GROUP", "ADD GR", "ADDGR");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (!CommandUtils.isValidArgs(args)) {
			console.error(BroadSQLErrorMessages.ERR_GROUP_01);
			return;
		}
		String id = args[0].trim();

		for (DatabaseGroupDefinition group : getDatabaseConnectionsVault().getGroupDetails()) {
			if (group.getId().equals(id)) {
				if (group.isActive()) {
					throw new BroadSQLException(BroadSQLErrorMessages.ERR_GROUP_02);
				}
				console.error("An inactive Database Group already exists for ID '" + id + "'. Use EDIT GROUP to reactivate it.");
				return;
			}
		}

		DatabaseDefinition placeholder = new DatabaseDefinition(id);
		boolean repeat = true;
		while (repeat) {
			console.println("");
			console.println("Creating new Database Group with ID '" + id + "'");
			String descr = console.inputField(placeholder, "Description", "", true, false, false, null);
			String comment = console.inputField(placeholder, "Comment", "", true, false, false, null);

			String confirm = console.inputField(placeholder, "Save Database Group [y/n/c]?", "", false, false, true, null);
			if (confirm.equalsIgnoreCase("y")) {
				repeat = false;
				getDatabaseConnectionsVault().saveGroup(new DatabaseGroupDefinition(id, descr, comment, DatabaseDefinition.STATUS_ACTIVE));
				console.println("");
				console.println("Database Group '" + id + "' successfully saved");
			} else if (confirm.equalsIgnoreCase("n")) {
				repeat = true;
			} else {
				console.println("Operation aborted");
				repeat = false;
			}
		}
		console.println("");
	}

	@Override
	public String getDescription() {
		return ("Creates a new Database Group");
	}

	@Override
	public String getArguments() {
		return "<id> (mandatory) new Database Group ID";
	}

	@Override
	public String getExamples() {
		return "ADD GROUP TAT;";
	}
}
