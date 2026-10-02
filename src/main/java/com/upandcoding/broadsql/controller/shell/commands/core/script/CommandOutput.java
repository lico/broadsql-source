package com.upandcoding.broadsql.controller.shell.commands.core.script;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptDirectives;

/**
 * Hides or shows the routine output of a Script: {@code OUTPUT QUIET} or {@code OUTPUT NORMAL}.
 *
 * <p>Allowed in Scripts only. Every Script run starts in {@code NORMAL}. {@code QUIET} hides the statement echo,
 * the {@code Found N queries} line, the variable value lines, the row counts of inserts, updates and deletes, the
 * blank separator lines, the {@code LET} confirmations, and the final status line when the status is
 * {@code SUCCESS}. It never hides query results, {@code ECHO} messages, {@code SHOW SCRIPT VARIABLES}, the output of
 * other commands, warnings, errors, or a final status other than {@code SUCCESS}.
 *
 * <p>The setting lasts until the end of the Script or the next {@code OUTPUT} statement; a Script it calls starts
 * with the same setting. The activity log always receives everything, including the hidden lines.
 *
 * <p>See [Controlling output](../scripting_output_and_errors.md#controlling-output-output-quiet-and-output-normal) in the SQL scripting
 * guide.
 */
public class CommandOutput extends Command {

	public CommandOutput() {
		super(ScriptDirectives.OUTPUT);
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		CommandInterpreter interpreter = getConsoleCommandInterpreter();
		if (interpreter == null || !interpreter.isRunningInsideScript()) {
			throw new BroadSQLException("OUTPUT applies inside Scripts only");
		}
		interpreter.getScriptContext().current().setOutputMode(ScriptDirectives.parseOutput(query));
	}

	@Override
	public String getDescription() {
		return "In a Script, hides routine output (QUIET) or shows it again (NORMAL, the default)";
	}

	@Override
	public String getArguments() {
		return "QUIET | NORMAL (mandatory)";
	}

	@Override
	public String getExamples() {
		return "OUTPUT QUIET;\nOUTPUT NORMAL;";
	}
}
