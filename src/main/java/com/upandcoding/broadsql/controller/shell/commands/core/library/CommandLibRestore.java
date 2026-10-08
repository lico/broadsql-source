package com.upandcoding.broadsql.controller.shell.commands.core.library;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;

/**
 * Restores an archived Script: {@code LIB RESTORE <script>}.
 *
 * <p>{@code script} is the path the Script had in the library (as shown by {@code LIB LIST ARCHIVES}),
 * matched exactly: the most recently archived version of that path is moved back to its original
 * location. Refuses (does not overwrite) if a Script already occupies that path. The BroadSQL Editor's
 * Recently Deleted restores through the same operation.
 */
public class CommandLibRestore extends Command {

	public CommandLibRestore() {
		super("LIB RESTORE", "LI RS", "LIRS");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String requested = CommandUtils.isValidArgs(args) ? args[0].trim() : null;
		if (StringUtils.isBlank(requested)) {
			throw new BroadSQLException("You must provide the path of an archived script to restore");
		}

		ScriptsLibrary library = LibraryScripts.open(consoleSettings);
		String key = library.keyOf(LibraryScripts.resolve(consoleSettings, requested));
		library.restore(key);
		console.success("Restored '" + key + "'.");
		console.println("");
	}

	@Override
	public String getDescription() {
		return "Restores a specific archived (LIB DEL) Script";
	}

	@Override
	public String getArguments() {
		return "<script> (mandatory) the archived Script's original path in the Scripts Library";
	}

	@Override
	public String getExamples() {
		return "LIB RESTORE maintenance/old-cleanup.bsql";
	}

	/** SPRINT 2409K: the one argument is an archived Script's original path (live Scripts cannot be restored). */
	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.ARCHIVED_SCRIPT);
	}

}
