package com.upandcoding.broadsql.controller.shell.commands.core.library;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Archives (soft-deletes) a query in the library, after confirmation: {@code LIB DEL <name>}.
 *
 * <p>{@code name} is mandatory, resolved the same way as {@code LIB SHOW}/{@code LIB RUN} (relative
 * path, {@code @alias}, or unambiguous bare file name; {@code .sql} appended automatically if omitted).
 * On confirmation, the file is moved to {@code archives/} under the library root rather than deleted;
 * see {@code LIB UNDO} (restore the most recently archived entry) and {@code LIB RESTORE <name>}
 * (restore a specific one), and {@code LIB LIST ARCHIVES} to browse what's there. There is no automatic
 * purge; see {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, section 6.
 */
public class CommandLibDel extends Command {

	public CommandLibDel() {
		super("LIB DEL", "LI DE", "LIDE");
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

		String confirm = console.inputField(new DatabaseDefinition(resolved), "Archive '" + resolved + "' [y/n]?", "", false, false, true, null);
		if (confirm != null && confirm.equalsIgnoreCase("y")) {
			catalog.archive(resolved);
			console.println("Archived (" + resolved + "). Undo with LIB UNDO, or LIB RESTORE " + resolved + ".");
			console.println("");
		} else {
			console.println("Operation aborted");
		}
	}

	@Override
	public String getDescription() {
		return ("Archives (soft-deletes) a query in the library, after confirmation; see LIB UNDO / LIB RESTORE");
	}

	@Override
	public String getArguments() {
		return "<name> (mandatory) a file name, @alias, or search term";
	}

	@Override
	public String getExamples() {
		return "LIB DEL SAREA.SQL";
	}
}
