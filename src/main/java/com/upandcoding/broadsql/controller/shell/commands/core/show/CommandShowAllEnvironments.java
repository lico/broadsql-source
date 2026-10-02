package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.shell.output.TableBorders;
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

	private static final int[] WIDTHS = { 15, 45, 10 };

	/** A line of this command's table, through the shared table format (TableBorders), with the ScreenSeparator setting. */
	private String line(String... cells) {
		char sep = consoleSettings.getOnScreenSeparator();
		return cells.length == 0 ? TableBorders.separator(WIDTHS, 0, sep) : TableBorders.row(Arrays.asList(cells), WIDTHS, 0, sep);
	}

	private String getFormattedRow(EnvironmentDefinition environment, String marker) {
		if (environment == null && marker == null) {
			return line("ID", "DESCRIPTION", "PRODUCTION");
		}
		if (environment != null) {
			return line(environment.getId(), environment.getDescr(), String.valueOf(environment.isProduction()));
		}
		return line();
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		List<EnvironmentDefinition> environments = getDatabaseConnectionsVault().getEnvironmentDetails();

		console.writeln(getFormattedRow(null, "-"));
		console.writeln(getFormattedRow(null, null));
		console.writeln(getFormattedRow(null, "-"));
		for (EnvironmentDefinition environment : environments) {
			console.writeln(getFormattedRow(environment, null));
		}
		console.writeln(getFormattedRow(null, "-"));
		console.writeln("");
		console.writeln("" + environments.size() + " environment(s) found");
		console.writeln("");
	}

	@Override
	public String getDescription() {
		return ("Shows the globally configured Environments");
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
