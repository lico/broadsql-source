package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Lists every inactive (soft-deleted) database connection in the CDF: {@code SHOW INACTIVE
 * CONNECTIONS}.
 *
 * <p>Prints one row per inactive connection (ID, type, name, user, database group, environment),
 * sorted by ID, with a trailing count - the Linux/CLI equivalent of the config screen's "View
 * inactive connections" toggle, using the exact same top/bottom dashed-separator table layout as
 * {@link CommandShowAllConnections}. An inactive connection is invisible to every other command
 * ({@code CONNECT}, {@code SHOW ALL CONNECTIONS}, {@code SHOW CONNECTION}, etc.) - this is the only
 * way to discover an inactive connection's ID from the CLI, short of already knowing it, before
 * using {@code REACTIVATE CONNECTION} or {@code HARDDEL}.
 *
 * <p>{@code databaseType} is optional; when given, only connections whose database type matches it
 * exactly (case-insensitive) are listed - not a partial or wildcard match, exactly like {@code SHOW
 * ALL CONNECTIONS}.
 */
public class CommandShowInactiveConnections extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandShowInactiveConnections.class);

	public CommandShowInactiveConnections() {
		super("SHOW INACTIVE CONNECTIONS", "SH INACT CO", "SHINACO");
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

	private String getFormattedRow(DatabaseDefinition pl) {
		char sep = consoleSettings.getOnScreenSeparator();

		String n = getFormattedCell("-", 15);
		String type = getFormattedCell("-", 15);
		String dbName = getFormattedCell("-", 45);
		String uName = getFormattedCell("-", 20);
		String environment = getFormattedCell("-", 15);
		String group = getFormattedCell("-", 10);

		if (pl == null) {
			n = getFormattedCell("ID", 15);
			type = getFormattedCell("TYPE", 15);
			dbName = getFormattedCell("NAME", 45);
			uName = getFormattedCell("USER NAME", 20);
			environment = getFormattedCell("ENVIRONMENT", 15);
			group = getFormattedCell("GROUP", 10);
		} else {
			n = getFormattedCell(pl.getId(), 15);
			type = getFormattedCell(pl.getDbType(), 15);
			dbName = getFormattedCell(pl.getDbName(), 45);
			uName = getFormattedCell(pl.getUserName(), 20);
			environment = getFormattedCell(pl.getEnvironment(), 15);
			group = getFormattedCell(pl.getDatabaseGroup(), 10);
		}

		return n + sep + type + sep + dbName + sep + uName + sep + group + sep + environment + sep;
	}

	private String getSeparatorRow() {
		char sep = consoleSettings.getOnScreenSeparator();
		return getFormattedCell("-", 15) + sep + getFormattedCell("-", 15) + sep + getFormattedCell("-", 45) + sep + getFormattedCell("-", 20) + sep + getFormattedCell("-", 10) + sep
				+ getFormattedCell("-", 15) + sep;
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String dbType = null;
		if (CommandUtils.isValidArgs(args)) {
			dbType = args[0].trim();
		}

		List<DatabaseDefinition> connections = getDatabaseConnectionsVault().getInactiveConnectionDetails();
		final String filterType = dbType;
		if (StringUtils.isNotBlank(filterType)) {
			connections.removeIf(pl -> !filterType.equalsIgnoreCase(pl.getDbType()));
		}
		connections.sort((a, b) -> a.getId().compareTo(b.getId()));

		console.println(getSeparatorRow());
		console.println(getFormattedRow(null));
		console.println(getSeparatorRow());
		for (DatabaseDefinition pl : connections) {
			console.println(getFormattedRow(pl));
		}
		console.println(getSeparatorRow());
		console.println("");
		console.println("" + connections.size() + " inactive database connection(s) found");
		console.println("");
	}

	@Override
	public String getDescription() {
		return ("Displays list of all inactive database connections in the CDF file");
	}

	@Override
	public String getArguments() {
		return "<databaseType> (optional) a database type. If provided, results are filtered accordingly";
	}

	@Override
	public String getExamples() {
		return "SHOW INACTIVE CONNECTIONS;\n\tSHOW INACTIVE CONNECTIONS H2;";
	}
}
