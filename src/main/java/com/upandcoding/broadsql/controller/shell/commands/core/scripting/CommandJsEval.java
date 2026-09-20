package com.upandcoding.broadsql.controller.shell.commands.core.scripting;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;

/**
 * Runs a short piece of JavaScript typed directly on the command line: {@code JS EVAL <code>}, the
 * quick, no-file equivalent of {@link CommandJsRun}. See {@code docs/LIGHT_SCRIPTING.md} for the full
 * scripting API ({@code connect}/{@code db}/{@code print}/{@code println}).
 *
 * <p>No sandbox: a script runs at the same trust level as an extension JAR or a {@code .bat} file the
 * user already runs. Java interop ({@code Java.type(...)}, {@code Packages}) is available, and a
 * script that never blocks (e.g. an infinite loop) cannot be interrupted with CTRL+C.
 */
public class CommandJsEval extends Command {

	public CommandJsEval() {
		super("JS EVAL", "JS EV", "JSEV");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String code = StringUtils.trimToEmpty(StringUtils.removeStartIgnoreCase(query.trim(), getKeywords()[0]));
		if (StringUtils.isBlank(code)) {
			console.println("You must provide JavaScript code to evaluate");
			return;
		}
		JsScriptRunner.run(code, console, sqlDatabase, getDatabaseConnectionsVault(), null);
	}

	@Override
	public String getDescription() {
		return ("Runs a short piece of JavaScript typed directly on the command line");
	}

	@Override
	public String getArguments() {
		return "<code> (mandatory) inline JavaScript code";
	}

	@Override
	public String getExamples() {
		return "JS EVAL println(2 + 2);";
	}
}
