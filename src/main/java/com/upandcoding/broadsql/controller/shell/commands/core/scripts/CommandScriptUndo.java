package com.upandcoding.broadsql.controller.shell.commands.core.scripts;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;

/**
 * Restores the most recently archived ({@code SCRIPT DEL}) script: {@code SCRIPT UNDO}. Same shape as
 * {@code LIB UNDO} (see its Javadoc), applied to the {@code Scripts} catalog.
 */
public class CommandScriptUndo extends Command {

	public CommandScriptUndo() {
		super("SCRIPT UNDO", "SC UN", "SCUN");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		FileCatalog catalog = new FileCatalog(consoleSettings.getScriptsPath(), BroadSQLErrorMessages.ERR_SCRIPTS_01);
		try {
			catalog.undo();
			console.println("Restored the most recently archived scripts catalog entry.");
		} catch (BroadSQLException e) {
			console.println(e.getMessage());
		}
		console.println("");
	}

	@Override
	public String getDescription() {
		return ("Restores the most recently archived (SCRIPT DEL) scripts catalog entry");
	}

	@Override
	public String getArguments() {
		return "none";
	}

	@Override
	public String getExamples() {
		return "SCRIPT UNDO";
	}
}
