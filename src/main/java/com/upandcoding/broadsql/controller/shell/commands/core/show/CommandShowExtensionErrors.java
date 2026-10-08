package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;

/**
 * Lists the extension and command loading failures recorded when BroadSQL started:
 * {@code SHOW EXTENSION ERRORS}. Shows one line per failure (the extension JAR or class and the
 * reason), or a message when there were none. Takes no arguments. Full detail stays in the log file.
 */
public class CommandShowExtensionErrors extends Command {

	public CommandShowExtensionErrors() {
		super("SHOW EXTENSION ERRORS");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		List<String> errors = getConsoleCommandInterpreter().getCommands().getConsoleCommandLoader().getExtensionErrors();
		if (errors.isEmpty()) {
			console.println("No extension errors.", ShellConsole.MSG_INFO);
			return;
		}
		console.println(errors.size() + " extension error(s):", ShellConsole.MSG_WARN);
		for (String error : errors) {
			console.println("  " + error);
		}
	}

	@Override
	public String getDescription() {
		return "Lists the extension and command loading failures recorded at startup";
	}

	@Override
	public String getExamples() {
		return "SHOW EXTENSION ERRORS;";
	}
}
