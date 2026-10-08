package com.upandcoding.broadsql.controller.shell.commands.core.library;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;

/**
 * Restores the most recently archived Script: {@code LIB UNDO}. Undoes the last {@code LIB DEL} (or
 * editor Delete), across the whole Scripts Library.
 */
public class CommandLibUndo extends Command {

	public CommandLibUndo() {
		super("LIB UNDO", "LI UN", "LIUN");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		ScriptsLibrary library = LibraryScripts.open(consoleSettings);
		library.undo();
		console.success("Restored the most recently archived script.");
		console.println("");
	}

	@Override
	public String getDescription() {
		return "Restores the most recently archived (LIB DEL) Script";
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
