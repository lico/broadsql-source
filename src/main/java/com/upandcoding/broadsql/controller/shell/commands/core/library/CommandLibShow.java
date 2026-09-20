package com.upandcoding.broadsql.controller.shell.commands.core.library;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;

/**
 * Displays the content of a saved query from the SQL library: {@code LIB SHOW <name>}.
 *
 * <p>{@code name} is mandatory, matched against the file's relative path, a declared {@code @alias},
 * or (if unambiguous) its bare file name, same resolution as {@code LIB RUN}/{@code LIB DEL}; the
 * {@code .sql} extension is appended automatically if omitted and nothing else matched. Prints the
 * entry's full content, metadata header included, or an error if no such entry exists. Also prints a
 * one-line warning, without refusing to show the content, for each of the entry's {@code @instance}/
 * {@code @environment} metadata that doesn't match the current connection's instance/environment (see
 * {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, section 4).
 */
public class CommandLibShow extends Command {

	public CommandLibShow() {
		super("LIB SHOW", "LI SH", "LISH");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String requested = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		if (StringUtils.isBlank(consoleSettings.getLibraryPath())) {
			console.println("No SQL library folder specified in the INI file");
			return;
		}
		if (StringUtils.isBlank(requested)) {
			console.println("No search string specified");
			return;
		}

		FileCatalog catalog = new FileCatalog(consoleSettings.getLibraryPath());
		String resolved = catalog.resolve(requested);
		if (resolved == null) {
			console.println("The SQL library does not contain the requested query file");
			return;
		}

		warnIfInstanceMismatch(catalog, resolved);
		warnIfEnvironmentMismatch(catalog, resolved);

		console.println("Content of SQL Library: " + resolved);
		for (String line : catalog.getRawContent(resolved).split("\\r?\\n", -1)) {
			console.println(line);
		}
		console.println("");
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

	@Override
	public String getDescription() {
		return ("Displays a SQL query from the library");
	}

	@Override
	public String getArguments() {
		return "<name> (mandatory) a file name, @alias, or search term";
	}

	@Override
	public String getExamples() {
		return "LIB SHOW COUNTRY.SQL";
	}
}
