package com.upandcoding.broadsql.controller.shell.commands.core.library;

import java.util.List;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptResolver;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunContext;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryCommandSupport;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryLauncher;
import com.upandcoding.broadsql.controller.shell.swing.scriptlibrary.ScriptLibraryWorkspaceLauncher;

/**
 * Opens a Script from the Scripts Library in the BroadSQL Editor: {@code LIB EDIT [<script>]}.
 *
 * <p>{@code script} is a path relative to the library root, exactly as {@code LIB RUN} takes it (no alias,
 * file-name search or extension guessing). The Script opens in the single BroadSQL Editor window, on its
 * existing tab if it is already open. No external editor is started, and opening a Script never saves
 * anything. With no name the Editor just opens. If the name matches no Script, the Editor offers to create
 * it (a name without an extension gets {@code .bsql}). {@code EDIT} opens the same
 * window.
 */
public class CommandLibEdit extends Command {

	private ScriptLibraryLauncher scriptLibraryLauncher;

	public CommandLibEdit() {
		super("LIB EDIT", "LI ED", "LIED");
	}

	@Override
	public boolean isHidden() {
		return (false);
	}

	/** Test seam - see {@link ScriptLibraryLauncher}. Production wiring lazily defaults to {@link ScriptLibraryWorkspaceLauncher} on first use. */
	public void setScriptLibraryLauncher(ScriptLibraryLauncher scriptLibraryLauncher) {
		this.scriptLibraryLauncher = scriptLibraryLauncher;
	}

	private ScriptLibraryLauncher launcher() {
		if (scriptLibraryLauncher == null) {
			scriptLibraryLauncher = new ScriptLibraryWorkspaceLauncher(consoleSettings);
		}
		return scriptLibraryLauncher;
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String requested = CommandUtils.isValidArgs(args) ? args[0].trim() : null;
		ScriptResolver resolver = new ScriptResolver(consoleSettings.getScriptsLibraryPath());
		ScriptRunContext context = new ScriptRunContext(sqlDatabase, getConsoleCommandInterpreter(), getDatabaseConnectionsVault(),
				shellConsolePrinter, consoleSettings, platform);
		ScriptLibraryCommandSupport.openOrFocus(launcher(), resolver, console, requested, context);
	}

	@Override
	public String getDescription() {
		return ("Opens a Scripts Library script in the BroadSQL Editor (nothing is saved by opening it)");
	}

	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.SCRIPT);
	}

	@Override
	public String getArguments() {
		return "script (optional) a path relative to the Scripts Library; with no name, opens the Editor on a new, unsaved Script";
	}

	@Override
	public String getExamples() {
		return "LIB EDIT COUNTRY.SQL;\nLIB EDIT country;";
	}
}
