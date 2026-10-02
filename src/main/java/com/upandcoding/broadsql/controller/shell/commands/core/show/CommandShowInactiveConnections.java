package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.shell.output.TableBorders;
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

	private static final int[] WIDTHS = { 15, 15, 45, 20, 10, 15 };

	/** A line of this command's table, through the shared table format (TableBorders), with the ScreenSeparator setting. */
	private String line(String... cells) {
		char sep = consoleSettings.getOnScreenSeparator();
		return cells.length == 0 ? TableBorders.separator(WIDTHS, 0, sep) : TableBorders.row(Arrays.asList(cells), WIDTHS, 0, sep);
	}

	private String getFormattedRow(DatabaseDefinition pl) {
		if (pl == null) {
			return line("ID", "TYPE", "NAME", "USER NAME", "GROUP", "ENVIRONMENT");
		}
		return line(pl.getId(), pl.getDbType(), pl.getDbName(), pl.getUserName(), pl.getDatabaseGroup(), pl.getEnvironment());
	}

	private String getSeparatorRow() {
		return line();
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

		console.writeln(getSeparatorRow());
		console.writeln(getFormattedRow(null));
		console.writeln(getSeparatorRow());
		for (DatabaseDefinition pl : connections) {
			console.writeln(getFormattedRow(pl));
		}
		console.writeln(getSeparatorRow());
		console.writeln("");
		console.writeln("" + connections.size() + " inactive database connection(s) found");
		console.writeln("");
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
