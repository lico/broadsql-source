package com.upandcoding.broadsql.controller.shell.commands.core.script;

import java.util.Map;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.scripts.PreparedSql;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptValue;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptValueText;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptVariables;
import com.upandcoding.broadsql.dao.DatabaseConnection;

/**
 * SPRINT 0110A: helpers shared by the commands that execute SQL with {@code ${name}} references (the SQL path,
 * {@code LET}, {@code DUMP}/{@code PULL}): the session variables, the bound-value echo lines of a Script, and the
 * reported rollback after a SQL error (spec sections 9.2 and 15.3).
 */
public final class ScriptStatements {

	private ScriptStatements() {
	}

	/** The session variables of {@code command}'s interpreter (an empty namespace when it has none, e.g. some tests). */
	public static ScriptVariables variables(Command command) {
		CommandInterpreter interpreter = command.getConsoleCommandInterpreter();
		return interpreter != null ? interpreter.getScriptVariables() : new ScriptVariables();
	}

	public static boolean insideScript(Command command) {
		CommandInterpreter interpreter = command.getConsoleCommandInterpreter();
		return interpreter != null && interpreter.isRunningInsideScript();
	}

	/**
	 * In a Script, one routine line per distinct referenced variable after the statement echo:
	 * {@code -- <name> = <value>} (hidden by {@code OUTPUT QUIET}, values truncated for display, sanitized).
	 */
	public static void echoBoundValues(Command command, PreparedSql prepared) {
		if (prepared == null || !insideScript(command)) {
			return;
		}
		for (Map.Entry<String, ScriptValue> entry : prepared.getReferenced().entrySet()) {
			command.getConsole().routineln(ScriptValueText.sanitize("-- " + entry.getKey() + " = " + ScriptValueText.truncate(ScriptValueText.listing(entry.getValue()))));
		}
	}

	/**
	 * The existing rollback after a SQL error, now reported (spec 15.3): rolls back, prints the error, then a
	 * warning when pending work tracked by BroadSQL was discarded.
	 */
	public static void reportSqlError(DatabaseConnection db, ShellConsole console, BroadSQLException error) throws BroadSQLException {
		boolean pendingDiscarded = db.rollbackAfterSqlError();
		console.error(error, false);
		if (pendingDiscarded) {
			String connection = db.getPlatform() != null ? db.getPlatform().getId() : "the current connection";
			console.warn("Pending changes since the last COMMIT on " + connection + " were rolled back.");
		}
	}
}
