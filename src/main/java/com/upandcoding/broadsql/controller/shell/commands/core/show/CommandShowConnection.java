package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.List;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Shows the details of a connection: {@code SHOW CONNECTION [connectionId]} - name, type, URL,
 * user, Database Group and environment.
 *
 * <p>{@code connectionId} is optional; if omitted, the currently active connection is shown instead.
 * A warning is displayed if the given (or current) connection ID doesn't exist in the CDF.
 */
public class CommandShowConnection extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandShowConnection.class);

	public CommandShowConnection() {
		super("SHOW CONNECTION", "SH CO", "SHCO");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		//log.debug("this.getPlatform(): {}", this.getPlatform());
		String platformID = null;
		if (CommandUtils.isValidArgs(args)) {
			platformID = args[0];
			platformID = platformID.trim();
			if (platformID.endsWith(";")) {
				platformID = StringUtils.substringBefore(platformID, ";");
			}
		} else {
			platformID = this.getPlatform();
		}
		//log.debug("platformID: {}", platformID);

		if (platformID != null && !"".equalsIgnoreCase(platformID.trim()) && getDatabaseConnectionsVault().contains(platformID)) {
			DatabaseDefinition pl = getDatabaseConnectionsVault().getDatabaseConnection(platformID);
			console.writeln("CONNECTION: " + platformID);
			console.writeln("NAME      : " + pl.getDbName());
			console.writeln("TYPE      : " + pl.getDbType());
			console.writeln("URL       : " + pl.getUrl());
			console.writeln("USER      : " + pl.getUserName());
			console.writeln("DATABASE GROUP : " + StringUtils.defaultIfBlank(pl.getDatabaseGroup(), "-"));
			console.writeln("ENVIRONMENT    : " + pl.getEnvironment());
			console.writeln("");
		} else if (platformID != null && getDatabaseConnectionsVault().isInactiveConnection(platformID)) {
			console.println(CommandUtils.inactiveConnectionMessage(platformID), ShellConsole.MSG_WARN);
		} else {
			console.println("Connection " + platformID + " does not exist", ShellConsole.MSG_WARN);
		}
	}

	@Override
	public String getDescription() {
		return ("Shows details about a specified connection");
	}

	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.CONNECTION);
	}

	@Override
	public String getArguments() {
		// TODO Auto-generated method stub
		return "server ID (optional): if empty displays current connection settings";
	}

	@Override
	public String getExamples() {
		// TODO Auto-generated method stub
		return "SHOW CONNECTION db01;\n\tSHOW CONNECTION;";
	}
}
