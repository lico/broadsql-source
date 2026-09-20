package com.upandcoding.broadsql.controller.shell.commands.core.scripts;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;

/**
 * Restores a specific archived ({@code SCRIPT DEL}) script: {@code SCRIPT RESTORE <name>}. Same shape
 * as {@code LIB RESTORE} (see its Javadoc), applied to the {@code Scripts} catalog.
 */
public class CommandScriptRestore extends Command {

	public CommandScriptRestore() {
		super("SCRIPT RESTORE", "SC RS", "SCRS");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String requested = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		if (StringUtils.isBlank(requested)) {
			console.println("You must provide the name of an archived entry to restore");
			return;
		}

		FileCatalog catalog = new FileCatalog(consoleSettings.getScriptsPath(), BroadSQLErrorMessages.ERR_SCRIPTS_01);
		try {
			catalog.restore(requested);
			console.println("Restored '" + requested + "'.");
		} catch (BroadSQLException e) {
			console.println(e.getMessage());
		}
		console.println("");
	}

	@Override
	public String getDescription() {
		return ("Restores a specific archived (SCRIPT DEL) scripts catalog entry");
	}

	@Override
	public String getArguments() {
		return "<name> (mandatory) the archived entry's original file name";
	}

	@Override
	public String getExamples() {
		return "SCRIPT RESTORE DAILY.SQL";
	}
}
