package com.upandcoding.broadsql.controller.shell.commands.extensions;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptResolver;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunContext;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryCommandSupport;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryLauncher;
import com.upandcoding.broadsql.controller.shell.swing.scriptlibrary.ScriptLibraryWorkspaceLauncher;

/**
 * Opens the BroadSQL Editor: {@code EDIT} (or {@code ED}).
 *
 * <p>The BroadSQL Editor shows the Scripts Library as a folder tree and edits any text Script in it.
 * {@code EDIT} with no name opens it with nothing selected. {@code EDIT <script>} takes a path relative to
 * the Scripts Library, exactly as {@code LIB RUN} does (no alias, file-name search or extension guessing):
 * <ul>
 * <li>if that Script exists, it opens, on its existing tab if it is already open;</li>
 * <li>if it does not, the editor offers to create it (a name without an extension gets {@code .bsql}).</li>
 * </ul>
 * Opening the Editor needs no database connection; only running a Script from inside it does.
 */
public class CommandEdit extends Command {

	private ScriptLibraryLauncher scriptLibraryLauncher;

	public CommandEdit() {
		super("EDIT", "ED");
	}

	@Override
	public boolean isHidden() {
		return (false);
	}

	/** Test seam - see {@link ScriptLibraryLauncher}'s own javadoc. Production wiring lazily defaults to {@link ScriptLibraryWorkspaceLauncher} on first use. */
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
		return ("Opens the BroadSQL Editor, the native workspace for browsing, editing, formatting, validating, running and versioning the Scripts Library; with a name, opens/focuses that Script");
	}

	@Override
	public String getArguments() {
		return "script (optional) a path relative to the Scripts Library; with no name, opens the Editor on a new, unsaved Script";
	}

	@Override
	public String getExamples() {
		return "EDIT;\nEDIT CUSTOMER.SQL;\nEDIT LIB:CUSTOMER.SQL;";
	}
}
