package com.upandcoding.broadsql.controller.shell.commands.core.script;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptLiterals;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptValue;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptValueText;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptVariables;
import com.upandcoding.broadsql.controller.shell.scripts.SqlReferences;
import com.upandcoding.broadsql.controller.shell.scripts.VariableReferenceScanner;

/**
 * Prints a message: {@code ECHO '<message>'}, at the prompt or in a Script.
 *
 * <p>The message is exactly one single-quoted string. Inside it, {@code ''} prints one apostrophe, {@code ${name}}
 * prints the value of a SQL scripting variable ({@code NULL} for a NULL value) and {@code $${name}} prints the
 * text {@code ${name}} unchanged. Spaces, line breaks, {@code ;} and {@code --} inside the quotes are printed as written.
 * An undefined variable makes the statement fail and nothing is printed.
 *
 * <p>The quotes are required: without them, an apostrophe or {@code --} in the text would change where the Script
 * statement ends. {@code ECHO} output is never hidden by {@code OUTPUT QUIET} and is written to the activity log.
 * Control characters coming from variable values are shown as {@code ?}. {@code ECHO} is the recommended way to
 * print messages from a Script; {@code PRINT} is unchanged.
 *
 * <p>See [Printing messages](../scripting_output_and_errors.md#printing-messages-echo) in the SQL scripting guide.
 */
public class CommandEcho extends Command {

	private static final String USAGE = "ECHO takes one single-quoted message: ECHO 'text';";

	public CommandEcho() {
		super("ECHO");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String text = textAfterKeyword(query);
		if (text.isEmpty() || text.charAt(0) != '\'') {
			throw new BroadSQLException(USAGE);
		}
		int end = ScriptLiterals.stringLiteralEnd(text, 0);
		if (end < 0 || !text.substring(end).isBlank()) {
			throw new BroadSQLException(USAGE);
		}
		String message = interpolate(text.substring(1, end - 1), ScriptStatements.variables(this));
		console.writeln(ScriptValueText.sanitize(message));
	}

	/** The message content of spec section 10.2: {@code ''}, {@code $${} and {@code ${name}} resolved. */
	static String interpolate(String content, ScriptVariables variables) throws BroadSQLException {
		StringBuilder out = new StringBuilder(content.length());
		int i = 0;
		int n = content.length();
		while (i < n) {
			char c = content.charAt(i);
			if (c == '\'' && i + 1 < n && content.charAt(i + 1) == '\'') {
				out.append('\'');
				i += 2;
			} else if (c == '$' && content.startsWith("$${", i)) {
				out.append("${");
				i += 3;
			} else if (c == '$' && content.startsWith("${", i)) {
				VariableReferenceScanner.Occurrence reference = VariableReferenceScanner.referenceAt(content, i);
				if (reference.isMalformed()) {
					throw SqlReferences.malformed(reference.text());
				}
				ScriptValue value = variables.get(reference.name());
				if (value == null) {
					throw SqlReferences.undefined(reference.name());
				}
				out.append(ScriptValueText.render(value));
				i = reference.end();
			} else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	@Override
	public String getDescription() {
		return "Prints a single-quoted message, with ${name} variable values";
	}

	@Override
	public String getDetailedDescription() {
		return "Prints a message, at the prompt or in a Script. The message is exactly one single-quoted string: '' prints an apostrophe, ${name} "
				+ "prints a SQL scripting variable's value, $${ prints ${. Never hidden by OUTPUT QUIET.";
	}

	@Override
	public String getArguments() {
		return "'<message>' (mandatory) one single-quoted string";
	}

	@Override
	public String getExamples() {
		return "ECHO 'Loading customers';\nECHO 'Customer ${customer_id} has ${order_count} orders';\nECHO 'It''s done';\nECHO 'Write $${name} to use a variable';";
	}
}
