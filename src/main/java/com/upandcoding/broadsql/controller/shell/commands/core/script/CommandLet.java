package com.upandcoding.broadsql.controller.shell.commands.core.script;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.SqlExecutionException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.scripts.PreparedSql;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptLiterals;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptValue;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptValueText;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptVariables;
import com.upandcoding.broadsql.controller.shell.scripts.SqlReferences;
import com.upandcoding.broadsql.controller.shell.scripts.VariableNames;
import com.upandcoding.broadsql.controller.shell.scripts.VariableReferenceScanner;
import com.upandcoding.broadsql.dao.DatabaseConnection;

/**
 * Assigns a SQL scripting variable: {@code LET <name> = <value>}, at the prompt or in a Script.
 *
 * <p>The value is a literal, a copy of another variable, or a query:
 * <ul>
 * <li>a number ({@code 42}, {@code -1.5}), a single-quoted string ({@code 'O''Brien'}), {@code TRUE}, {@code FALSE}
 * or {@code NULL};</li>
 * <li>{@code ${other}}: an exact copy of another variable, same value and same type;</li>
 * <li>a query starting with {@code SELECT}, {@code WITH} or {@code VALUES}: it runs on the current connection, in
 * the current transaction, and must return exactly one row and one column. Zero rows, several rows or several
 * columns are errors; a NULL value is assigned as NULL. The query result is never displayed and never becomes
 * the last result.</li>
 * </ul>
 *
 * <p>Use the variable as a SQL value with {@code ${name}}: {@code SELECT * FROM orders WHERE customer_id = ${id};}.
 * The value is sent to the database as a typed parameter, never pasted into the SQL text, so an apostrophe needs
 * no escaping and a value can never change the statement. Names are case-insensitive; {@code ENV}, {@code NULL},
 * {@code TRUE} and {@code FALSE} are reserved.
 *
 * <p>Variables belong to the BroadSQL session: Scripts and the prompt share them, they survive {@code CONNECT}
 * and {@code ENV}, and they are lost when BroadSQL exits. A failed assignment leaves the variable unchanged. List
 * them with {@code SHOW SCRIPT VARIABLES}. {@code LET} is unrelated to {@code VAR}, which sets API variables.
 *
 * <p>See [Script variables](../scripting_variables.md) in the SQL scripting guide for examples.
 */
public class CommandLet extends Command {

	public CommandLet() {
		super("LET");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String text = textAfterKeyword(query);
		int n = text.length();
		int nameEnd = 0;
		while (nameEnd < n && !Character.isWhitespace(text.charAt(nameEnd)) && text.charAt(nameEnd) != '=') {
			nameEnd++;
		}
		String name = text.substring(0, nameEnd);
		if (name.isEmpty()) {
			throw new BroadSQLException("Missing variable name: LET <name> = <value>. Use SHOW SCRIPT VARIABLES to list the variables");
		}
		String problem = VariableNames.problem(name, "variable name");
		if (problem != null) {
			throw new BroadSQLException(problem);
		}
		int equals = nameEnd;
		while (equals < n && Character.isWhitespace(text.charAt(equals))) {
			equals++;
		}
		if (equals >= n || text.charAt(equals) != '=') {
			throw new BroadSQLException("Missing = after LET " + name + ": LET <name> = <value>");
		}
		String valueText = text.substring(equals + 1).strip();
		if (valueText.isEmpty()) {
			throw new BroadSQLException("Missing value: LET " + name + " = <value>");
		}

		ScriptVariables variables = ScriptStatements.variables(this);
		ScriptValue value;
		if (valueText.startsWith("${")) {
			value = copy(valueText, variables);
		} else if (ScriptLiterals.startsQuery(valueText)) {
			value = query(valueText, variables);
			if (value == null) {
				return; // SQL error, already reported (and rolled back)
			}
		} else {
			value = ScriptLiterals.parse(valueText);
		}
		variables.assign(name, value);
		console.routineln(ScriptValueText.confirmation(name, value));
	}

	/** {@code LET x = ${y}}: the other variable's value object itself (spec 8.2). */
	private static ScriptValue copy(String valueText, ScriptVariables variables) throws BroadSQLException {
		VariableReferenceScanner.Occurrence reference = VariableReferenceScanner.referenceAt(valueText, 0);
		if (reference.isMalformed()) {
			throw SqlReferences.malformed(reference.text());
		}
		if (reference.end() != valueText.length()) {
			throw ScriptLiterals.invalid(valueText);
		}
		ScriptValue value = variables.get(reference.name());
		if (value == null) {
			throw SqlReferences.undefined(reference.name());
		}
		return value;
	}

	/** {@code LET x = <query>} (spec 8.3); {@code null} after a reported SQL error. */
	private ScriptValue query(String sql, ScriptVariables variables) throws BroadSQLException {
		DatabaseConnection db = sqlDatabase;
		if (db == null || db.getDirectConnection() == null) {
			throw new BroadSQLException("No active SQL connection: a query assignment runs on the current connection");
		}
		PreparedSql prepared = SqlReferences.prepareAlways(sql, variables, db::isPgJdbc);
		ScriptStatements.echoBoundValues(this, prepared);
		try {
			return db.queryScalar(prepared);
		} catch (SqlExecutionException e) {
			ScriptStatements.reportSqlError(db, console, e);
			return null;
		}
	}

	@Override
	public String getDescription() {
		return "Assigns a SQL scripting variable from a literal, another variable, or a one-row query";
	}

	@Override
	public String getDetailedDescription() {
		return "Assigns a SQL scripting variable, at the prompt or in a Script. The value is a number, a single-quoted string, TRUE, FALSE, NULL, "
				+ "one variable reference ${other} (an exact copy), or a query starting with SELECT, WITH or VALUES that returns exactly one row "
				+ "and one column. Use the variable in SQL as ${name}: it is sent as a typed parameter, never pasted into the SQL text. "
				+ "Variables are shared by the prompt and every Script of the session. List them with SHOW SCRIPT VARIABLES. "
				+ "LET is unrelated to VAR, which sets API variables used by API requests only.";
	}

	@Override
	public String getArguments() {
		return "<name> = <value> (mandatory) a name made of letters, digits and _, then a literal, ${other}, or a query";
	}

	@Override
	public String getExamples() {
		return "LET customer_id = 42;\nLET country = 'FR';\nLET active = TRUE;\nLET saved_id = ${customer_id};\nLET max_id = SELECT MAX(id) FROM customer;\n"
				+ "SELECT * FROM orders WHERE customer_id = ${max_id};";
	}
}
