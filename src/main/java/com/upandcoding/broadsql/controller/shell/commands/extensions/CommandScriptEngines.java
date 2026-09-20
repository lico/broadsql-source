package com.upandcoding.broadsql.controller.shell.commands.extensions;

import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;

/**
 * Lists the scripting engines available to the running JVM: {@code SHOW SCRIPT ENGINES}. Takes no
 * arguments.
 *
 * <p>For each {@code javax.script} engine found on the classpath, prints its name, version,
 * supported language and language version, and recognized aliases. Useful mainly to check whether a
 * given scripting language (e.g. JavaScript/Nashorn) is available in the current Java runtime before
 * relying on it elsewhere.
 */
public class CommandScriptEngines extends Command {

	private final static Logger log = LoggerFactory.getLogger(CommandScriptEngines.class);

	public CommandScriptEngines() {
		super("SHOW SCRIPT ENGINES", "SHOW ENGINES", "SH EN");
	}

	@Override
	public boolean isHidden() {
		return (true);
	}

	public void showEngines(ShellConsole shellConsole) throws ScriptException {
		shellConsole.println("Supported Script Engines:");
		ClassLoader ctxtLoader = Thread.currentThread().getContextClassLoader();
		final ScriptEngineManager manager = new ScriptEngineManager(getClass().getClassLoader());
		if (manager.getEngineFactories() == null || manager.getEngineFactories().isEmpty()) {
			shellConsole.println("No script engine is supported");
		} else {
			for (ScriptEngineFactory se : manager.getEngineFactories()) {
				shellConsole.println("ScriptEngine: " + se.getEngineName());
				shellConsole.println("\tVersion: " + se.getEngineVersion());
				shellConsole.println("\tLanguage: " + se.getLanguageName());
				shellConsole.println("\tLanguage Version: " + se.getLanguageVersion());
				shellConsole.println("\tNames: " + se.getNames());
			}
		}
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String engineName = "JavaScript";

		try {
			showEngines(console);
		} catch (ScriptException ex) {
			console.println(ex.getMessage());
		}
		console.print("\n");
	}

	@Override
	public String getDescription() {
		return ("Displays list of supported script engines");
	}

	@Override
	public String getArguments() {
		return "";
	}

	@Override
	public String getExamples() {
		return "SHOW SCRIPT ENGINES;";
	}
}
