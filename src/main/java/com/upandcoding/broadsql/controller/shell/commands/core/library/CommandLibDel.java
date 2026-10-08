package com.upandcoding.broadsql.controller.shell.commands.core.library;

import java.util.List;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import java.nio.file.Path;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Archives (soft-deletes) a Script in the Scripts Library, after confirmation: {@code LIB DEL <script>}.
 *
 * <p>{@code script} is a path relative to the library root, exactly as written. On confirmation the file
 * is moved to {@code archives/} under the library root rather than deleted; see {@code LIB UNDO} (restore
 * the most recently archived Script), {@code LIB RESTORE <script>} (restore a specific one) and
 * {@code LIB LIST ARCHIVES}. The BroadSQL Editor's Delete uses the very same operation. There is no
 * automatic purge.
 */
public class CommandLibDel extends Command {

	public CommandLibDel() {
		super("LIB DEL", "LI DE", "LIDE");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String requested = CommandUtils.isValidArgs(args) ? args[0].trim() : null;
		if (StringUtils.isBlank(requested)) {
			throw new BroadSQLException("You must provide the name of a Scripts Library script");
		}

		ScriptsLibrary library = LibraryScripts.open(consoleSettings);
		Path file = LibraryScripts.resolve(consoleSettings, requested);
		String key = LibraryScripts.existingKey(consoleSettings, library, requested);

		String confirm = console.inputField(new DatabaseDefinition(key), "Archive '" + key + "' [y/n]?", "", false, false, true, null);
		if (confirm != null && confirm.equalsIgnoreCase("y")) {
			library.archive(file);
			console.println("Archived (" + key + "). Undo with LIB UNDO, or LIB RESTORE " + key + ".");
			console.println("");
		} else {
			console.println("Operation aborted");
		}
	}

	@Override
	public String getDescription() {
		return "Archives (soft-deletes) a Script in the Scripts Library, after confirmation; see LIB UNDO / LIB RESTORE";
	}

	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.SCRIPT);
	}

	@Override
	public String getArguments() {
		return "<script> (mandatory) a path relative to the Scripts Library";
	}

	@Override
	public String getExamples() {
		return "LIB DEL maintenance/old-cleanup.bsql";
	}
}
