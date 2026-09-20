package com.upandcoding.broadsql.controller.shell.commands.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.DatabaseGroupDefinition;

/**
 * Edits an existing Database Group: {@code EDIT GROUP <id>} (docs/CONNECTION_MODEL.md §4).
 *
 * <p>{@code id} is mandatory and must already exist, active or inactive - the command fails
 * otherwise. Runs an interactive wizard pre-filled with the group's current Description and Comment,
 * plus an Active {@code [y/n]} prompt (this is also how an inactive group is reactivated from the
 * CLI), followed by a {@code [y/n/c]} confirmation: {@code y} saves, {@code n} restarts the wizard
 * from the top, anything else aborts without saving. Deactivating a group still referenced by any
 * connection - active or inactive - is refused by {@code DatabaseDefinitionsVault#saveGroup}, exactly
 * like {@code DEL GROUP} refuses.
 *
 * <p>Unlike the config screen's "Database Groups" tab, this command does not support renaming a
 * group's ID - the CLI wizard pattern (shared with {@code EDIT CONNECTION}) always keeps the ID it was
 * invoked with fixed.
 */
public class CommandManageGroupEdit extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageGroupEdit.class);

	public CommandManageGroupEdit() {
		super("EDIT GROUP", "EDIT GR", "EDGR");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (!CommandUtils.isValidArgs(args)) {
			console.error(BroadSQLErrorMessages.ERR_GROUP_01);
			return;
		}
		String id = args[0].trim();

		DatabaseGroupDefinition current = null;
		for (DatabaseGroupDefinition group : getDatabaseConnectionsVault().getGroupDetails()) {
			if (group.getId().equals(id)) {
				current = group;
				break;
			}
		}
		if (current == null) {
			console.error(BroadSQLErrorMessages.ERR_GROUP_04 + ": '" + id + "'");
			return;
		}

		DatabaseDefinition placeholder = new DatabaseDefinition(id);
		boolean repeat = true;
		while (repeat) {
			console.println("");
			console.println("Editing Database Group '" + id + "'");
			String descr = console.inputField(placeholder, "Description", current.getDescr(), true, false, false, null);
			String comment = console.inputField(placeholder, "Comment", current.getComment(), true, false, false, null);
			String activeAnswer = console.inputField(placeholder, "Active [y/n] (currently " + (current.isActive() ? "y" : "n") + ")", "", false, false, true, null);
			String statusId = isYes(activeAnswer) ? DatabaseDefinition.STATUS_ACTIVE : DatabaseDefinition.STATUS_INACTIVE;

			String confirm = console.inputField(placeholder, "Save Database Group [y/n/c]?", "", false, false, true, null);
			if (confirm.equalsIgnoreCase("y")) {
				repeat = false;
				getDatabaseConnectionsVault().saveGroup(new DatabaseGroupDefinition(id, descr, comment, statusId));
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

	private static boolean isYes(String answer) {
		return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
	}

	@Override
	public String getDescription() {
		return ("Edits an existing Database Group");
	}

	@Override
	public String getArguments() {
		return "<id> (mandatory) existing Database Group ID";
	}

	@Override
	public String getExamples() {
		return "EDIT GROUP TAT;";
	}
}
