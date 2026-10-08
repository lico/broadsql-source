package com.upandcoding.broadsql.controller.shell.commands.core.script;

import java.util.ArrayList;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.output.ConsolePrinter;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptValueText;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptVariables;

/**
 * Lists the SQL scripting variables of the session: {@code SHOW SCRIPT VARIABLES}.
 *
 * <p>One row per variable, sorted by name: its name as last assigned, its type and its value. String values are
 * shown between single quotes (so the string {@code 'NULL'} is told apart from a NULL value, shown {@code NULL}).
 * Only variables set with {@code LET} or passed as Script arguments are listed, never API variables set with
 * {@code VAR}. Allowed at the prompt and in Scripts; the list is shown even under {@code OUTPUT QUIET}.
 *
 * <p>See [Script variables](../scripting_variables.md) in the SQL scripting guide.
 */
public class CommandShowScriptVariables extends Command {

	public CommandShowScriptVariables() {
		super("SHOW SCRIPT VARIABLES");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		if (!textAfterKeyword(query).isEmpty()) {
			throw new BroadSQLException("SHOW SCRIPT VARIABLES takes no argument");
		}
		ScriptVariables variables = ScriptStatements.variables(this);
		if (variables.isEmpty()) {
			console.println("No script variables are defined.");
			console.println("");
			return;
		}
		List<List<String>> rows = new ArrayList<>();
		for (ScriptVariables.Entry entry : variables.sorted()) {
			rows.add(List.of(entry.name(), entry.value().getTypeName(), ScriptValueText.sanitize(ScriptValueText.listing(entry.value()))));
		}
		// a printer bound to this command's console, so the table reaches the BroadSQL Editor's Output pane during a Run
		ConsolePrinter printer = new ConsolePrinter();
		printer.shellConsole = console;
		printer.printTable(List.of("Name", "Type", "Value"), rows, false);
	}

	@Override
	public String getDescription() {
		return "Lists the SQL scripting variables of the session (set with LET or as Script arguments)";
	}

	@Override
	public String getExamples() {
		return "SHOW SCRIPT VARIABLES;";
	}
}
