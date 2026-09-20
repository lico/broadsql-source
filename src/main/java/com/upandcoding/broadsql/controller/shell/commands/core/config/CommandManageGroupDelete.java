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
 * Soft-deletes an existing Database Group: {@code DEL GROUP <id>} (docs/CONNECTION_MODEL.md §4).
 *
 * <p>{@code id} is mandatory and must exist, active, in the CDF's {@code INSTANCE} table - already
 * inactive is reported with a distinct message, unknown with the generic one. Asks for a {@code
 * [y/n]} confirmation before proceeding; only {@code y}/{@code yes} deletes, anything else aborts.
 *
 * <p>This is a <em>soft</em> delete: the group is marked inactive, not removed - {@code
 * DatabaseDefinitionsVault#softDeleteGroup} refuses if any connection, active or inactive, still
 * references it. Reactivate it with {@code EDIT GROUP} (toggle Active back on).
 */
public class CommandManageGroupDelete extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageGroupDelete.class);

	public CommandManageGroupDelete() {
		super("DEL GROUP", "DEL GR", "DELGR");
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
		if (!current.isActive()) {
			console.error("Database Group '" + id + "' is already inactive");
			return;
		}

		String confirm = console.inputField(new DatabaseDefinition(id), "Delete Database Group '" + id + "' [y/n]?", "", false, false, true, null);
		if (confirm != null && (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes"))) {
			getDatabaseConnectionsVault().softDeleteGroup(id);
			console.println("Database Group '" + id + "' deleted");
		} else {
			console.println("Deletion aborted");
		}
	}

	@Override
	public String getDescription() {
		return ("Deletes an existing Database Group");
	}

	@Override
	public String getArguments() {
		return "<id> (mandatory) existing, active Database Group ID";
	}

	@Override
	public String getExamples() {
		return "DEL GROUP TAT;";
	}
}
