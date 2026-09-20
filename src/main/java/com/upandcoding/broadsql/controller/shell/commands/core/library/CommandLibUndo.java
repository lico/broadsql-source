package com.upandcoding.broadsql.controller.shell.commands.core.library;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;

/**
 * Restores the most recently archived ({@code LIB DEL}) library entry: {@code LIB UNDO}.
 *
 * <p>Refuses (does not overwrite) if a live entry already occupies the archived item's original path;
 * see {@code FileCatalog.restore}/{@code undo}, and {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, section 6.
 */
public class CommandLibUndo extends Command {

	public CommandLibUndo() {
		super("LIB UNDO", "LI UN", "LIUN");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		if (StringUtils.isBlank(consoleSettings.getLibraryPath())) {
			console.println("No SQL library folder specified in the INI file");
			return;
		}

		FileCatalog catalog = new FileCatalog(consoleSettings.getLibraryPath());
		try {
			catalog.undo();
			console.println("Restored the most recently archived library entry.");
		} catch (BroadSQLException e) {
			console.println(e.getMessage());
		}
		console.println("");
	}

	@Override
	public String getDescription() {
		return ("Restores the most recently archived (LIB DEL) library entry");
	}

	@Override
	public String getArguments() {
		return "none";
	}

	@Override
	public String getExamples() {
		return "LIB UNDO";
	}
}
