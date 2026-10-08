package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.HashMap;
import java.util.Map;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.output.CapturingConsole;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptCall;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptExecutor;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptResolver;

/**
 * {@code Run} in the BroadSQL Editor: executes the currently active, saved Script through the one
 * canonical pipeline - {@link ScriptResolver} for the path, {@link ScriptExecutor} for the execution -
 * exactly what {@code @script} and {@code LIB RUN script} use (SPRINT 1909S). The editor is another
 * client of that architecture, not an alternative execution path: parameters, warnings, nested scripts,
 * errors and connection state behave identically.
 *
 * <p>Output is captured (not written to the real interactive terminal) via {@link CapturingConsole}: the
 * connection's own console, the interpreter's console and every already-loaded command's console are
 * temporarily redirected to the capture for the duration of the call and restored afterward. Statements
 * of the Script are dispatched through {@code CommandInterpreter.setQuery}/{@code executeCommand()}, the
 * same re-entry the interactive read loop uses; this shares a pre-existing, low-probability race with a
 * user typing at the terminal at the same moment (reported as a known limitation, not changed here).
 * {@code Run Selection} is not implemented.
 */
public final class ScriptRunCoordinator {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ScriptRunCoordinator.class);

	/**
	 * @param relativePathOnDisk the Script's current, already-saved path in the Scripts Library - Run
	 *                           always reads from disk, so the caller must have saved any unsaved
	 *                           changes first.
	 * @param argumentText       SPRINT 0110A: the named arguments, {@code name=value ...} (built from the parameter
	 *                           dialog driven by the Script's {@code @params}); empty when none
	 */
	public RunOutcome run(String relativePathOnDisk, ScriptRunContext context, String argumentText) {
		if (!context.hasActiveConnection()) {
			return RunOutcome.noActiveConnection();
		}

		// The capture stands in for the application's console: same log file writer, prompt and log switch.
		CapturingConsole output = CapturingConsole.standingInFor(context.consoleCommandInterpreter() != null ? context.consoleCommandInterpreter().getConsole() : null);
		ShellConsole originalCmdLineConsole = context.sqlDatabase().getCmdLineConsole();
		context.sqlDatabase().setCmdLineConsole(output);
		Map<Command, ShellConsole> originalCommandConsoles = redirectSharedCommandListConsole(context, output);
		ShellConsole originalInterpreterConsole = redirectInterpreterConsole(context, output);
		try {
			BroadSQLException error = null;
			ScriptRunResult result = null;
			try {
				ScriptResolver resolver = new ScriptResolver(context.consoleSettings().getScriptsLibraryPath());
				result = new ScriptExecutor(output, context.consoleCommandInterpreter(), context.databaseConnectionsVault())
						.run(ScriptCall.of(relativePathOnDisk, () -> resolver.resolveLibraryScript(relativePathOnDisk).getPath(), argumentText));
			} catch (RuntimeException unexpected) {
				// Never escapes to the Editor's worker thread (it would leave Run disabled and "Running..." for good):
				// reported in the Output pane like any error, with the stack trace in the application log.
				log.error("Unexpected failure while running '{}' from the BroadSQL Editor", relativePathOnDisk, unexpected);
				error = new BroadSQLException("Unexpected error: " + unexpected, unexpected);
				output.println("ERROR: " + error.getLocalizedMessage(), false);
			}
			return RunOutcome.executed(output.getOutput(), error, output.getErrorCount(), result);
		} finally {
			context.sqlDatabase().setCmdLineConsole(originalCmdLineConsole);
			restoreInterpreterConsole(context, originalInterpreterConsole);
			restoreSharedCommandListConsole(originalCommandConsoles);
		}
	}

	/**
	 * A Script's statements run through the interpreter's own, already-populated {@link CommandList} - a
	 * set of long-lived singletons whose {@code console} field was set once, when first loaded. Every
	 * already-loaded command's console is therefore temporarily redirected to {@code output} for this one
	 * {@code Run} and restored in the {@code finally} block above: a deliberate, documented process-wide
	 * mutation.
	 */
	private Map<Command, ShellConsole> redirectSharedCommandListConsole(ScriptRunContext context, ShellConsole output) {
		Map<Command, ShellConsole> saved = new HashMap<>();
		CommandList commands = context.consoleCommandInterpreter() != null ? context.consoleCommandInterpreter().getCommands() : null;
		if (commands == null) {
			return saved;
		}
		for (Command command : commands.values()) {
			if (!saved.containsKey(command)) {
				saved.put(command, command.getConsole());
				command.setConsole(output);
			}
		}
		return saved;
	}

	private void restoreSharedCommandListConsole(Map<Command, ShellConsole> originalCommandConsoles) {
		for (Map.Entry<Command, ShellConsole> entry : originalCommandConsoles.entrySet()) {
			entry.getKey().setConsole(entry.getValue());
		}
	}

	private ShellConsole redirectInterpreterConsole(ScriptRunContext context, ShellConsole output) {
		CommandInterpreter interpreter = context.consoleCommandInterpreter();
		if (interpreter == null) {
			return null;
		}
		ShellConsole original = interpreter.getConsole();
		interpreter.setConsole(output);
		if (interpreter.getCommands() != null) {
			interpreter.getCommands().setConsole(output);
		}
		return original;
	}

	private void restoreInterpreterConsole(ScriptRunContext context, ShellConsole original) {
		CommandInterpreter interpreter = context.consoleCommandInterpreter();
		if (interpreter == null) {
			return;
		}
		interpreter.setConsole(original);
		if (interpreter.getCommands() != null) {
			interpreter.getCommands().setConsole(original);
		}
	}
}
