package com.upandcoding.broadsql.controller.shell.commands.core.scripts;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;

/**
 * Displays the content of a saved script: {@code SCRIPT SHOW <name>}. Same shape as {@code LIB SHOW}
 * (see its Javadoc), applied to the {@code Scripts} catalog.
 */
public class CommandScriptShow extends Command {

	public CommandScriptShow() {
		super("SCRIPT SHOW", "SC SH", "SCSH");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String requested = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		if (StringUtils.isBlank(requested)) {
			console.println("No search string specified");
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

		console.println("Content of scripts catalog: " + resolved);
		for (String line : catalog.getRawContent(resolved).split("\\r?\\n", -1)) {
			console.println(line);
		}
		console.println("");
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
		return ("Displays a script from the scripts catalog");
	}

	@Override
	public String getArguments() {
		return "<name> (mandatory) a file name, @alias, or search term";
	}

	@Override
	public String getExamples() {
		return "SCRIPT SHOW DAILY.SQL";
	}
}
