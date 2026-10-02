package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.shell.output.TableBorders;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Lists every database connection defined in the CDF: {@code SHOW ALL CONNECTIONS}.
 *
 * <p>Prints one row per connection (ID, type, name, user, database group, environment), sorted by ID, with
 * a trailing count of connections found.
 *
 * <p>{@code databaseType} is optional; when given, only connections whose database type matches it
 * exactly (case-insensitive) are listed - it is not a partial or wildcard match.
 */
public class CommandShowAllConnections extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandShowAllConnections.class);

	public CommandShowAllConnections() {
		super("SHOW ALL CONNECTIONS", "SH ALL CO", "SH AL CO", "SHALLCO", "SHALCO");
	}

	private static final int[] WIDTHS = { 15, 15, 45, 20, 10, 15 };

	/** A line of this command's table, through the shared table format (TableBorders), with the ScreenSeparator setting. */
	private String line(String... cells) {
		char sep = consoleSettings.getOnScreenSeparator();
		return cells.length == 0 ? TableBorders.separator(WIDTHS, 0, sep) : TableBorders.row(Arrays.asList(cells), WIDTHS, 0, sep);
	}

	private String getFormattedRow(DatabaseDefinition pl, String name) throws BroadSQLException {
		if (pl == null && name == null) {
			return line("ID", "TYPE", "NAME", "USER NAME", "GROUP", "ENVIRONMENT");
		}
		if (pl != null && !name.trim().equals("")) {
			return line(name, pl.getDbType(), pl.getDbName(), pl.getUserName(), pl.getDatabaseGroup(), pl.getEnvironment());
		}
		return line();
	}

	private void printConnections(HashMap<String, DatabaseDefinition> platforms, List<String> names) throws BroadSQLException {
		console.writeln(getFormattedRow(null, "-"));
		console.writeln(getFormattedRow(null, null));
		console.writeln(getFormattedRow(null, "-"));
		for (String name : names) {
			DatabaseDefinition pl = platforms.get(name);
			console.writeln(getFormattedRow(pl, name));
		}
		console.writeln(getFormattedRow(null, "-"));
		console.writeln("");
		console.writeln("" + platforms.size() + " database connections found");
		console.writeln("");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String dbType = null;
		if (CommandUtils.isValidArgs(args)) {
			dbType = args[0].trim();
		}

		HashMap<String, DatabaseDefinition> platforms = this.getDatabaseConnectionsVault().getPlatforms();
		Set<String> names = platforms.keySet();
		if (dbType != null && !dbType.trim().equals("")) {
			HashMap<String, DatabaseDefinition> platforms2 = new HashMap<String, DatabaseDefinition>();
			for (String name : names) {
				DatabaseDefinition pl = platforms.get(name);
				//log.debug(pl.getDbType() + " compared  to "+dbType);
				if (dbType.equalsIgnoreCase(pl.getDbType())) {
					//log.debug(name);
					platforms2.put(name, platforms.get(name));
				}
			}
			platforms = platforms2;
		}

		names = platforms.keySet();
		List<String> lNames = new ArrayList<String>(names);
		Collections.sort(lNames, new Comparator<String>() {
			@Override
			public int compare(String o1, String o2) {
				String s1 = (String) o1;
				String s2 = (String) o2;
				return s1.compareTo(s2);
			}
		});
		printConnections(platforms, lNames);
	}

	@Override
	public String getDescription() {
		return ("Displays list of all database connections in the CDF file");
	}

	@Override
	public String getArguments() {
		return "<databaseType> (optional) a database type. If provided, results are filtered accordingly";
	}

	@Override
	public String getExamples() {
		return "SHOW ALL CONNECTIONS;\n\tSHOW ALL CONNECTIONS H2;";
	}
}
