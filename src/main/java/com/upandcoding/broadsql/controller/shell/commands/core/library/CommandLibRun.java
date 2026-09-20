package com.upandcoding.broadsql.controller.shell.commands.core.library;

import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;

/*
 * LIB RUN: executes SQL queries in the SQL library
 * You can replace parameters using %1 %2 etc
 * Just put the %1 %2 in the stored SQL query such as:
 * select count(*) from customer where country='%1' and agent=%2;
 * Then call the procedure like this:
 * LIB RUN MAQUERY.SQL CH 800;
 * At time of execution, %1 will be replaced by CH and %2 will be replaced by 800
 */
/**
 * Runs a saved query from the SQL library: {@code LIB RUN <name> [param1 param2 ...]}.
 *
 * <p>{@code name} is mandatory, matched against the file's relative path, a declared {@code @alias},
 * or (if unambiguous) its bare file name; the {@code .sql} extension is appended automatically if
 * omitted and nothing else matched. The entry's content is read from the configured library folder
 * ({@code SqlLib} in {@code BroadSQL.ini}), its metadata header stripped, echoed to the console, and
 * executed: as a {@code SELECT} if it isn't recognized as an update statement, as an update otherwise.
 *
 * <p>The stored query may contain positional placeholders {@code %1} to {@code %9}, replaced by the
 * extra arguments given after the name, in order (e.g. {@code LIB RUN MYQUERY.SQL CH 800} replaces
 * {@code %1} with {@code CH} and {@code %2} with {@code 800}). If any placeholder is still present
 * after substitution, the command reports that not enough parameters were given and does not run the
 * query; if more arguments are given than the query actually uses, it reports that too but still runs
 * it. The {@code <@file>} macro syntax (see {@code HELP}) is only expanded for {@code SELECT}-type
 * queries, not for updates. Also prints a one-line warning, without refusing to run, for each of the
 * entry's {@code @instance}/{@code @environment} metadata that doesn't match the current connection's
 * instance/environment (see {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, sections 4 and 7).
 */
public class CommandLibRun extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandLibRun.class);

	public CommandLibRun() {
		super("LIB RUN", "LI RU", "LIRU");
	}

	public String substituteParamCodes(String query, String[] values) {
		String result = query;
		if (values != null && values.length > 1) {
			for (int i = 1; i < values.length; i++) {
				String repl = "%" + i;
				result = StringUtils.replace(result, repl, values[i]);
			}
		}
		return (result);
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		if (extractMode) {
			sqlDatabase.setToScreen(false);
		}

		String[] args = parseArgs(query);
		String libItem = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		if (StringUtils.isBlank(consoleSettings.getLibraryPath())) {
			console.println("No SQL library folder specified in the INI file");
			return;
		}

		FileCatalog catalog = new FileCatalog(consoleSettings.getLibraryPath());
		String resolved = catalog.resolve(libItem);
		if (resolved == null) {
			console.println("The SQL library does not contain the requested query file");
			return;
		}

		warnIfInstanceMismatch(catalog, resolved);
		warnIfEnvironmentMismatch(catalog, resolved);

		String qry = catalog.getQueryBody(resolved);
		if (qry != null) {
			qry = qry.trim();
		}
		if (qry != null && qry.endsWith(";")) {
			qry = StringUtils.substringBefore(qry, ";");
		}
		String qryDisplay = StringUtils.replace(qry, "%", "]]z0a23-=");
		qryDisplay = StringUtils.replace(qryDisplay, "]]z0a23-=", "%%");
		console.println(qryDisplay);
		warnIfTooManySuppliedParams(catalog, resolved, args);
		query = qry;
		try {
			if (CommandUtils.isNotUpdateStatement(query)) {
				query = CommandUtils.substituteMacros(query);
				query = substituteParamCodes(query, args);
				if (query.contains("%1") || query.contains("%2") || query.contains("%3") || query.contains("%4") || query.contains("%5") || query.contains("%6") || query.contains("%7") || query.contains("%8") || query.contains("%9")) {
					console.println("You did not enter enough parameters, please review the library query file");
					console.println("");
				} else {
					sqlDatabase.setListMode(listMode);
					sqlDatabase.setSep(this.getSeparator());
					sqlDatabase.executeSelectQuery(query);
				}
			} else {
				// log.debug("OTHER Statement");
				sqlDatabase.executeUpdateQuery(query);
			}
		} catch (BroadSQLException se) {
			console.error(se);
			console.println(qry);
			console.println("");
		}
	}

	private void warnIfInstanceMismatch(FileCatalog catalog, String resolved) {
		EntryMetadata metadata = catalog.getEntryMetadata(resolved);
		String currentInstance = CommandUtils.currentInstance(platform, getDatabaseConnectionsVault());
		if (!metadata.appliesToInstance(currentInstance)) {
			console.println("This query is tagged for instance " + metadata.instanceDisplayValue()
					+ ", the current connection's instance is " + (StringUtils.isBlank(currentInstance) ? "unknown" : currentInstance));
		}
	}

	private void warnIfEnvironmentMismatch(FileCatalog catalog, String resolved) {
		EntryMetadata metadata = catalog.getEntryMetadata(resolved);
		String currentEnvironment = CommandUtils.currentEnvironment(platform, getDatabaseConnectionsVault());
		if (!metadata.appliesToEnvironment(currentEnvironment)) {
			console.println("This query is tagged for environment " + metadata.environmentDisplayValue()
					+ ", the current connection's environment is " + (StringUtils.isBlank(currentEnvironment) ? "unknown" : currentEnvironment));
		}
	}

	private void warnIfTooManySuppliedParams(FileCatalog catalog, String resolved, String[] args) throws BroadSQLException {
		int supplied = args != null ? args.length - 1 : 0;
		if (supplied <= 0) {
			return;
		}
		Set<Integer> declared = catalog.getParamNumbers(resolved);
		if (supplied > declared.size()) {
			console.println(supplied + " parameter(s) given, this query only uses " + describeParams(declared) + " - extra parameter(s) ignored");
		}
	}

	private static String describeParams(Set<Integer> declared) {
		if (declared.isEmpty()) {
			return "no parameters";
		}
		StringBuilder result = new StringBuilder();
		for (Integer param : declared) {
			if (result.length() > 0) {
				result.append(", ");
			}
			result.append("%").append(param);
		}
		return result.toString();
	}

	@Override
	public String getDescription() {
		return ("Executes a SQL query stored in the queries library");
	}

	@Override
	public String getArguments() {
		return "<name> (mandatory) a file name, @alias, or search term. Default extension is SQL";
	}

	@Override
	public String getExamples() {
		return "LIB RUN COUNTRY.SQL";
	}
}
