package com.upandcoding.broadsql.controller.shell.commands.core.script;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptDirectives;

/**
 * Chooses what a Script does after a failed statement: {@code ON ERROR STOP} or {@code ON ERROR CONTINUE}.
 *
 * <p>Allowed in Scripts only. Every Script run starts with {@code CONTINUE}: a failed statement is reported and
 * counted, and the next statement runs. After {@code ON ERROR STOP}, the first failed statement ends the Script:
 * nothing after it runs, a message names the statement and its line, and the Script's status is {@code FAILED}.
 * A Script called by this Script starts with the same setting; a change made inside it ends when it returns.
 *
 * <p>A failed SQL statement rolls back the pending transaction, as it always did: with autocommit OFF, earlier
 * uncommitted changes are lost too, and a warning says so. {@code ON ERROR STOP} itself never commits or rolls
 * back. At the prompt, a line of several statements already stops at its first failure.
 *
 * <p>See [Error handling](../scripting_output_and_errors.md#error-handling-on-error) in the SQL scripting guide, including what
 * {@code ON ERROR CONTINUE} means for the transaction.
 */
public class CommandOnError extends Command {

	public CommandOnError() {
		super(ScriptDirectives.ON_ERROR);
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		CommandInterpreter interpreter = getConsoleCommandInterpreter();
		if (interpreter == null || !interpreter.isRunningInsideScript()) {
			throw new BroadSQLException("ON ERROR applies inside Scripts only; a line of several statements typed at the prompt already stops at its first failure");
		}
		interpreter.getScriptContext().current().setErrorPolicy(ScriptDirectives.parseOnError(query));
	}

	@Override
	public String getDescription() {
		return "In a Script, stops at the first failed statement (STOP) or continues (CONTINUE, the default)";
	}

	@Override
	public String getArguments() {
		return "STOP | CONTINUE (mandatory)";
	}

	@Override
	public String getExamples() {
		return "ON ERROR STOP;\nON ERROR CONTINUE;";
	}
}
