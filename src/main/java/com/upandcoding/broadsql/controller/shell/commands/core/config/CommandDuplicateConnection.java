package com.upandcoding.broadsql.controller.shell.commands.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Duplicates an existing database connection under a new ID: {@code DUPLICATE CONNECTION <sourceId>
 * <newId>}.
 *
 * <p>{@code sourceId} must name an active connection - a distinct message is shown when it names an
 * inactive one instead of the generic "must provide a valid ID". {@code newId} follows the exact
 * same collision rules as {@code ADD CONNECTION}: already in use by an active connection fails,
 * already in use by an inactive one offers to reactivate it instead (discarding the duplication).
 *
 * <p>Every field (Name, Type, URL, User Name, User Password, Database Group, Environment, Comment)
 * is pre-filled from {@code sourceId}'s connection, then the same interactive wizard as {@code
 * ADD}/{@code EDIT CONNECTION} runs, ending in a {@code [y/n/c]} confirmation, so the new connection
 * never ends up an exact untouched clone unless the user explicitly keeps every value as-is.
 */
public class CommandDuplicateConnection extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandDuplicateConnection.class);

	public CommandDuplicateConnection() {
		super("DUPLICATE CONNECTION", "DUP CO", "DUPCO");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		if (!CommandUtils.isValidArgs(args) || args.length < 2) {
			console.error(BroadSQLErrorMessages.ERR_CONN_01);
			return;
		}

		String sourceId = args[0].trim();
		String newId = args[1].trim();
		CommandUtils.duplicatePlatform(sourceId, newId, console, getDatabaseConnectionsVault(), sqlDatabase);
	}

	@Override
	public String getDescription() {
		return ("Duplicates an existing database connection under a new ID");
	}

	@Override
	public String getArguments() {
		return "<sourceId> <newId> (both mandatory) an existing, active connection ID, and the new connection's ID";
	}

	@Override
	public String getExamples() {
		return "DUPLICATE CONNECTION MYDB01 MYDB02;";
	}
}
