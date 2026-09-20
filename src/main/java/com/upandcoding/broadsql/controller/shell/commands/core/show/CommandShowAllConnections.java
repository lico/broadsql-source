package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private String getFormattedRow(DatabaseDefinition pl, String name) throws BroadSQLException {
		String result = "";
		char sep = consoleSettings.getOnScreenSeparator();

		String n = getFormattedCell("-", 15);
		String type = getFormattedCell("-", 15);
		String dbName = getFormattedCell("-", 45);
		String uName = getFormattedCell("-", 20);
		String environment = getFormattedCell("-", 15);
		String group = getFormattedCell("-", 10);

		if (pl == null && name == null) {
			n = getFormattedCell("ID", 15);
			type = getFormattedCell("TYPE", 15);
			dbName = getFormattedCell("NAME", 45);
			uName = getFormattedCell("USER NAME", 20);
			environment = getFormattedCell("ENVIRONMENT", 15);
			group = getFormattedCell("GROUP", 10);

		} else if (pl != null && !name.trim().equals("")) {
			n = getFormattedCell(name, 15);
			type = getFormattedCell(pl.getDbType(), 15);
			dbName = getFormattedCell(pl.getDbName(), 45);
			uName = getFormattedCell(pl.getUserName(), 20);
			environment = getFormattedCell(pl.getEnvironment(), 15);
			group = getFormattedCell(pl.getDatabaseGroup(), 10);

		}

		result = n + sep + type + sep + dbName + sep + uName + sep + group + sep + environment + sep;

		return (result);
	}

	private void printConnections(HashMap<String, DatabaseDefinition> platforms, List<String> names) throws BroadSQLException {
		console.println(getFormattedRow(null, "-"));
		console.println(getFormattedRow(null, null));
		console.println(getFormattedRow(null, "-"));
		for (String name : names) {
			DatabaseDefinition pl = platforms.get(name);
			console.println(getFormattedRow(pl, name));
		}
		console.println(getFormattedRow(null, "-"));
		console.println("");
		console.println("" + platforms.size() + " database connections found");
		console.println("");
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
