package com.upandcoding.broadsql.controller.shell.commands.core.misc;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Prints text to the console: {@code PRINT <text>} (or {@code PRNT}).
 *
 * <p>With no argument, prints a blank line. Unquoted text is split on spaces and rejoined with
 * {@code ", "} - so {@code PRINT Hello World;} prints {@code Hello, World}, not {@code Hello World}.
 * To print multi-word text verbatim, quote it: {@code PRINT "Hello World";}.
 *
 * <p>In Scripts, prefer {@code ECHO 'message';}: it prints the text exactly as written, can include variable
 * values with {@code ${name}}, and is never hidden by {@code OUTPUT QUIET}.
 */
public class CommandPrint extends Command {

	public CommandPrint() {
		super("PRINT", "PRNT");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		
		if (CommandUtils.isValidArgs(args)) {
			String msg = String.join(", ", args);
			console.println(msg);
		} else {
			console.println("");
		}
	}

	@Override
	public String getDescription() {
		return ("Prints text");
	}

	@Override
	public String getDetailedDescription() {
		return "Prints text. Unquoted words are joined with \", \"; quote the text to print it verbatim. In Scripts, prefer ECHO 'message', "
				+ "which prints the text exactly as written and can include ${name} variable values.";
	}

	@Override
	public String getArguments() {
		return "<text> text to print, if empty prints a blank line";
	}

	@Override
	public String getExamples() {
		return "PRINT Hello World;";
	}
}
