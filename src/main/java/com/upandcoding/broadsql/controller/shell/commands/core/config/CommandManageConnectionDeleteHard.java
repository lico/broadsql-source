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
 * Permanently deletes an existing database connection: {@code HARDDEL <id>}. Hidden from
 * {@code HELP} - not intended for routine use.
 *
 * <p>{@code id} is mandatory. Asks for a {@code [y/n/c]} confirmation before proceeding; only
 * {@code y} deletes, any other answer aborts. Unlike {@code DEL CONNECTION}, the deletion is
 * permanent and cannot be undone.
 *
 * <p>Only ever legal on an already-inactive connection ({@code DatabaseDefinitionsVault#deleteDatabaseDefinition}
 * enforces this, not this command itself): an active connection is refused with a message asking to
 * deactivate it first ({@code DEL CONNECTION}), and an unknown {@code id} is refused as not found,
 * both after the confirmation prompt (which still runs first, so an unknown or active {@code id}
 * gets a proper error rather than nothing happening silently).
 */
public class CommandManageConnectionDeleteHard extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageConnectionDeleteHard.class);

	public CommandManageConnectionDeleteHard() {
		super("HARDDEL", "HADELCO", "HADECO");
	}

	@Override
	public boolean isHidden() {
		return (true);
	}
	
	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String platform = null;
		if (CommandUtils.isValidArgs(args)) {
			platform = args[0].trim();
			if (StringUtils.isNotBlank(platform)) {
					// getDatabaseConnection() only ever sees ACTIVE connections; a HARDDEL target is normally
					// INACTIVE (see DatabaseDefinitionsVault#deleteDatabaseDefinition, which now refuses an
					// active one), so def would be null here - inputField() silently skips prompting entirely
					// when def is null, so a placeholder (id-only) DatabaseDefinition is used instead, just to
					// keep the confirmation interactive.
					DatabaseDefinition def = getDatabaseConnectionsVault().getDatabaseConnection(platform);
					if (def == null) {
						def = new DatabaseDefinition(platform);
					}
					String confirm = console.inputField(def, "Hard delete connection '" + platform + "' [y/n/c]?", "", false, false, true, null);
					if ("n".equalsIgnoreCase(confirm)) {
						console.println("Deletion aborted");
					} else {
						getDatabaseConnectionsVault().deleteDatabaseDefinition(platform);
						console.println("Database connection '" + platform + "' deleted");
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
		return ("Hard deletes an existing database connection");
	}

	@Override
	public String getArguments() {
		return "<id> (mandatory) existing connection ID";
	}

	@Override
	public String getExamples() {
		return "HARDDEL MYDB01;";
	}
}
