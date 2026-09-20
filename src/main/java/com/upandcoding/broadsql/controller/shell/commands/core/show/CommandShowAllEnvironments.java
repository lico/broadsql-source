package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/**
 * Lists the globally configured Environments: {@code SHOW ALL ENVIRONMENTS}
 * (docs/CONNECTION_MODEL.md §10.2).
 *
 * <p>Prints one row per Environment (ID, Description, Production), active and inactive alike, sorted
 * by ID, with a trailing count, using the exact same top/bottom dashed-separator table layout as
 * {@link CommandShowAllConnections}/{@link CommandShowAllGroups}. Unlike {@link CommandShowGroup}
 * (whose {@code SHOW ENVIRONMENTS} alias lists the Connections of the <em>current</em> Database
 * Group), this command is not connection-scoped - it always lists the full global Environment
 * referential, per §10.2: "must not mean 'show the connections in the current Database Group.'"
 */
public class CommandShowAllEnvironments extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandShowAllEnvironments.class);

	public CommandShowAllEnvironments() {
		super("SHOW ALL ENVIRONMENTS", "SHALENV", "SHOW ALL ENVTS");
	}

	private String getFormattedCell(String content, int length) {
		String result = content;
		if (content == null || content.trim().equals("")) {
			result = "";
		}
		if ("-".equalsIgnoreCase(result.trim())) {
			result = StringUtils.leftPad("", length, "-");
		} else {
			result = StringUtils.rightPad(result, length, " ");
		}
		return (result);
	}//getFormattedCell

	private String getFormattedRow(EnvironmentDefinition environment, String marker) {
		char sep = consoleSettings.getOnScreenSeparator();

		String id = getFormattedCell("-", 15);
		String descr = getFormattedCell("-", 45);
		String production = getFormattedCell("-", 10);

		if (environment == null && marker == null) {
			id = getFormattedCell("ID", 15);
			descr = getFormattedCell("DESCRIPTION", 45);
			production = getFormattedCell("PRODUCTION", 10);
		} else if (environment != null) {
			id = getFormattedCell(environment.getId(), 15);
			descr = getFormattedCell(environment.getDescr(), 45);
			production = getFormattedCell(String.valueOf(environment.isProduction()), 10);
		}

		return id + sep + descr + sep + production + sep;
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		List<EnvironmentDefinition> environments = getDatabaseConnectionsVault().getEnvironmentDetails();

		console.println(getFormattedRow(null, "-"));
		console.println(getFormattedRow(null, null));
		console.println(getFormattedRow(null, "-"));
		for (EnvironmentDefinition environment : environments) {
			console.println(getFormattedRow(environment, null));
		}
		console.println(getFormattedRow(null, "-"));
		console.println("");
		console.println("" + environments.size() + " environment(s) found");
		console.println("");
	}

	@Override
	public String getDescription() {
		return ("Show the globally configured Environments");
	}

	@Override
	public String getArguments() {
		return "none";
	}

	@Override
	public String getExamples() {
		return "SHOW ALL ENVIRONMENTS;";
	}
}
