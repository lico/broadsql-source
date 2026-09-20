package com.upandcoding.broadsql.controller.shell.commands.core.scripts;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExternalFile;

/**
 * Runs a saved script from the scripts catalog: {@code SCRIPT RUN <name>}.
 *
 * <p>Sugar for the existing {@code @<path>} mechanism (see {@code CommandExternalFile}): {@code name}
 * is resolved against the {@code Scripts} catalog (relative path, {@code @alias}, or unambiguous bare
 * file name, same resolution as {@code LIB RUN}/{@code SCRIPT SHOW}), then every statement in the
 * resolved file is run exactly as {@code @<path>} would run it:
 * {@link CommandExternalFile#runScriptFile(String)} is the exact same code path, so behavior is
 * identical. Unlike {@code LIB RUN}, there is no {@code %N} positional parameter substitution, since a
 * script is an arbitrary sequence of BroadSQL/SQL statements, not a single parameterized query (see
 * {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, section 2). Also prints a one-line warning, without
 * refusing to run, for each of the entry's {@code @instance}/{@code @environment} metadata that
 * doesn't match the current connection's instance/environment (section 4).
 */
public class CommandScriptRun extends Command {

	public CommandScriptRun() {
		super("SCRIPT RUN", "SC RU", "SCRU");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String requested = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		if (StringUtils.isBlank(requested)) {
			console.println("You must provide a file name");
			return;
		}

		FileCatalog catalog = new FileCatalog(consoleSettings.getScriptsPath(), BroadSQLErrorMessages.ERR_SCRIPTS_01);
		String resolved = catalog.resolve(requested);
		if (resolved == null) {
			console.println("The scripts catalog does not contain the requested file");
			return;
		}

		warnIfInstanceMismatch(catalog, resolved);
		warnIfEnvironmentMismatch(catalog, resolved);

		String absolutePath = catalog.getNames().get(resolved);
		buildDelegate().runScriptFile(absolutePath);
	}

	/** A {@code CommandExternalFile} wired with this command's own already-populated collaborators. */
	private CommandExternalFile buildDelegate() {
		CommandExternalFile delegate = new CommandExternalFile();
		delegate.setConsole(console);
		delegate.setSqlDatabase(sqlDatabase);
		delegate.setConsoleSettings(consoleSettings);
		delegate.setShellConsolePrinter(shellConsolePrinter);
		delegate.setConsoleCommandInterpreter(getConsoleCommandInterpreter());
		delegate.setDatabaseConnectionsVault(getDatabaseConnectionsVault());
		delegate.setPlatform(platform);
		delegate.setListMode(listMode);
		delegate.setExtractMode(extractMode);
		return delegate;
	}

	private void warnIfInstanceMismatch(FileCatalog catalog, String resolved) {
		EntryMetadata metadata = catalog.getEntryMetadata(resolved);
		String currentInstance = CommandUtils.currentInstance(platform, getDatabaseConnectionsVault());
		if (!metadata.appliesToInstance(currentInstance)) {
			console.println("This script is tagged for instance " + metadata.instanceDisplayValue()
					+ ", the current connection's instance is " + (StringUtils.isBlank(currentInstance) ? "unknown" : currentInstance));
		}
	}

	private void warnIfEnvironmentMismatch(FileCatalog catalog, String resolved) {
		EntryMetadata metadata = catalog.getEntryMetadata(resolved);
		String currentEnvironment = CommandUtils.currentEnvironment(platform, getDatabaseConnectionsVault());
		if (!metadata.appliesToEnvironment(currentEnvironment)) {
			console.println("This script is tagged for environment " + metadata.environmentDisplayValue()
					+ ", the current connection's environment is " + (StringUtils.isBlank(currentEnvironment) ? "unknown" : currentEnvironment));
		}
	}

	@Override
	public String getDescription() {
		return ("Runs a saved script from the scripts catalog: sugar for @<resolved path>, no %N parameter substitution");
	}

	@Override
	public String getArguments() {
		return "<name> (mandatory) a file name, @alias, or search term";
	}

	@Override
	public String getExamples() {
		return "SCRIPT RUN DAILY.SQL";
	}
}
