package com.upandcoding.broadsql.controller.shell.commands.core.library;

import java.util.List;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptExecutor;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;

/**
 * Displays the content of a Script from the Scripts Library: {@code LIB SHOW <script>}.
 *
 * <p>{@code script} is a path relative to the library root, exactly as written (see {@code LIB RUN}).
 * Prints the Script's full content, metadata header included, or an error if the library does not
 * contain it, after the parameters its {@code -- @params:} lines declare ({@code Parameters: none declared}
 * otherwise). Also prints a one-line warning, without refusing to show the content, for each of the
 * Script's {@code @instance}/{@code @environment} tags that doesn't match the current connection.
 */
public class CommandLibShow extends Command {

	public CommandLibShow() {
		super("LIB SHOW", "LI SH", "LISH");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String requested = CommandUtils.isValidArgs(args) ? args[0].trim() : null;
		if (StringUtils.isBlank(requested)) {
			throw new BroadSQLException("You must provide the name of a Scripts Library script");
		}

		ScriptsLibrary library = LibraryScripts.open(consoleSettings);
		String key = LibraryScripts.existingKey(consoleSettings, library, requested);
		String content = library.getRawContent(key);

		for (String warning : ScriptExecutor.metadataWarnings(content, platform, getDatabaseConnectionsVault(), key)) {
			console.writeln(warning);
		}
		console.writeln("Content of Scripts Library script: " + key);
		// SPRINT 0110A: the arguments each call must pass (spec section 17.8)
		console.writeln("Parameters: " + EntryMetadata.parse(content).getParamsDisplayValue());
		console.printBlock(content);
		console.writeln("");
	}

	@Override
	public String getDescription() {
		return "Displays a Script from the Scripts Library";
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
		return "LIB SHOW maintenance/cleanup.bsql";
	}
}
