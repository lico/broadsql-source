package com.upandcoding.broadsql.controller.shell.commands.core.scripting;

import java.util.function.Consumer;
import java.util.function.Function;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;

/**
 * Runs one {@code JS} script or inline snippet against a fresh set of bindings (see
 * {@code docs/LIGHT_SCRIPTING.md}, rules 2-6) - never reused/shared across runs, each run gets a clean
 * slate:
 * <ul>
 * <li>{@code connect(name)} opens a new, independent {@link ScriptConnection}.</li>
 * <li>{@code db} aliases the interactive session's own active connection, or is absent
 * ({@code null}) if nothing is connected.</li>
 * <li>{@code print}/{@code println} write through {@link ShellConsole}, never Nashorn's own default
 * console global (which writes {@code System.out} directly).</li>
 * <li>{@code args} is a plain Java {@code String[]} of the trailing tokens from {@code JS RUN <file>
 * arg1 arg2}, directly indexable from the script ({@code args[0]}).</li>
 * </ul>
 *
 * <p>Every failure - a syntax error, a thrown script value, or a SQL error surfacing from
 * {@link ScriptConnection#execute}/{@code executeUpdate} - is caught here and turned into a
 * {@link BroadSQLException}, never left to crash the shell.
 *
 * <p><b>Empirically confirmed</b> (JDK 21, {@code nashorn-core} 15.7 - see the throwaway verification
 * run logged in {@code docs/TECHNICAL_CHANGE.md}): a checked exception thrown by a Java method called
 * directly from a script (e.g. {@link ScriptConnection#execute}) is <i>not</i> wrapped in
 * {@link ScriptException} by Nashorn - it surfaces as a plain {@link RuntimeException} whose
 * {@link Throwable#getCause()} is the original exception. The bound {@code connect} function below
 * mirrors that same convention by hand (a JS-callable {@link Function} cannot itself declare a checked
 * exception), so a single {@code catch (RuntimeException)} below handles both cases uniformly.
 */
public final class JsScriptRunner {

	private JsScriptRunner() {
	}

	public static void run(String scriptBody, ShellConsole console, DatabaseConnection sqlDatabase,
			DatabaseDefinitionsVault vault, String[] args) throws BroadSQLException {

		ScriptEngine engine = new ScriptEngineManager(JsScriptRunner.class.getClassLoader()).getEngineByName("nashorn");
		if (engine == null) {
			throw new BroadSQLException("No JavaScript engine (Nashorn) is available in this JVM");
		}

		Bindings bindings = engine.createBindings();
		bindings.put("connect", (Function<String, ScriptConnection>) name -> {
			try {
				return ScriptConnection.connect(name, vault);
			} catch (BroadSQLException e) {
				throw new RuntimeException(e);
			}
		});
		bindings.put("db", bindActiveConnection(sqlDatabase));
		bindings.put("print", (Consumer<Object>) msg -> console.print(String.valueOf(msg)));
		bindings.put("println", (Consumer<Object>) msg -> console.println(String.valueOf(msg)));
		bindings.put("args", args != null ? args : new String[0]);

		try {
			engine.eval(scriptBody, bindings);
		} catch (ScriptException e) {
			throw new BroadSQLException(scriptErrorMessage(e), e);
		} catch (RuntimeException e) {
			if (e.getCause() instanceof BroadSQLException) {
				throw (BroadSQLException) e.getCause();
			}
			throw new BroadSQLException("JS script error: " + e.getMessage(), e);
		}
	}

	private static ScriptConnection bindActiveConnection(DatabaseConnection sqlDatabase) {
		if (sqlDatabase == null || sqlDatabase.getDirectConnection() == null) {
			return null;
		}
		return ScriptConnection.wrapExisting(sqlDatabase.getDirectConnection(), "db");
	}

	private static String scriptErrorMessage(ScriptException e) {
		if (e.getLineNumber() >= 0) {
			return "JS script error at line " + e.getLineNumber() + ": " + e.getMessage();
		}
		return "JS script error: " + e.getMessage();
	}
}
