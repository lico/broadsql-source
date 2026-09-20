package com.upandcoding.broadsql.controller.shell.commands.core.library;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;

/**
 * Restores a specific archived ({@code LIB DEL}) library entry: {@code LIB RESTORE <name>}.
 *
 * <p>{@code name} is matched against the archived entry's original relative path or bare file name
 * (see {@code FileCatalog.restore}); if it was archived more than once, the most recent archive is
 * restored. Refuses (does not overwrite) if a live entry already occupies that path; see
 * {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, section 6.
 */
public class CommandLibRestore extends Command {

	public CommandLibRestore() {
		super("LIB RESTORE", "LI RS", "LIRS");
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
			console.println("You must provide the name of an archived entry to restore");
			return;
		}

		FileCatalog catalog = new FileCatalog(consoleSettings.getLibraryPath());
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
		return ("Restores a specific archived (LIB DEL) library entry");
	}

	@Override
	public String getArguments() {
		return "<name> (mandatory) the archived entry's original file name";
	}

	@Override
	public String getExamples() {
		return "LIB RESTORE COUNTRY.SQL";
	}
}
