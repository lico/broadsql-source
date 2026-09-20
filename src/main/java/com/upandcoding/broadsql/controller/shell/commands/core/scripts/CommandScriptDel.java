package com.upandcoding.broadsql.controller.shell.commands.core.scripts;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Archives (soft-deletes) a script, after confirmation: {@code SCRIPT DEL <name>}. Same shape as
 * {@code LIB DEL} (see its Javadoc), applied to the {@code Scripts} catalog; see {@code SCRIPT UNDO}/
 * {@code SCRIPT RESTORE}/{@code SCRIPT LIST ARCHIVES}.
 */
public class CommandScriptDel extends Command {

	public CommandScriptDel() {
		super("SCRIPT DEL", "SC DE", "SCDE");
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

		String confirm = console.inputField(new DatabaseDefinition(resolved), "Archive '" + resolved + "' [y/n]?", "", false, false, true, null);
		if (confirm != null && confirm.equalsIgnoreCase("y")) {
			catalog.archive(resolved);
			console.println("Archived (" + resolved + "). Undo with SCRIPT UNDO, or SCRIPT RESTORE " + resolved + ".");
			console.println("");
		} else {
			console.println("Operation aborted");
		}
	}

	@Override
	public String getDescription() {
		return ("Archives (soft-deletes) a script in the scripts catalog, after confirmation; see SCRIPT UNDO / SCRIPT RESTORE");
	}

	@Override
	public String getArguments() {
		return "<name> (mandatory) a file name, @alias, or search term";
	}

	@Override
	public String getExamples() {
		return "SCRIPT DEL DAILY.SQL";
	}
}
